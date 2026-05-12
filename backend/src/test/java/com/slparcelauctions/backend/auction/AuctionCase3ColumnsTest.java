package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroup;
import com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroupRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Round-trip persistence test for the two case-3 columns on {@code auctions}:
 * {@code realty_group_sl_group_id} and {@code agent_commission_rate}. Verifies
 * the JPA mapping survives a write/clear/reload cycle, both populated and NULL.
 *
 * <p>Spec: §3.2, §3.3, plan Task 5.
 *
 * <p>Test cleanup uses the {@code auctioncase3-%@test.local} email pattern to
 * scope test-row deletion to this class.
 */
@SpringBootTest
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
class AuctionCase3ColumnsTest {

    @Autowired UserRepository userRepository;
    @Autowired RealtyGroupRepository realtyGroupRepository;
    @Autowired RealtyGroupSlGroupRepository slGroupRepository;
    @Autowired AuctionRepository auctionRepository;
    @Autowired DataSource dataSource;

    @AfterEach
    void cleanup() throws Exception {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var stmt = conn.createStatement()) {
                stmt.execute("""
                    DELETE FROM auction_parcel_snapshots
                     WHERE auction_id IN (
                       SELECT id FROM auctions WHERE seller_id IN (
                         SELECT id FROM users WHERE email LIKE 'auctioncase3-%@test.local'))
                    """);
                stmt.execute("""
                    DELETE FROM auctions
                     WHERE seller_id IN (SELECT id FROM users WHERE email LIKE 'auctioncase3-%@test.local')
                    """);
                stmt.execute("""
                    DELETE FROM realty_group_sl_groups
                     WHERE realty_group_id IN (
                       SELECT id FROM realty_groups WHERE leader_id IN
                         (SELECT id FROM users WHERE email LIKE 'auctioncase3-%@test.local'))
                    """);
                stmt.execute("""
                    DELETE FROM realty_groups
                     WHERE leader_id IN (SELECT id FROM users WHERE email LIKE 'auctioncase3-%@test.local')
                    """);
                stmt.execute("DELETE FROM users WHERE email LIKE 'auctioncase3-%@test.local'");
            }
        }
    }

    @Test
    void case3ColumnsRoundtripWhenPopulated() {
        User seller = userRepository.save(buildUser("seller"));
        RealtyGroup group = realtyGroupRepository.save(RealtyGroup.builder()
            .name("Case3 Group " + suffix())
            .slug("auctioncase3-" + suffix())
            .leaderId(seller.getId())
            .build());

        RealtyGroupSlGroup slGroup = slGroupRepository.save(RealtyGroupSlGroup.builder()
            .realtyGroupId(group.getId())
            .slGroupUuid(UUID.randomUUID())
            .slGroupName("SL Group " + suffix())
            .verified(true)
            .verifiedAt(OffsetDateTime.now())
            .build());

        Auction saved = auctionRepository.save(buildDraftAuction(seller));
        saved.setRealtyGroupSlGroupId(slGroup.getId());
        saved.setAgentCommissionRate(new BigDecimal("0.0750"));
        saved = auctionRepository.save(saved);

        Long auctionId = saved.getId();

        Auction reloaded = auctionRepository.findById(auctionId).orElseThrow();
        assertThat(reloaded.getRealtyGroupSlGroupId()).isEqualTo(slGroup.getId());
        assertThat(reloaded.getAgentCommissionRate())
            .isNotNull()
            .isEqualByComparingTo(new BigDecimal("0.0750"));
    }

    @Test
    void case3ColumnsAreNullableForIndividualListings() {
        User seller = userRepository.save(buildUser("solo"));

        Auction saved = auctionRepository.save(buildDraftAuction(seller));

        Auction reloaded = auctionRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getRealtyGroupSlGroupId()).isNull();
        assertThat(reloaded.getAgentCommissionRate()).isNull();
    }

    // ─────────────────────── helpers ───────────────────────

    private static User buildUser(String tag) {
        return User.builder()
            .username("auctioncase3-" + tag + "-" + suffix())
            .email("auctioncase3-" + tag + "-" + UUID.randomUUID() + "@test.local")
            .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
            .displayName(tag)
            .verified(true)
            .slAvatarUuid(UUID.randomUUID())
            .build();
    }

    private static Auction buildDraftAuction(User seller) {
        UUID parcelUuid = UUID.randomUUID();
        Auction auction = Auction.builder()
            .seller(seller)
            .slParcelUuid(parcelUuid)
            .status(AuctionStatus.DRAFT)
            .title("Case3 Auction " + suffix())
            .startingBid(1_000L)
            .durationHours(24)
            .snipeProtect(false)
            .endsAt(OffsetDateTime.now().plusDays(1))
            .listingFeePaid(false)
            .currentBid(0L)
            .bidCount(0)
            .consecutiveWorldApiFailures(0)
            .commissionRate(new BigDecimal("0.05"))
            .agentFeeRate(new BigDecimal("0.0000"))
            .build();
        auction.setParcelSnapshot(AuctionParcelSnapshot.builder()
            .slParcelUuid(parcelUuid)
            .ownerUuid(seller.getSlAvatarUuid())
            .ownerType("agent")
            .parcelName("Test Parcel")
            .regionName("Test Region")
            .regionMaturityRating("GENERAL")
            .areaSqm(1024)
            .positionX(128.0).positionY(64.0).positionZ(22.0)
            .build());
        return auction;
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
