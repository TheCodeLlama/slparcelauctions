package com.slparcelauctions.backend.realty.wallet.admin;

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

import com.slparcelauctions.backend.admin.audit.AdminAction;
import com.slparcelauctions.backend.admin.audit.AdminActionRepository;
import com.slparcelauctions.backend.admin.audit.AdminActionTargetType;
import com.slparcelauctions.backend.admin.audit.AdminActionType;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.wallet.RealtyGroupLedgerEntry;
import com.slparcelauctions.backend.realty.wallet.RealtyGroupLedgerEntryType;
import com.slparcelauctions.backend.realty.wallet.RealtyGroupLedgerRepository;
import com.slparcelauctions.backend.realty.wallet.dto.GroupWalletDto;
import com.slparcelauctions.backend.realty.wallet.exception.AdminAdjustAmountOutOfRangeException;
import com.slparcelauctions.backend.realty.wallet.exception.InsufficientGroupBalanceException;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Sub-project G section 7.2 -- service-level integration tests for
 * {@link AdminRealtyGroupWalletService#adjust}. Covers credit, debit, validation,
 * audit row, broadcast, and ceiling/blank-reason/overdraw guard paths.
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
class AdminRealtyGroupWalletServiceTest {

    @Autowired AdminRealtyGroupWalletService service;
    @Autowired RealtyGroupRepository groups;
    @Autowired RealtyGroupLedgerRepository ledger;
    @Autowired AdminActionRepository adminActions;
    @Autowired UserRepository users;

    @Test
    void adjust_credits_walletBalanceAndWritesLedgerAuditRow_whenAmountIsPositive() {
        User admin = seedAdmin("a");
        Long leaderId = seedUser("leader-a").getId();
        RealtyGroup g = groups.save(RealtyGroup.builder()
                .name("adj-credit-" + uniq()).slug("adj-credit-" + uniq())
                .leaderId(leaderId).balanceLindens(0L).build());

        GroupWalletDto out = service.adjust(admin.getId(), g.getPublicId(), 2500L,
                "Compensating bad payout");

        assertThat(out.balance()).isEqualTo(2500L);
        assertThat(groups.findById(g.getId()).orElseThrow().getBalanceLindens()).isEqualTo(2500L);
        RealtyGroupLedgerEntry tail = ledger.findRecentForGroup(g.getId(), PageRequest.of(0, 1)).get(0);
        assertThat(tail.getEntryType()).isEqualTo(RealtyGroupLedgerEntryType.ADMIN_ADJUSTMENT);
        assertThat(tail.getAmount()).isEqualTo(2500L);
        assertThat(tail.getBalanceAfter()).isEqualTo(2500L);
        assertThat(tail.getDescription()).isEqualTo("Compensating bad payout");
        AdminAction action = adminActions.findAll().stream()
                .filter(a -> a.getActionType() == AdminActionType.REALTY_GROUP_WALLET_ADMIN_ADJUSTMENT)
                .filter(a -> a.getTargetId().equals(g.getId()))
                .findFirst().orElseThrow();
        assertThat(action.getTargetType()).isEqualTo(AdminActionTargetType.REALTY_GROUP);
        assertThat(action.getDetails()).containsEntry("reason", "Compensating bad payout");
        assertThat(((Number) action.getDetails().get("amount")).longValue()).isEqualTo(2500L);
    }

    @Test
    void adjust_debitsWallet_whenAmountIsNegativeAndBalanceAdequate() {
        User admin = seedAdmin("b");
        Long leaderId = seedUser("leader-b").getId();
        RealtyGroup g = groups.save(RealtyGroup.builder()
                .name("adj-debit-" + uniq()).slug("adj-debit-" + uniq())
                .leaderId(leaderId).balanceLindens(10_000L).build());

        GroupWalletDto out = service.adjust(admin.getId(), g.getPublicId(), -3500L,
                "Recovering overpaid commission");

        assertThat(out.balance()).isEqualTo(6_500L);
        assertThat(groups.findById(g.getId()).orElseThrow().getBalanceLindens()).isEqualTo(6_500L);
        RealtyGroupLedgerEntry tail = ledger.findRecentForGroup(g.getId(), PageRequest.of(0, 1)).get(0);
        assertThat(tail.getAmount()).isEqualTo(-3500L);
        assertThat(tail.getBalanceAfter()).isEqualTo(6_500L);
    }

    @Test
    void adjust_throws_whenDebitWouldOverdraw() {
        User admin = seedAdmin("c");
        Long leaderId = seedUser("leader-c").getId();
        RealtyGroup g = groups.save(RealtyGroup.builder()
                .name("adj-overdraw-" + uniq()).slug("adj-overdraw-" + uniq())
                .leaderId(leaderId).balanceLindens(500L).build());

        assertThatThrownBy(() -> service.adjust(admin.getId(), g.getPublicId(), -1000L, "Bad call"))
                .isInstanceOf(InsufficientGroupBalanceException.class);
    }

    @Test
    void adjust_throws_whenAmountIsZero() {
        User admin = seedAdmin("d");
        Long leaderId = seedUser("leader-d").getId();
        RealtyGroup g = groups.save(RealtyGroup.builder()
                .name("adj-zero-" + uniq()).slug("adj-zero-" + uniq())
                .leaderId(leaderId).balanceLindens(100L).build());

        assertThatThrownBy(() -> service.adjust(admin.getId(), g.getPublicId(), 0L, "Nope"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void adjust_throws_whenAmountExceedsCeiling() {
        User admin = seedAdmin("e");
        Long leaderId = seedUser("leader-e").getId();
        RealtyGroup g = groups.save(RealtyGroup.builder()
                .name("adj-huge-" + uniq()).slug("adj-huge-" + uniq())
                .leaderId(leaderId).balanceLindens(0L).build());

        assertThatThrownBy(() -> service.adjust(admin.getId(), g.getPublicId(), 10_000_001L, "Too big"))
                .isInstanceOf(AdminAdjustAmountOutOfRangeException.class);
    }

    @Test
    void adjust_throws_whenReasonIsBlank() {
        User admin = seedAdmin("f");
        Long leaderId = seedUser("leader-f").getId();
        RealtyGroup g = groups.save(RealtyGroup.builder()
                .name("adj-blank-" + uniq()).slug("adj-blank-" + uniq())
                .leaderId(leaderId).balanceLindens(0L).build());

        assertThatThrownBy(() -> service.adjust(admin.getId(), g.getPublicId(), 100L, "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void adjust_throws_whenReasonExceeds500Chars() {
        User admin = seedAdmin("g");
        Long leaderId = seedUser("leader-g").getId();
        RealtyGroup g = groups.save(RealtyGroup.builder()
                .name("adj-long-" + uniq()).slug("adj-long-" + uniq())
                .leaderId(leaderId).balanceLindens(0L).build());

        String tooLong = "x".repeat(501);
        assertThatThrownBy(() -> service.adjust(admin.getId(), g.getPublicId(), 100L, tooLong))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private User seedAdmin(String suffix) {
        return users.save(User.builder()
            .username("adminadj-" + suffix + "-" + uniq())
            .email("adminadj-" + suffix + "-" + UUID.randomUUID() + "@test.local")
            .passwordHash("x")
            .role(Role.ADMIN)
            .build());
    }

    private User seedUser(String label) {
        return users.save(User.builder()
            .username("u-" + label + "-" + uniq())
            .email("u-" + label + "-" + UUID.randomUUID() + "@test.local")
            .passwordHash("x")
            .build());
    }

    private static String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
