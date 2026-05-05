package com.slparcelauctions.backend.wallet.me;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.wallet.LedgerRow;
import com.slparcelauctions.backend.wallet.UserLedgerEntry;
import com.slparcelauctions.backend.wallet.UserLedgerEntryType;
import com.slparcelauctions.backend.wallet.UserLedgerRepository;
import com.slparcelauctions.backend.wallet.WithdrawalStatus;

/**
 * Integration coverage for the collapsed-ledger SQL: ensures
 * {@code WITHDRAW_COMPLETED} / {@code WITHDRAW_REVERSED} rows are filtered
 * out of results, and that surviving {@code WITHDRAW_QUEUED} rows carry
 * the correct {@link WithdrawalStatus} computed from the EXISTS subqueries.
 */
@SpringBootTest
@ActiveProfiles("dev")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class LedgerCollapsedRepositoryTest {

    @Autowired UserLedgerRepository ledgerRepo;
    @Autowired UserRepository userRepo;
    @Autowired TransactionTemplate txTemplate;

    Clock clock = Clock.fixed(Instant.parse("2026-05-01T20:00:00Z"), ZoneOffset.UTC);

    @Test
    void collapsedQuery_excludesTerminalRowsAndStampsStatus() {
        User user = newUser();

        // 1. DEPOSIT (non-withdraw, status should be null).
        appendDeposit(user, 1000L);
        // 2. WITHDRAW_QUEUED with no terminal sibling — PENDING.
        UserLedgerEntry pending = appendWithdrawQueued(user, 100L);
        // 3. WITHDRAW_QUEUED + COMPLETED — collapsed to single COMPLETED row.
        UserLedgerEntry doneQueued = appendWithdrawQueued(user, 200L);
        appendWithdrawTerminal(user, doneQueued.getId(),
                UserLedgerEntryType.WITHDRAW_COMPLETED);
        // 4. WITHDRAW_QUEUED + REVERSED — collapsed to single REVERSED row.
        UserLedgerEntry badQueued = appendWithdrawQueued(user, 300L);
        appendWithdrawTerminal(user, badQueued.getId(),
                UserLedgerEntryType.WITHDRAW_REVERSED);

        Page<LedgerRow> page = ledgerRepo.findCollapsedForUser(user.getId(), null,
                PageRequest.of(0, 25, Sort.by(Sort.Direction.DESC, "createdAt")));

        // 4 underlying queued/deposit rows visible; 2 terminal rows hidden.
        assertThat(page.getTotalElements()).isEqualTo(4L);
        assertThat(page.getContent()).hasSize(4);

        // Content: deposit (status null) + 3 queued rows with statuses.
        assertThat(page.getContent())
                .extracting(LedgerRow::withdrawalStatus)
                .containsExactlyInAnyOrder(
                        null,
                        WithdrawalStatus.PENDING,
                        WithdrawalStatus.COMPLETED,
                        WithdrawalStatus.REVERSED);

        // No terminal rows leak through.
        assertThat(page.getContent())
                .extracting(r -> r.entry().getEntryType())
                .doesNotContain(
                        UserLedgerEntryType.WITHDRAW_COMPLETED,
                        UserLedgerEntryType.WITHDRAW_REVERSED);

        // The pending row's status is PENDING (only verifying by the
        // entry id since order is timestamp-driven).
        LedgerRow pendingRow = page.getContent().stream()
                .filter(r -> r.entry().getId().equals(pending.getId()))
                .findFirst().orElseThrow();
        assertThat(pendingRow.withdrawalStatus()).isEqualTo(WithdrawalStatus.PENDING);
    }

    @Test
    void streamCollapsed_returnsSameShapeAsPaginated() {
        User user = newUser();
        appendDeposit(user, 500L);
        UserLedgerEntry queued = appendWithdrawQueued(user, 50L);
        appendWithdrawTerminal(user, queued.getId(),
                UserLedgerEntryType.WITHDRAW_COMPLETED);

        // Streaming requires an active read-only transaction so the JDBC
        // cursor stays open across the consume.
        var rows = txTemplate.execute(s -> {
            try (var stream = ledgerRepo.streamCollapsedForUser(user.getId(), null)) {
                return stream.toList();
            }
        });
        assertThat(rows).hasSize(2);
        assertThat(rows)
                .extracting(LedgerRow::withdrawalStatus)
                .containsExactlyInAnyOrder(null, WithdrawalStatus.COMPLETED);
    }

    private User newUser() {
        return userRepo.save(User.builder().username("u-" + UUID.randomUUID().toString().substring(0, 8))
                .email("ledger-collapsed-" + UUID.randomUUID() + "@example.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .verified(true)
                .balanceLindens(0L)
                .reservedLindens(0L)
                .penaltyBalanceOwed(0L)
                .createdAt(OffsetDateTime.now(clock))
                .build());
    }

    private UserLedgerEntry appendDeposit(User user, long amount) {
        return ledgerRepo.save(UserLedgerEntry.builder()
                .userId(user.getId())
                .entryType(UserLedgerEntryType.DEPOSIT)
                .amount(amount)
                .balanceAfter(amount)
                .reservedAfter(0L)
                .slTransactionId(UUID.randomUUID().toString())
                .createdAt(OffsetDateTime.now(clock).plusSeconds(0))
                .build());
    }

    private UserLedgerEntry appendWithdrawQueued(User user, long amount) {
        return ledgerRepo.save(UserLedgerEntry.builder()
                .userId(user.getId())
                .entryType(UserLedgerEntryType.WITHDRAW_QUEUED)
                .amount(amount)
                .balanceAfter(0L)
                .reservedAfter(0L)
                .idempotencyKey(UUID.randomUUID().toString())
                .createdAt(OffsetDateTime.now(clock))
                .build());
    }

    private UserLedgerEntry appendWithdrawTerminal(
            User user, Long queuedId, UserLedgerEntryType type) {
        UserLedgerEntry queuedRow = ledgerRepo.findById(queuedId).orElseThrow();
        return ledgerRepo.save(UserLedgerEntry.builder()
                .userId(user.getId())
                .entryType(type)
                .amount(queuedRow.getAmount())
                .balanceAfter(0L)
                .reservedAfter(0L)
                .refType("USER_LEDGER")
                .refId(queuedId)
                .createdAt(OffsetDateTime.now(clock).plusSeconds(30))
                .build());
    }
}
