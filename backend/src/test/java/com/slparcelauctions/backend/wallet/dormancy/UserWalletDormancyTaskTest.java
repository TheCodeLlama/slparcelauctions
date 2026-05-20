package com.slparcelauctions.backend.wallet.dormancy;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.slparcelauctions.backend.escrow.command.TerminalCommand;
import com.slparcelauctions.backend.escrow.command.TerminalCommandAction;
import com.slparcelauctions.backend.escrow.command.TerminalCommandPurpose;
import com.slparcelauctions.backend.escrow.command.TerminalCommandRepository;
import com.slparcelauctions.backend.escrow.command.TerminalCommandStatus;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.wallet.UserLedgerEntry;
import com.slparcelauctions.backend.wallet.UserLedgerEntryType;
import com.slparcelauctions.backend.wallet.UserLedgerRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserWalletDormancyTaskTest {

    private final UserRepository userRepo = mock(UserRepository.class);
    private final UserLedgerRepository ledgerRepo = mock(UserLedgerRepository.class);
    private final TerminalCommandRepository cmdRepo = mock(TerminalCommandRepository.class);
    private final NotificationPublisher notif = mock(NotificationPublisher.class);

    private final UserWalletDormancyTask task = new UserWalletDormancyTask(
        userRepo, ledgerRepo, cmdRepo, notif);

    private static final OffsetDateTime NOW =
        OffsetDateTime.of(2026, 5, 19, 4, 0, 0, 0, ZoneOffset.UTC);

    // -------------------------------------------------------------------------
    // flag -- phase 1
    // -------------------------------------------------------------------------

    @Test
    void phase1FlagsUser() throws Exception {
        User u = user(10L, 1000L, 0L);

        task.flag(u, NOW);

        assertThat(u.getWalletDormancyPhase()).isEqualTo(1);
        assertThat(u.getWalletDormancyStartedAt()).isEqualTo(NOW);
        verify(userRepo).save(u);
        verify(notif).userWalletDormancyFlagged(10L, 1, 1000L);
    }

    // -------------------------------------------------------------------------
    // escalateOrAutoReturn -- phases 2 and 3
    // -------------------------------------------------------------------------

    @Test
    void phase1To2_escalatesAndSendsIm() throws Exception {
        User u = user(10L, 500L, 0L);
        u.setWalletDormancyPhase(1);
        u.setWalletDormancyStartedAt(NOW.minusDays(7));

        task.escalateOrAutoReturn(u, NOW);

        assertThat(u.getWalletDormancyPhase()).isEqualTo(2);
        verify(userRepo).save(u);
        verify(notif).userWalletDormancyFlagged(10L, 2, 500L);
    }

    @Test
    void phase2To3_escalatesAndSendsIm() throws Exception {
        User u = user(20L, 750L, 0L);
        u.setWalletDormancyPhase(2);
        u.setWalletDormancyStartedAt(NOW.minusDays(14));

        task.escalateOrAutoReturn(u, NOW);

        assertThat(u.getWalletDormancyPhase()).isEqualTo(3);
        verify(userRepo).save(u);
        verify(notif).userWalletDormancyFlagged(20L, 3, 750L);
    }

    @Test
    void phase3To4_escalatesAndSendsIm() throws Exception {
        User u = user(30L, 200L, 0L);
        u.setWalletDormancyPhase(3);
        u.setWalletDormancyStartedAt(NOW.minusDays(21));

        task.escalateOrAutoReturn(u, NOW);

        assertThat(u.getWalletDormancyPhase()).isEqualTo(4);
        verify(userRepo).save(u);
        verify(notif).userWalletDormancyFlagged(30L, 4, 200L);
    }

    // -------------------------------------------------------------------------
    // escalateOrAutoReturn -- phase 4 -> auto-return (COMPLETED / phase 99)
    // -------------------------------------------------------------------------

    @Test
    void phase4QueuesAutoReturnAndStampsPhase99() throws Exception {
        UUID avatarUuid = UUID.randomUUID();
        User u = userWithAvatar(42L, 500L, 0L, avatarUuid);
        u.setWalletDormancyPhase(4);
        u.setWalletDormancyStartedAt(NOW.minusDays(28));

        when(cmdRepo.save(any(TerminalCommand.class))).thenAnswer(inv -> {
            TerminalCommand cmd = inv.getArgument(0);
            setId(cmd, 999L);
            return cmd;
        });
        when(ledgerRepo.save(any(UserLedgerEntry.class))).thenAnswer(inv -> {
            UserLedgerEntry e = inv.getArgument(0);
            setId(e, 77L);
            return e;
        });

        task.escalateOrAutoReturn(u, NOW);

        // Balance debited by the auto-return amount (all 500 because reserved=0).
        assertThat(u.getBalanceLindens()).isEqualTo(0L);
        // Phase stamped 99
        assertThat(u.getWalletDormancyPhase()).isEqualTo(99);

        // TerminalCommand assertions
        ArgumentCaptor<TerminalCommand> cmdCap = ArgumentCaptor.forClass(TerminalCommand.class);
        verify(cmdRepo).save(cmdCap.capture());
        TerminalCommand cmd = cmdCap.getValue();
        assertThat(cmd.getAction()).isEqualTo(TerminalCommandAction.WITHDRAW);
        assertThat(cmd.getPurpose())
            .isEqualTo(TerminalCommandPurpose.USER_WALLET_DORMANCY_AUTO_RETURN);
        assertThat(cmd.getAmount()).isEqualTo(500L);
        assertThat(cmd.getRecipientUuid()).isEqualTo(avatarUuid.toString());
        assertThat(cmd.getStatus()).isEqualTo(TerminalCommandStatus.QUEUED);
        assertThat(cmd.getIdempotencyKey()).startsWith("user-dormancy-42-");
        assertThat(cmd.getAttemptCount()).isEqualTo(0);
        assertThat(cmd.getRequiresManualReview()).isFalse();

        // Ledger row assertions
        ArgumentCaptor<UserLedgerEntry> ledgerCap = ArgumentCaptor.forClass(UserLedgerEntry.class);
        verify(ledgerRepo).save(ledgerCap.capture());
        UserLedgerEntry ledger = ledgerCap.getValue();
        assertThat(ledger.getEntryType()).isEqualTo(UserLedgerEntryType.DORMANCY_AUTO_RETURN);
        assertThat(ledger.getUserId()).isEqualTo(42L);
        assertThat(ledger.getAmount()).isEqualTo(500L);
        assertThat(ledger.getBalanceAfter()).isEqualTo(0L);
        assertThat(ledger.getReservedAfter()).isEqualTo(0L);
        assertThat(ledger.getRefType()).isEqualTo("TERMINAL_COMMAND");
        assertThat(ledger.getDescription()).contains("auto-return");

        verify(notif).userWalletDormancyAutoReturned(42L, 500L);
    }

    @Test
    void phase4_partialReservedBalance_onlyAvailableIsWithdrawn() throws Exception {
        UUID avatarUuid = UUID.randomUUID();
        // balance=1000, reserved=400 -> available=600 is what auto-returns.
        User u = userWithAvatar(50L, 1000L, 400L, avatarUuid);
        u.setWalletDormancyPhase(4);
        u.setWalletDormancyStartedAt(NOW.minusDays(28));

        when(cmdRepo.save(any(TerminalCommand.class))).thenAnswer(inv -> {
            TerminalCommand cmd = inv.getArgument(0);
            setId(cmd, 1001L);
            return cmd;
        });
        when(ledgerRepo.save(any(UserLedgerEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        task.escalateOrAutoReturn(u, NOW);

        // Balance debited by 600 (the available amount); reserved unchanged.
        assertThat(u.getBalanceLindens()).isEqualTo(400L);
        assertThat(u.getReservedLindens()).isEqualTo(400L);
        assertThat(u.getWalletDormancyPhase()).isEqualTo(99);

        ArgumentCaptor<TerminalCommand> cmdCap = ArgumentCaptor.forClass(TerminalCommand.class);
        verify(cmdRepo).save(cmdCap.capture());
        assertThat(cmdCap.getValue().getAmount()).isEqualTo(600L);

        ArgumentCaptor<UserLedgerEntry> ledgerCap = ArgumentCaptor.forClass(UserLedgerEntry.class);
        verify(ledgerRepo).save(ledgerCap.capture());
        UserLedgerEntry ledger = ledgerCap.getValue();
        assertThat(ledger.getAmount()).isEqualTo(600L);
        assertThat(ledger.getBalanceAfter()).isEqualTo(400L);
        assertThat(ledger.getReservedAfter()).isEqualTo(400L);

        verify(notif).userWalletDormancyAutoReturned(50L, 600L);
    }

    @Test
    void phase4_allReserved_stampsPhase99WithoutWithdraw() throws Exception {
        // balance == reserved -> available is 0; no withdraw, no ledger row.
        User u = userWithAvatar(60L, 300L, 300L, UUID.randomUUID());
        u.setWalletDormancyPhase(4);
        u.setWalletDormancyStartedAt(NOW.minusDays(28));

        task.escalateOrAutoReturn(u, NOW);

        assertThat(u.getBalanceLindens()).isEqualTo(300L);
        assertThat(u.getReservedLindens()).isEqualTo(300L);
        assertThat(u.getWalletDormancyPhase()).isEqualTo(99);

        verify(cmdRepo, never()).save(any(TerminalCommand.class));
        verify(ledgerRepo, never()).save(any(UserLedgerEntry.class));
        verify(notif).userWalletDormancyAutoReturned(60L, 0L);
    }

    @Test
    void phase4_noSlAvatarUuid_stampsPhase99WithoutWithdraw() throws Exception {
        // Defensive path -- a user with a positive balance should always have a
        // verified avatar but if the column is somehow null we don't queue a
        // command the bot can't deliver.
        User u = user(70L, 100L, 0L); // user() does NOT set slAvatarUuid
        u.setWalletDormancyPhase(4);
        u.setWalletDormancyStartedAt(NOW.minusDays(28));

        task.escalateOrAutoReturn(u, NOW);

        assertThat(u.getWalletDormancyPhase()).isEqualTo(99);
        verify(cmdRepo, never()).save(any(TerminalCommand.class));
        verify(ledgerRepo, never()).save(any(UserLedgerEntry.class));
        verify(notif).userWalletDormancyAutoReturned(70L, 0L);
    }

    // -------------------------------------------------------------------------
    // clearForUser -- login clears dormancy state
    // -------------------------------------------------------------------------

    @Test
    void loginClearsDormancyState() throws Exception {
        User u = user(55L, 300L, 0L);
        u.setWalletDormancyPhase(2);
        u.setWalletDormancyStartedAt(NOW.minusDays(14));
        when(userRepo.findById(55L)).thenReturn(Optional.of(u));

        task.clearForUser(55L);

        assertThat(u.getWalletDormancyPhase()).isNull();
        assertThat(u.getWalletDormancyStartedAt()).isNull();
        verify(userRepo).save(u);
    }

    @Test
    void clearForUser_doesNothingForNonDormantUser() throws Exception {
        User u = user(60L, 100L, 0L);
        // phase is null -- not dormant
        when(userRepo.findById(60L)).thenReturn(Optional.of(u));

        task.clearForUser(60L);

        verify(userRepo, never()).save(any());
    }

    @Test
    void clearForUser_doesNotClearCompletedPhase99() throws Exception {
        User u = user(70L, 0L, 0L);
        u.setWalletDormancyPhase(99);
        when(userRepo.findById(70L)).thenReturn(Optional.of(u));

        task.clearForUser(70L);

        // Phase 99 (COMPLETED) must not be cleared
        assertThat(u.getWalletDormancyPhase()).isEqualTo(99);
        verify(userRepo, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // End-to-end: phase 1 -> 2 -> 3 -> 4 -> 99 with the clock advanced
    // -------------------------------------------------------------------------

    @Test
    void fullStateMachineMarchToAutoReturn() throws Exception {
        UUID avatarUuid = UUID.randomUUID();
        User u = userWithAvatar(80L, 250L, 0L, avatarUuid);

        // Phase 1 -- newly dormant
        task.flag(u, NOW);
        assertThat(u.getWalletDormancyPhase()).isEqualTo(1);

        // Phase 1 -> 2
        task.escalateOrAutoReturn(u, NOW.plusDays(7));
        assertThat(u.getWalletDormancyPhase()).isEqualTo(2);

        // Phase 2 -> 3
        task.escalateOrAutoReturn(u, NOW.plusDays(14));
        assertThat(u.getWalletDormancyPhase()).isEqualTo(3);

        // Phase 3 -> 4
        task.escalateOrAutoReturn(u, NOW.plusDays(21));
        assertThat(u.getWalletDormancyPhase()).isEqualTo(4);

        when(cmdRepo.save(any(TerminalCommand.class))).thenAnswer(inv -> {
            TerminalCommand cmd = inv.getArgument(0);
            setId(cmd, 2024L);
            return cmd;
        });
        when(ledgerRepo.save(any(UserLedgerEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        // Phase 4 -> 99 with the WITHDRAW queued + ledger row appended.
        task.escalateOrAutoReturn(u, NOW.plusDays(28));
        assertThat(u.getWalletDormancyPhase()).isEqualTo(99);
        assertThat(u.getBalanceLindens()).isEqualTo(0L);

        ArgumentCaptor<TerminalCommand> cmdCap = ArgumentCaptor.forClass(TerminalCommand.class);
        verify(cmdRepo).save(cmdCap.capture());
        TerminalCommand cmd = cmdCap.getValue();
        assertThat(cmd.getRecipientUuid()).isEqualTo(avatarUuid.toString());
        assertThat(cmd.getAmount()).isEqualTo(250L);
        assertThat(cmd.getPurpose())
            .isEqualTo(TerminalCommandPurpose.USER_WALLET_DORMANCY_AUTO_RETURN);
        assertThat(cmd.getIdempotencyKey())
            .isEqualTo("user-dormancy-80-" + NOW.toEpochSecond());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private User user(Long id, long balance, long reserved) throws Exception {
        User u = User.builder()
            .username("u-" + id)
            .passwordHash("x")
            .balanceLindens(balance)
            .reservedLindens(reserved)
            .build();
        setId(u, id);
        setPublicId(u, UUID.randomUUID());
        return u;
    }

    private User userWithAvatar(Long id, long balance, long reserved, UUID avatarUuid)
            throws Exception {
        User u = user(id, balance, reserved);
        u.setSlAvatarUuid(avatarUuid);
        return u;
    }

    private static void setId(Object entity, Long id) throws Exception {
        Field f = com.slparcelauctions.backend.common.BaseEntity.class.getDeclaredField("id");
        f.setAccessible(true);
        f.set(entity, id);
    }

    private static void setPublicId(Object entity, UUID publicId) throws Exception {
        Field f = com.slparcelauctions.backend.common.BaseEntity.class.getDeclaredField("publicId");
        f.setAccessible(true);
        f.set(entity, publicId);
    }
}
