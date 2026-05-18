package com.slparcelauctions.backend.admin.users.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.admin.users.wallet.dto.AdminWalletAdjustRequest;
import com.slparcelauctions.backend.admin.users.wallet.dto.AdminWalletSnapshotDto;
import com.slparcelauctions.backend.admin.users.wallet.exception.AdminWalletStateException;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.wallet.UserLedgerEntry;
import com.slparcelauctions.backend.wallet.UserLedgerEntryType;
import com.slparcelauctions.backend.wallet.UserLedgerRepository;

/**
 * Service-level integration tests for {@link AdminWalletService#adjust}. The
 * debit case is a regression guard for the user-ledger signed-adjustment bug:
 * V3 created {@code user_ledger} with a blanket {@code CHECK (amount > 0)}, but
 * {@code adjust} writes the signed request amount under a single
 * {@code ADJUSTMENT} type (same shape the group wallet uses for
 * {@code ADMIN_ADJUSTMENT}). Before the V38 migration a negative adjustment
 * hit {@code user_ledger_amount_check} and 500ed. V30 fixed the identical bug
 * for {@code realty_group_ledger}; V38 mirrors it for {@code user_ledger}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "auth.cleanup.enabled=false",
    "slpa.auction-end.enabled=false",
    "slpa.ownership-monitor.enabled=false",
    "slpa.escrow.ownership-monitor-job.enabled=false",
    "slpa.escrow.timeout-job.enabled=false",
    "slpa.escrow.command-dispatcher-job.enabled=false",
    "slpa.review.scheduler.enabled=false",
    "slpa.notifications.cleanup.enabled=false",
    "slpa.notifications.sl-im.cleanup.enabled=false",
    "slpa.realty.invitation-expiry.enabled=false"
})
@Transactional
class AdminWalletServiceTest {

    @Autowired AdminWalletService service;
    @Autowired UserRepository users;
    @Autowired UserLedgerRepository ledger;

    @Test
    void adjust_debitsUserWallet_whenAmountIsNegativeAndBalanceAdequate() {
        User admin = seedAdmin();
        User u = seedUserWithBalance(10_000L);

        AdminWalletSnapshotDto out = service.adjust(
                u.getId(), new AdminWalletAdjustRequest(-100L, "Test debit", false), admin.getId());

        assertThat(out.balanceLindens()).isEqualTo(9_900L);
        assertThat(users.findById(u.getId()).orElseThrow().getBalanceLindens()).isEqualTo(9_900L);
        UserLedgerEntry tail = ledger
                .findByUserIdOrderByCreatedAtDesc(u.getId(), PageRequest.of(0, 1))
                .getContent().get(0);
        assertThat(tail.getEntryType()).isEqualTo(UserLedgerEntryType.ADJUSTMENT);
        assertThat(tail.getAmount()).isEqualTo(-100L);
        assertThat(tail.getBalanceAfter()).isEqualTo(9_900L);
    }

    @Test
    void adjust_creditsUserWallet_whenAmountIsPositive() {
        User admin = seedAdmin();
        User u = seedUserWithBalance(500L);

        AdminWalletSnapshotDto out = service.adjust(
                u.getId(), new AdminWalletAdjustRequest(2_500L, "Comp credit", false), admin.getId());

        assertThat(out.balanceLindens()).isEqualTo(3_000L);
        UserLedgerEntry tail = ledger
                .findByUserIdOrderByCreatedAtDesc(u.getId(), PageRequest.of(0, 1))
                .getContent().get(0);
        assertThat(tail.getEntryType()).isEqualTo(UserLedgerEntryType.ADJUSTMENT);
        assertThat(tail.getAmount()).isEqualTo(2_500L);
        assertThat(tail.getBalanceAfter()).isEqualTo(3_000L);
    }

    @Test
    void adjust_throws_whenDebitWouldBreachReservationFloorWithoutOverride() {
        User admin = seedAdmin();
        User u = seedUserWithBalance(100L);

        assertThatThrownBy(() -> service.adjust(
                u.getId(), new AdminWalletAdjustRequest(-1_000L, "Too much", false), admin.getId()))
                .isInstanceOf(AdminWalletStateException.class)
                .hasMessageContaining("reservation floor");
    }

    @Test
    void adjust_throws_whenAmountIsZero() {
        User admin = seedAdmin();
        User u = seedUserWithBalance(100L);

        assertThatThrownBy(() -> service.adjust(
                u.getId(), new AdminWalletAdjustRequest(0L, "Nope", false), admin.getId()))
                .isInstanceOf(AdminWalletStateException.class);
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private User seedAdmin() {
        return users.save(User.builder()
            .username("adminadj-" + uniq())
            .email("adminadj-" + UUID.randomUUID() + "@test.local")
            .passwordHash("x")
            .role(Role.ADMIN)
            .build());
    }

    private User seedUserWithBalance(long balance) {
        return users.save(User.builder()
            .username("walletadj-" + uniq())
            .email("walletadj-" + UUID.randomUUID() + "@test.local")
            .passwordHash("x")
            .balanceLindens(balance)
            .build());
    }

    private static String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
