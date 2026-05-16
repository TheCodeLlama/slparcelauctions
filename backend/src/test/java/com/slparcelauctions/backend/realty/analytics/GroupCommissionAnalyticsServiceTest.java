package com.slparcelauctions.backend.realty.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupMember;
import com.slparcelauctions.backend.realty.RealtyGroupMemberRepository;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.analytics.dto.MemberCommissionRowDto;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupPermissionDeniedException;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Service test for {@link GroupCommissionAnalyticsService}.
 *
 * <p>Runs against real Postgres ({@code @SpringBootTest @ActiveProfiles("dev")}) because
 * the query relies on Postgres-only syntax ({@code FILTER (WHERE ...)},
 * {@code INTERVAL '30 days'}, correlated {@code EXISTS}). H2 does not implement
 * {@code FILTER}; {@code @DataJpaTest} is not viable.
 *
 * <p>Auctions / ledger rows are inserted via raw JDBC to keep the fixture surface
 * minimal — the {@link com.slparcelauctions.backend.auction.Auction} entity carries
 * many required columns whose construction adds no signal to this query's behaviour.
 *
 * <p>Spec §6.8 / §15.2.
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
    "slpa.realty.invitation-expiry.enabled=false",
    "slpa.realty.group-suspension-expiry.enabled=false",
    "slpa.realty.group-bulk-suspend.enabled=false",
    "slpa.realty.sl-group.reverify.enabled=false"
})
class GroupCommissionAnalyticsServiceTest {

    /** Prefix used by every row this test creates so {@link #cleanup()} can scope its DELETEs. */
    private static final String EMAIL_PREFIX = "gca-svc-";

    @Autowired GroupCommissionAnalyticsService service;
    @Autowired RealtyGroupRepository groupRepo;
    @Autowired RealtyGroupMemberRepository memberRepo;
    @Autowired UserRepository userRepo;
    @Autowired DataSource dataSource;

    private final List<Long> insertedAuctionIds = new ArrayList<>();
    private final List<Long> insertedLedgerIds = new ArrayList<>();
    private final List<Long> insertedSlGroupIds = new ArrayList<>();

    @AfterEach
    void cleanup() throws Exception {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            conn.setAutoCommit(true);
            // Order: drop child rows before parents.
            for (Long id : insertedLedgerIds) {
                stmt.execute("DELETE FROM user_ledger WHERE id = " + id);
            }
            for (Long id : insertedAuctionIds) {
                stmt.execute("DELETE FROM auction_parcel_snapshots WHERE auction_id = " + id);
                stmt.execute("DELETE FROM auctions WHERE id = " + id);
            }
            for (Long id : insertedSlGroupIds) {
                stmt.execute("DELETE FROM realty_group_sl_groups WHERE id = " + id);
            }
            stmt.execute(
                "DELETE FROM realty_group_members"
                + " WHERE user_id IN (SELECT id FROM users WHERE email LIKE '"
                + EMAIL_PREFIX + "%@test.local')");
            stmt.execute(
                "DELETE FROM realty_groups"
                + " WHERE leader_id IN (SELECT id FROM users WHERE email LIKE '"
                + EMAIL_PREFIX + "%@test.local')");
            stmt.execute("DELETE FROM users WHERE email LIKE '" + EMAIL_PREFIX + "%@test.local'");
        }
    }

    // ─────────────────────────── tests ───────────────────────────

    @Test
    void compute_returnsRowsPerMemberWithLifetimeAndLast30Days() throws Exception {
        Fixture f = newFixture();
        // Three qualifying auctions for the group (case-1 linkage via realty_group_id).
        // Distinct auctions per ledger row so each AGCOMM-{auctionId} idempotency
        // key is unique -- prod has one AGENT_COMMISSION_CREDIT per auction (spec §9.6).
        long aliceOldAuction   = insertAuction(f.group.getId(), null);
        long aliceFreshAuction = insertAuction(f.group.getId(), null);
        long bobFreshAuction   = insertAuction(f.group.getId(), null);

        // alice: 100 L$ 60 days ago + 50 L$ today = 150 lifetime / 50 last-30d.
        insertLedger(f.alice.getId(), aliceOldAuction,   100, daysAgo(60));
        insertLedger(f.alice.getId(), aliceFreshAuction,  50, daysAgo(1));
        // bob: 200 L$ today = 200 lifetime / 200 last-30d.
        insertLedger(f.bob.getId(), bobFreshAuction, 200, daysAgo(1));
        // leader has no commission rows -> zero/zero.

        List<MemberCommissionRowDto> rows = service.compute(
            f.group.getPublicId(), f.leader.getId());

        // ORDER BY lifetime DESC -> bob, alice, leader.
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).memberPublicId()).isEqualTo(f.bob.getPublicId());
        assertThat(rows.get(0).lifetimeLindens()).isEqualTo(200);
        assertThat(rows.get(0).last30DaysLindens()).isEqualTo(200);
        assertThat(rows.get(1).memberPublicId()).isEqualTo(f.alice.getPublicId());
        assertThat(rows.get(1).lifetimeLindens()).isEqualTo(150);
        assertThat(rows.get(1).last30DaysLindens()).isEqualTo(50);
        assertThat(rows.get(2).memberPublicId()).isEqualTo(f.leader.getPublicId());
        assertThat(rows.get(2).lifetimeLindens()).isZero();
        assertThat(rows.get(2).last30DaysLindens()).isZero();
    }

    @Test
    void compute_filtersToCase1AndCase3AuctionsBelongingToGroup() throws Exception {
        Fixture f = newFixture();

        // Case 1: auction directly linked to f.group via realty_group_id.
        long case1Auction = insertAuction(f.group.getId(), null);
        // Case 3: auction linked through a realty_group_sl_group row for f.group.
        long slGroupId = insertSlGroupForGroup(f.group.getId());
        long case3Auction = insertAuction(null, slGroupId);

        // A third auction belongs to a DIFFERENT group -- must NOT contribute even
        // though the ledger row sits on alice (who is a member of f.group too).
        Fixture other = newFixture();
        long otherAuction = insertAuction(other.group.getId(), null);

        insertLedger(f.alice.getId(), case1Auction, 100, daysAgo(2));
        insertLedger(f.alice.getId(), case3Auction, 50,  daysAgo(2));
        insertLedger(f.alice.getId(), otherAuction, 9999, daysAgo(2));

        List<MemberCommissionRowDto> rows = service.compute(
            f.group.getPublicId(), f.leader.getId());

        MemberCommissionRowDto aliceRow = rows.stream()
            .filter(r -> r.memberPublicId().equals(f.alice.getPublicId()))
            .findFirst().orElseThrow();
        // 100 + 50 only; 9999 from the other group must not leak in.
        assertThat(aliceRow.lifetimeLindens()).isEqualTo(150);
    }

    @Test
    void compute_emptyResult_whenNoCommissions() {
        Fixture f = newFixture();
        // Group has 3 members (leader, alice, bob) but no qualifying ledger rows.
        List<MemberCommissionRowDto> rows = service.compute(
            f.group.getPublicId(), f.leader.getId());

        assertThat(rows).hasSize(3);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.lifetimeLindens()).isZero();
            assertThat(r.last30DaysLindens()).isZero();
        });
    }

    @Test
    void compute_requiresLeaderOrManageMembers() {
        Fixture f = newFixture();

        // alice is a plain member without MANAGE_MEMBERS -> 403.
        assertThatThrownBy(() -> service.compute(f.group.getPublicId(), f.alice.getId()))
            .isInstanceOf(RealtyGroupPermissionDeniedException.class);

        // Grant MANAGE_MEMBERS -> succeeds.
        RealtyGroupMember aliceRow = memberRepo
            .findByGroupIdAndUserId(f.group.getId(), f.alice.getId()).orElseThrow();
        aliceRow.setPermissionSet(EnumSet.of(RealtyGroupPermission.MANAGE_MEMBERS));
        memberRepo.save(aliceRow);

        List<MemberCommissionRowDto> rows = service.compute(
            f.group.getPublicId(), f.alice.getId());
        assertThat(rows).hasSize(3);
    }

    @Test
    void compute_throwsWhenGroupNotFound() {
        UUID unknown = UUID.randomUUID();
        assertThatThrownBy(() -> service.compute(unknown, 0L))
            .isInstanceOf(RealtyGroupNotFoundException.class);
    }

    // ─────────────────────────── fixtures ───────────────────────────

    private record Fixture(User leader, User alice, User bob, RealtyGroup group) {}

    private Fixture newFixture() {
        User leader = persistUser("l");
        User alice = persistUser("a");
        User bob = persistUser("b");

        String slug = "gca-" + UUID.randomUUID().toString().substring(0, 8);
        RealtyGroup group = groupRepo.save(RealtyGroup.builder()
            .name("GCA Test Group " + slug)
            .slug(slug)
            .leaderId(leader.getId())
            .build());

        memberRepo.save(RealtyGroupMember.builder()
            .groupId(group.getId()).userId(leader.getId())
            .joinedAt(OffsetDateTime.now())
            .build());
        memberRepo.save(RealtyGroupMember.builder()
            .groupId(group.getId()).userId(alice.getId())
            .joinedAt(OffsetDateTime.now())
            .build());
        memberRepo.save(RealtyGroupMember.builder()
            .groupId(group.getId()).userId(bob.getId())
            .joinedAt(OffsetDateTime.now())
            .build());

        return new Fixture(leader, alice, bob, group);
    }

    private User persistUser(String tag) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return userRepo.save(User.builder()
            .username(EMAIL_PREFIX + tag + "-" + suffix)
            .email(EMAIL_PREFIX + tag + "-" + suffix + "@test.local")
            .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
            .displayName(tag + "-" + suffix)
            .verified(true)
            .slAvatarUuid(UUID.randomUUID())
            .build());
    }

    /** Insert a minimal auctions row + paired auction_parcel_snapshot, optionally linked
     *  to a realty group (case-1) and/or a realty_group_sl_groups row (case-3). */
    private long insertAuction(Long realtyGroupId, Long realtyGroupSlGroupId) throws Exception {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);

            // seller_id: any user works; use the first member of the test group context.
            long sellerId;
            try (var rs = conn.createStatement().executeQuery(
                    "SELECT id FROM users WHERE email LIKE '" + EMAIL_PREFIX
                    + "%@test.local' ORDER BY id LIMIT 1")) {
                rs.next();
                sellerId = rs.getLong(1);
            }

            UUID parcelUuid = UUID.randomUUID();
            long auctionId;
            try (var ps = conn.prepareStatement("""
                    INSERT INTO auctions (
                        bid_count, current_bid, duration_hours,
                        listing_fee_paid, snipe_protect, starting_bid, status, title,
                        seller_id, realty_group_id, realty_group_sl_group_id,
                        sl_parcel_uuid, created_at, updated_at, public_id
                    )
                    VALUES (0, 0, 72, false, false, 1000, 'ACTIVE', 'Test Auction',
                            ?, ?, ?, ?, now(), now(), ?)
                    RETURNING id
                    """)) {
                ps.setLong(1, sellerId);
                if (realtyGroupId != null) ps.setLong(2, realtyGroupId);
                else ps.setNull(2, java.sql.Types.BIGINT);
                if (realtyGroupSlGroupId != null) ps.setLong(3, realtyGroupSlGroupId);
                else ps.setNull(3, java.sql.Types.BIGINT);
                ps.setObject(4, parcelUuid);
                ps.setObject(5, UUID.randomUUID());
                var rs = ps.executeQuery();
                rs.next();
                auctionId = rs.getLong(1);
            }

            // auction_parcel_snapshots has FK auction_id, NOT NULL sl_parcel_uuid.
            try (var ps = conn.prepareStatement("""
                    INSERT INTO auction_parcel_snapshots (auction_id, sl_parcel_uuid)
                    VALUES (?, ?)
                    """)) {
                ps.setLong(1, auctionId);
                ps.setObject(2, parcelUuid);
                ps.executeUpdate();
            }

            insertedAuctionIds.add(auctionId);
            return auctionId;
        }
    }

    /** Insert a realty_group_sl_groups row linked to the given realty group. */
    private long insertSlGroupForGroup(Long realtyGroupId) throws Exception {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("""
                    INSERT INTO realty_group_sl_groups (
                        public_id, realty_group_id, sl_group_uuid, sl_group_name,
                        verified, verified_at, verified_via, created_at, updated_at
                    )
                    VALUES (?, ?, ?, 'Test SL Group', true, now(), 'FOUNDER_TERMINAL', now(), now())
                    RETURNING id
                    """)) {
            conn.setAutoCommit(true);
            ps.setObject(1, UUID.randomUUID());
            ps.setLong(2, realtyGroupId);
            ps.setObject(3, UUID.randomUUID());
            var rs = ps.executeQuery();
            rs.next();
            long id = rs.getLong(1);
            insertedSlGroupIds.add(id);
            return id;
        }
    }

    /** Insert a user_ledger row of type AGENT_COMMISSION_CREDIT for the given user/auction. */
    private void insertLedger(Long userId, long auctionId, long amount, OffsetDateTime createdAt)
            throws Exception {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("""
                    INSERT INTO user_ledger (
                        user_id, entry_type, amount, balance_after, reserved_after,
                        ref_type, ref_id, idempotency_key, created_at, public_id
                    )
                    VALUES (?, 'AGENT_COMMISSION_CREDIT', ?, ?, 0, 'AUCTION', ?, ?, ?, ?)
                    RETURNING id
                    """)) {
            conn.setAutoCommit(true);
            ps.setLong(1, userId);
            ps.setLong(2, amount);
            ps.setLong(3, amount);
            ps.setLong(4, auctionId);
            ps.setString(5, "AGCOMM-" + auctionId);
            ps.setObject(6, createdAt);
            ps.setObject(7, UUID.randomUUID());
            var rs = ps.executeQuery();
            rs.next();
            insertedLedgerIds.add(rs.getLong(1));
        }
    }

    private static OffsetDateTime daysAgo(int days) {
        return OffsetDateTime.now().minusDays(days);
    }
}
