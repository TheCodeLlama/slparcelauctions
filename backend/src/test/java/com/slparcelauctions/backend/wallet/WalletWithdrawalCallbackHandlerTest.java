package com.slparcelauctions.backend.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.slparcelauctions.backend.escrow.command.TerminalCommand;
import com.slparcelauctions.backend.escrow.command.TerminalCommandAction;
import com.slparcelauctions.backend.escrow.command.TerminalCommandPurpose;
import com.slparcelauctions.backend.escrow.command.TerminalCommandRepository;
import com.slparcelauctions.backend.escrow.command.TerminalCommandStatus;
import com.slparcelauctions.backend.notification.Notification;
import com.slparcelauctions.backend.notification.NotificationCategory;
import com.slparcelauctions.backend.notification.NotificationRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Integration coverage for the wallet-withdrawal callback bridge: a
 * successful terminal callback must append a {@code WITHDRAW_COMPLETED}
 * row + dispatch a {@code WALLET_WITHDRAWAL_COMPLETED} notification; a
 * stall must append {@code WITHDRAW_REVERSED}, credit balance back, and
 * dispatch {@code WALLET_WITHDRAWAL_REVERSED}.
 */
@SpringBootTest
@ActiveProfiles("dev")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class WalletWithdrawalCallbackHandlerTest {

    @Autowired WalletWithdrawalCallbackHandler handler;
    @Autowired WalletService walletService;
    @Autowired UserRepository userRepo;
    @Autowired UserLedgerRepository ledgerRepo;
    @Autowired TerminalCommandRepository cmdRepo;
    @Autowired NotificationRepository notificationRepo;
    @Autowired TransactionTemplate txTemplate;

    Clock clock;

    @BeforeEach
    void clock() {
        clock = Clock.fixed(Instant.parse("2026-05-01T18:00:00Z"), ZoneOffset.UTC);
    }

    @Test
    void onSuccess_appendsCompletedRow_andDispatchesIm() {
        User user = newUser();

        // 1. Deposit + queue a withdrawal so we have a real ledger row +
        //    terminal command pair to exercise the callback against.
        walletService.deposit(user.getSlAvatarUuid(), 500L,
                UUID.randomUUID().toString());
        WalletService.WithdrawQueuedResult res =
                walletService.withdrawSiteInitiated(user.getId(), 200L,
                        UUID.randomUUID().toString());
        UserLedgerEntry queuedEntry = res.entry();
        TerminalCommand cmd = ((WalletService.WithdrawQueuedResult.Ok) res).command();

        assertThat(queuedEntry.getEntryType()).isEqualTo(UserLedgerEntryType.WITHDRAW_QUEUED);
        long balanceAfterQueue = userRepo.findById(user.getId()).orElseThrow()
                .getBalanceLindens();
        assertThat(balanceAfterQueue).isEqualTo(300L);

        // 2. Fire the success callback through the dispatcher's normal
        //    transactional surface.
        txTemplate.executeWithoutResult(s ->
                handler.onSuccess(cmd, UUID.randomUUID().toString()));

        // 3. WITHDRAW_COMPLETED row appended (no balance change).
        List<UserLedgerEntry> entries = ledgerRepo.findTop50ByUserIdOrderByCreatedAtDesc(user.getId());
        assertThat(entries).extracting(UserLedgerEntry::getEntryType)
                .contains(UserLedgerEntryType.WITHDRAW_COMPLETED);
        UserLedgerEntry completed = entries.stream()
                .filter(e -> e.getEntryType() == UserLedgerEntryType.WITHDRAW_COMPLETED)
                .findFirst().orElseThrow();
        assertThat(completed.getRefId()).isEqualTo(queuedEntry.getId());
        assertThat(completed.getAmount()).isEqualTo(200L);

        // Balance unchanged from the queue debit.
        assertThat(userRepo.findById(user.getId()).orElseThrow()
                .getBalanceLindens()).isEqualTo(300L);

        // 4. Notification dispatched.
        List<Notification> ns = notificationRepo.findAllByUserId(user.getId());
        assertThat(ns).extracting(Notification::getCategory)
                .contains(NotificationCategory.WALLET_WITHDRAWAL_COMPLETED);
    }

    @Test
    void onStall_appendsReversedRow_creditsBalance_andDispatchesIm() {
        User user = newUser();

        walletService.deposit(user.getSlAvatarUuid(), 500L,
                UUID.randomUUID().toString());
        WalletService.WithdrawQueuedResult res =
                walletService.withdrawSiteInitiated(user.getId(), 200L,
                        UUID.randomUUID().toString());
        UserLedgerEntry queuedEntry = res.entry();
        TerminalCommand cmd = ((WalletService.WithdrawQueuedResult.Ok) res).command();

        // Sanity: balance debited.
        assertThat(userRepo.findById(user.getId()).orElseThrow()
                .getBalanceLindens()).isEqualTo(300L);

        txTemplate.executeWithoutResult(s ->
                handler.onStall(cmd, "transport timeout"));

        // WITHDRAW_REVERSED row appended + balance credited back.
        List<UserLedgerEntry> entries = ledgerRepo.findTop50ByUserIdOrderByCreatedAtDesc(user.getId());
        UserLedgerEntry reversed = entries.stream()
                .filter(e -> e.getEntryType() == UserLedgerEntryType.WITHDRAW_REVERSED)
                .findFirst().orElseThrow();
        assertThat(reversed.getRefId()).isEqualTo(queuedEntry.getId());
        assertThat(reversed.getAmount()).isEqualTo(200L);
        assertThat(userRepo.findById(user.getId()).orElseThrow()
                .getBalanceLindens()).isEqualTo(500L);

        // Notification dispatched.
        List<Notification> ns = notificationRepo.findAllByUserId(user.getId());
        assertThat(ns).extracting(Notification::getCategory)
                .contains(NotificationCategory.WALLET_WITHDRAWAL_REVERSED);
    }

    @Test
    void pendingWithdrawalAmount_excludesCompletedAndReversed() {
        User user = newUser();
        walletService.deposit(user.getSlAvatarUuid(), 1000L,
                UUID.randomUUID().toString());

        // Queue 3 withdrawals — leave one pending, complete one, reverse one.
        WalletService.WithdrawQueuedResult pending =
                walletService.withdrawSiteInitiated(user.getId(), 100L,
                        UUID.randomUUID().toString());
        WalletService.WithdrawQueuedResult done =
                walletService.withdrawSiteInitiated(user.getId(), 200L,
                        UUID.randomUUID().toString());
        WalletService.WithdrawQueuedResult bad =
                walletService.withdrawSiteInitiated(user.getId(), 300L,
                        UUID.randomUUID().toString());

        TerminalCommand doneCmd = ((WalletService.WithdrawQueuedResult.Ok) done).command();
        TerminalCommand badCmd = ((WalletService.WithdrawQueuedResult.Ok) bad).command();

        assertThat(walletService.pendingWithdrawalAmount(user.getId())).isEqualTo(600L);

        txTemplate.executeWithoutResult(s ->
                handler.onSuccess(doneCmd, UUID.randomUUID().toString()));
        assertThat(walletService.pendingWithdrawalAmount(user.getId())).isEqualTo(400L);

        txTemplate.executeWithoutResult(s -> handler.onStall(badCmd, "no terminal"));
        assertThat(walletService.pendingWithdrawalAmount(user.getId())).isEqualTo(100L);
    }

    private User newUser() {
        return userRepo.save(User.builder().username("u-" + UUID.randomUUID().toString().substring(0, 8))
                .email("wallet-cb-" + UUID.randomUUID() + "@example.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .verified(true)
                .balanceLindens(0L)
                .reservedLindens(0L)
                .penaltyBalanceOwed(0L)
                .createdAt(OffsetDateTime.now(clock))
                .build());
    }
}
