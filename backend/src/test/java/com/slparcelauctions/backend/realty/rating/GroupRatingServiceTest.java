package com.slparcelauctions.backend.realty.rating;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.rating.dto.GroupRatingDto;
import com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroup;
import com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroupRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Service test for {@link GroupRatingService} (spec §16.1, §16.2) and unit test for
 * {@link GroupRatingCacheInvalidator}.
 *
 * <p>The aggregate query joins {@code reviews} -&gt; {@code auctions} and uses
 * Postgres-specific casts ({@code ::double precision}) + a correlated EXISTS over
 * {@code realty_group_sl_groups}. {@code @DataJpaTest} (H2) cannot run this; we use
 * the same {@code @SpringBootTest @ActiveProfiles("dev")} surface as the commission
 * analytics test and clean rows manually after each case.
 *
 * <p>Spec: {@code docs/superpowers/specs/2026-05-12-realty-groups-admin-moderation-design.md} §16.
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
class GroupRatingServiceTest {

    /** Prefix used by every row this test creates so {@link #cleanup()} can scope its DELETEs. */
    private static final String EMAIL_PREFIX = "grs-svc-";

    @Autowired GroupRatingService service;
    @Autowired RealtyGroupRepository groupRepo;
    @Autowired UserRepository userRepo;
    @Autowired StringRedisTemplate redis;
    @Autowired DataSource dataSource;

    private final List<Long> insertedReviewIds = new ArrayList<>();
    private final List<Long> insertedAuctionIds = new ArrayList<>();
    private final List<Long> insertedSlGroupIds = new ArrayList<>();
    private final List<Long> insertedGroupIds = new ArrayList<>();
    private final List<String> redisKeysToClean = new ArrayList<>();

    @AfterEach
    void cleanup() throws Exception {
        for (String key : redisKeysToClean) {
            try { redis.delete(key); } catch (RuntimeException ignored) { /* best-effort */ }
        }
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            conn.setAutoCommit(true);
            // Order: drop child rows before parents.
            for (Long id : insertedReviewIds) {
                stmt.execute("DELETE FROM reviews WHERE id = " + id);
            }
            for (Long id : insertedAuctionIds) {
                stmt.execute("DELETE FROM auction_parcel_snapshots WHERE auction_id = " + id);
                stmt.execute("DELETE FROM auctions WHERE id = " + id);
            }
            for (Long id : insertedSlGroupIds) {
                stmt.execute("DELETE FROM realty_group_sl_groups WHERE id = " + id);
            }
            for (Long id : insertedGroupIds) {
                stmt.execute("DELETE FROM realty_groups WHERE id = " + id);
            }
            stmt.execute("DELETE FROM users WHERE email LIKE '" + EMAIL_PREFIX + "%@test.local'");
        }
    }

    // ────────────────────────── service tests ──────────────────────────

    @Test
    void computeRating_aggregatesCase1AndCase3Reviews() throws Exception {
        Fixture f = newFixture();

        // Case-1: auction linked directly to group via realty_group_id.
        long case1Auction = insertAuction(f.group.getId(), null);
        // Case-3: auction linked indirectly via realty_group_sl_groups -> realty_group_id.
        long slGroupId = insertSlGroupForGroup(f.group.getId());
        long case3Auction = insertAuction(null, slGroupId);
        // Distractor: auction with no group linkage.
        long unrelatedAuction = insertAuction(null, null);

        // Reviews: 5 + 3 on the group's auctions, 1 on the unrelated auction.
        insertReview(case1Auction, f.alice.getId(), f.bob.getId(), 5);
        insertReview(case3Auction, f.bob.getId(), f.alice.getId(), 3);
        insertReview(unrelatedAuction, f.alice.getId(), f.bob.getId(), 1);

        scheduleCacheCleanup(f.group.getId());

        GroupRatingDto rating = service.computeRating(f.group.getId());

        assertThat(rating.reviewCount()).isEqualTo(2);
        assertThat(rating.averageRating()).isNotNull();
        assertThat(rating.averageRating()).isEqualTo(4.0); // (5 + 3) / 2
    }

    @Test
    void computeRating_emptyResult_whenNoReviews() {
        Fixture f = newFixture();
        scheduleCacheCleanup(f.group.getId());

        GroupRatingDto rating = service.computeRating(f.group.getId());

        assertThat(rating.averageRating()).isNull();
        assertThat(rating.reviewCount()).isZero();
    }

    @Test
    void computeRating_cachesResultInRedis() throws Exception {
        Fixture f = newFixture();
        long auctionId = insertAuction(f.group.getId(), null);
        insertReview(auctionId, f.alice.getId(), f.bob.getId(), 4);

        String cacheKey = GroupRatingService.CACHE_KEY_PREFIX + f.group.getId();
        scheduleCacheCleanup(f.group.getId());
        // Force a clean baseline so any stray cached value from a prior run cannot
        // mask the cache-write being asserted here.
        redis.delete(cacheKey);

        GroupRatingDto first = service.computeRating(f.group.getId());
        assertThat(first.reviewCount()).isEqualTo(1);
        assertThat(first.averageRating()).isEqualTo(4.0);

        String cached = redis.opsForValue().get(cacheKey);
        assertThat(cached).isNotNull();
        // Encoded as "{avg}|{count}".
        assertThat(cached).isEqualTo("4.0|1");

        // Second call: even after we delete the underlying review row, the cached value
        // is returned -- proves the read came from Redis, not a fresh DB scan.
        for (Long id : insertedReviewIds) {
            try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
                conn.setAutoCommit(true);
                stmt.execute("DELETE FROM reviews WHERE id = " + id);
            }
        }
        insertedReviewIds.clear();

        GroupRatingDto second = service.computeRating(f.group.getId());
        assertThat(second.reviewCount()).isEqualTo(1);
        assertThat(second.averageRating()).isEqualTo(4.0);
    }

    @Test
    void invalidate_evictsCacheOnNewReview() {
        Fixture f = newFixture();
        String cacheKey = GroupRatingService.CACHE_KEY_PREFIX + f.group.getId();
        scheduleCacheCleanup(f.group.getId());

        // Seed the cache directly with the encoded form the service uses.
        redis.opsForValue().set(cacheKey, "4.5|10");
        assertThat(redis.opsForValue().get(cacheKey)).isEqualTo("4.5|10");

        service.invalidate(f.group.getId());

        assertThat(redis.opsForValue().get(cacheKey)).isNull();
    }

    // ────────────────────────── invalidator unit test ──────────────────────────

    @Test
    void on_reviewCreatedEvent_invalidatesGroupCache_case1() {
        GroupRatingService ratingService = mock(GroupRatingService.class);
        AuctionRepository auctionRepoMock = mock(AuctionRepository.class);
        RealtyGroupSlGroupRepository slGroupRepoMock = mock(RealtyGroupSlGroupRepository.class);
        GroupRatingCacheInvalidator invalidator = new GroupRatingCacheInvalidator(
            ratingService, auctionRepoMock, slGroupRepoMock);

        Auction a = Auction.builder().title("t").realtyGroupId(99L).build();
        when(auctionRepoMock.findById(1L)).thenReturn(Optional.of(a));

        invalidator.on(new ReviewCreatedEvent(1L, 7L, 5));

        verify(ratingService, times(1)).invalidate(99L);
        verify(slGroupRepoMock, never()).findById(any());
    }

    @Test
    void on_reviewCreatedEvent_invalidatesGroupCache_case3() {
        GroupRatingService ratingService = mock(GroupRatingService.class);
        AuctionRepository auctionRepoMock = mock(AuctionRepository.class);
        RealtyGroupSlGroupRepository slGroupRepoMock = mock(RealtyGroupSlGroupRepository.class);
        GroupRatingCacheInvalidator invalidator = new GroupRatingCacheInvalidator(
            ratingService, auctionRepoMock, slGroupRepoMock);

        Auction a = Auction.builder().title("t").realtyGroupSlGroupId(55L).build();
        when(auctionRepoMock.findById(1L)).thenReturn(Optional.of(a));
        RealtyGroupSlGroup rsg = RealtyGroupSlGroup.builder().realtyGroupId(123L).build();
        // The invalidator only inspects realtyGroupId on the row, so the id field on the
        // mock doesn't need to be set; verify the chain by stubbing findById to return rsg.
        when(slGroupRepoMock.findById(55L)).thenReturn(Optional.of(rsg));

        invalidator.on(new ReviewCreatedEvent(1L, 7L, 3));

        verify(ratingService, times(1)).invalidate(123L);
    }

    @Test
    void on_reviewCreatedEvent_noop_whenAuctionHasNoGroupLinkage() {
        GroupRatingService ratingService = mock(GroupRatingService.class);
        AuctionRepository auctionRepoMock = mock(AuctionRepository.class);
        RealtyGroupSlGroupRepository slGroupRepoMock = mock(RealtyGroupSlGroupRepository.class);
        GroupRatingCacheInvalidator invalidator = new GroupRatingCacheInvalidator(
            ratingService, auctionRepoMock, slGroupRepoMock);

        Auction a = Auction.builder().title("t").build();
        when(auctionRepoMock.findById(1L)).thenReturn(Optional.of(a));

        invalidator.on(new ReviewCreatedEvent(1L, 7L, 4));

        verify(ratingService, never()).invalidate(any());
    }

    // ────────────────────────── fixtures ──────────────────────────

    private record Fixture(User leader, User alice, User bob, RealtyGroup group) {}

    private Fixture newFixture() {
        User leader = persistUser("l");
        User alice = persistUser("a");
        User bob = persistUser("b");

        String slug = "grs-" + UUID.randomUUID().toString().substring(0, 8);
        RealtyGroup group = groupRepo.save(RealtyGroup.builder()
            .name("GRS Test Group " + slug)
            .slug(slug)
            .leaderId(leader.getId())
            .build());
        insertedGroupIds.add(group.getId());

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

    private long insertAuction(Long realtyGroupId, Long realtyGroupSlGroupId) throws Exception {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);

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

    private long insertSlGroupForGroup(Long realtyGroupId) throws Exception {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("""
                    INSERT INTO realty_group_sl_groups (
                        public_id, realty_group_id, sl_group_uuid, sl_group_name,
                        verified, verified_at, verified_via, consecutive_fetch_failures,
                        created_at, updated_at
                    )
                    VALUES (?, ?, ?, 'Test SL Group', true, now(), 'FOUNDER_TERMINAL', 0,
                            now(), now())
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

    /** Insert a {@code reviews} row directly so the test does not have to satisfy the
     *  full ReviewService eligibility ladder (escrow/COMPLETED, 14-day window, etc.). */
    private void insertReview(long auctionId, Long reviewerId, Long revieweeId, int rating)
            throws Exception {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("""
                    INSERT INTO reviews (
                        public_id, auction_id, reviewer_id, reviewee_id, reviewed_role,
                        rating, visible, flag_count, submitted_at, created_at, updated_at
                    )
                    VALUES (?, ?, ?, ?, 'SELLER', ?, true, 0, now(), now(), now())
                    RETURNING id
                    """)) {
            conn.setAutoCommit(true);
            ps.setObject(1, UUID.randomUUID());
            ps.setLong(2, auctionId);
            ps.setLong(3, reviewerId);
            ps.setLong(4, revieweeId);
            ps.setInt(5, rating);
            var rs = ps.executeQuery();
            rs.next();
            insertedReviewIds.add(rs.getLong(1));
        }
    }

    private void scheduleCacheCleanup(Long groupId) {
        redisKeysToClean.add(GroupRatingService.CACHE_KEY_PREFIX + groupId);
    }
}
