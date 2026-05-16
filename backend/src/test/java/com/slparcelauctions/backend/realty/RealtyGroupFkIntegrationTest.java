package com.slparcelauctions.backend.realty;

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

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * FK behavior integration test: dissolving a group via the normal soft-delete path leaves
 * any auctions tied to the group untouched (no FK trigger fires â€” dissolve is an UPDATE,
 * not a DELETE), and a hard-delete of the group row (admin-only, not a normal path)
 * triggers the {@code ON DELETE SET NULL} on {@code auctions.realty_group_id}.
 *
 * <p>This validates the migration's FK declaration from spec Â§3.4 / V24:
 *
 * <pre>{@code
 *   ALTER TABLE auctions
 *     ADD CONSTRAINT fk_auctions_realty_group
 *     FOREIGN KEY (realty_group_id) REFERENCES realty_groups(id) ON DELETE SET NULL;
 * }</pre>
 *
 * <p>Cleanup uses the {@code rgfk-%@test.local} email pattern to scope realty-row deletion
 * to this test class (mirrors the {@code SlImMessageDaoTest} pattern).
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
class RealtyGroupFkIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired RealtyGroupRepository groupRepository;
    @Autowired RealtyGroupMemberRepository memberRepository;
    @Autowired AuctionRepository auctionRepository;
    @Autowired DataSource dataSource;

    private Long auctionIdForCleanup;
    private Long sellerIdForCleanup;

    @AfterEach
    void cleanup() throws Exception {
        // Auction deletes first (it FK-references the user via seller and may reference the
        // realty group; nulling the latter is the whole point of the test, but the test may
        // also leave the auction in place).
        if (auctionIdForCleanup != null) {
            auctionRepository.deleteById(auctionIdForCleanup);
            auctionIdForCleanup = null;
        }
        // Seller delete is deferred to the raw-SQL sweep below â€” Hibernate's batched delete
        // can fail noisily on the FK chain (realty_groups.leader_id), and the raw sweep
        // already handles ordering correctly.
        sellerIdForCleanup = null;

        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var stmt = conn.createStatement()) {
                stmt.execute("""
                    DELETE FROM notification
                     WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'rgfk-%@test.local')
                    """);
                stmt.execute("""
                    DELETE FROM sl_im_message
                     WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'rgfk-%@test.local')
                    """);
                // Null any auctions still pointing at our test groups (they were created via
                // the test's seller email pattern; defensive in case the test failed early).
                stmt.execute("""
                    UPDATE auctions SET realty_group_id = NULL
                     WHERE realty_group_id IN (
                       SELECT id FROM realty_groups WHERE leader_id IN
                         (SELECT id FROM users WHERE email LIKE 'rgfk-%@test.local'))
                    """);
                stmt.execute("""
                    DELETE FROM realty_group_members
                     WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'rgfk-%@test.local')
                    """);
                stmt.execute("""
                    DELETE FROM realty_groups
                     WHERE leader_id IN (SELECT id FROM users WHERE email LIKE 'rgfk-%@test.local')
                    """);
                stmt.execute("DELETE FROM users WHERE email LIKE 'rgfk-%@test.local'");
            }
        }
    }

    @Test
    void dissolve_isSoftDelete_doesNotTouchAuctionRealtyGroupId() {
        User leader = persistSeller("leader");
        RealtyGroup g = persistGroup(leader, "FK Dissolve " + suffix());
        Auction auction = persistAuction(leader, g.getId());

        // Dissolve via soft-delete (the production path).
        g.setDissolvedAt(OffsetDateTime.now());
        groupRepository.save(g);

        Auction reloaded = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloaded.getRealtyGroupId())
            .as("soft-delete (UPDATE) must not cascade through the FK â€” the auction column "
                + "retains the original group id")
            .isEqualTo(g.getId());
    }

    @Test
    void hardDeleteGroupRow_setsAuctionRealtyGroupIdToNull() throws Exception {
        User leader = persistSeller("leader");
        RealtyGroup g = persistGroup(leader, "FK HardDelete " + suffix());
        Auction auction = persistAuction(leader, g.getId());

        // Hard-delete: bypass the service entirely. The realty_group_members ON DELETE
        // CASCADE eats the leader's member row first; the FK on auctions is
        // ON DELETE SET NULL so the auction's realty_group_id should flip to NULL.
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM realty_groups WHERE id = " + g.getId());
            }
        }

        // Fresh read â€” the audited FK contract is "SET NULL" on hard delete.
        Auction reloaded = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloaded.getRealtyGroupId())
            .as("hard-delete on realty_groups must SET NULL on auctions.realty_group_id "
                + "per the FK constraint in V24")
            .isNull();

        // Group row is genuinely gone.
        assertThat(groupRepository.findById(g.getId())).isEmpty();
    }

    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private User persistSeller(String label) {
        User u = userRepository.save(User.builder()
            .username("rgfk-" + label + "-" + UUID.randomUUID().toString().substring(0, 8))
            .email("rgfk-" + label + "-" + UUID.randomUUID() + "@test.local")
            .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
            .displayName(label)
            .verified(true)
            .slAvatarUuid(UUID.randomUUID())
            .build());
        sellerIdForCleanup = u.getId();
        return u;
    }

    private RealtyGroup persistGroup(User leader, String name) {
        String slug = "rgfk-" + UUID.randomUUID().toString().substring(0, 8);
        RealtyGroup g = groupRepository.save(RealtyGroup.builder()
            .name(name).slug(slug).leaderId(leader.getId()).build());
        RealtyGroupMember leaderRow = RealtyGroupMember.builder()
            .groupId(g.getId()).userId(leader.getId()).joinedAt(OffsetDateTime.now()).build();
        leaderRow.setPermissionSet(java.util.EnumSet.noneOf(
            com.slparcelauctions.backend.realty.permission.RealtyGroupPermission.class));
        memberRepository.save(leaderRow);
        return g;
    }

    private Auction persistAuction(User seller, Long realtyGroupId) {
        UUID parcelUuid = UUID.randomUUID();
        Auction a = Auction.builder()
            .title("FK test " + suffix())
            .slParcelUuid(parcelUuid)
            .seller(seller)
            .realtyGroupId(realtyGroupId)
            .status(AuctionStatus.DRAFT)

            .verificationTier(VerificationTier.SCRIPT)
            .startingBid(10L)
            .durationHours(24)
            .snipeProtect(false)
            .listingFeePaid(false)
            .currentBid(0L)
            .bidCount(0)
            .consecutiveWorldApiFailures(0)
            .commissionRate(new BigDecimal("0.05"))
            .build();
        a.setParcelSnapshot(AuctionParcelSnapshot.builder()
            .slParcelUuid(parcelUuid)
            .ownerUuid(UUID.randomUUID())
            .ownerType("agent")
            .parcelName("FK Parcel")
            .regionName("FK Region")
            .regionMaturityRating("GENERAL")
            .areaSqm(512)
            .positionX(128.0).positionY(64.0).positionZ(22.0)
            .build());
        Auction saved = auctionRepository.save(a);
        auctionIdForCleanup = saved.getId();
        return saved;
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
