package com.slparcelauctions.backend.realty.rating;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Integration test for {@link RealtyGroupReviewsController} (sub-project G §13).
 *
 * <p>Anonymous-accessible — no JWT in the request. Builds reviews via raw SQL
 * inserts (same pattern as {@link GroupRatingServiceTest}) to skip the
 * blind-reveal eligibility ladder; this test asserts the wire shape and the
 * attribution filter, not the review-submission flow.
 *
 * <p>The aggregation query is Postgres-specific (native SQL with EXISTS over
 * {@code realty_group_sl_groups}). Uses the same {@code @SpringBootTest
 * @ActiveProfiles("dev")} surface as the rating-service test and cleans rows
 * manually after each case.
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
    "slpa.realty.invitation-expiry.enabled=false",
    "slpa.realty.group-suspension-expiry.enabled=false",
    "slpa.realty.group-bulk-suspend.enabled=false",
    "slpa.realty.sl-group.reverify.enabled=false"
})
class RealtyGroupReviewsControllerTest {

    /** Prefix used by every row this test creates so {@link #cleanup()} can scope its DELETEs. */
    private static final String EMAIL_PREFIX = "rgrc-";

    @Autowired MockMvc mvc;
    @Autowired RealtyGroupRepository groupRepo;
    @Autowired UserRepository userRepo;
    @Autowired DataSource dataSource;

    private final List<Long> insertedReviewIds = new ArrayList<>();
    private final List<Long> insertedAuctionIds = new ArrayList<>();
    private final List<Long> insertedGroupIds = new ArrayList<>();

    @AfterEach
    void cleanup() throws Exception {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            conn.setAutoCommit(true);
            for (Long id : insertedReviewIds) {
                stmt.execute("DELETE FROM reviews WHERE id = " + id);
            }
            for (Long id : insertedAuctionIds) {
                stmt.execute("DELETE FROM auction_parcel_snapshots WHERE auction_id = " + id);
                stmt.execute("DELETE FROM auctions WHERE id = " + id);
            }
            for (Long id : insertedGroupIds) {
                stmt.execute("DELETE FROM realty_groups WHERE id = " + id);
            }
            stmt.execute("DELETE FROM users WHERE email LIKE '" + EMAIL_PREFIX + "%@test.local'");
        }
    }

    // ────────────────────────── tests ──────────────────────────

    @Test
    void returnsPagedReviewsForGroupAuctions_anonymousAccessOk() throws Exception {
        Fixture f = newFixture();
        long auctionId = insertAuction(f.group.getId());
        insertReview(auctionId, f.alice.getId(), f.bob.getId(), 4, "Great communication", true);

        mvc.perform(get("/api/v1/realty/groups/{publicId}/reviews", f.group.getPublicId())
                .param("page", "0").param("size", "20"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].rating").value(4))
            .andExpect(jsonPath("$.content[0].comment").value("Great communication"))
            .andExpect(jsonPath("$.content[0].auctionPublicId").exists())
            .andExpect(jsonPath("$.content[0].auctionTitle").exists())
            .andExpect(jsonPath("$.content[0].reviewerPublicId").exists())
            .andExpect(jsonPath("$.content[0].reviewerDisplayName").exists())
            .andExpect(jsonPath("$.content[0].createdAt").exists())
            .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void emptyGroupReturnsEmptyPage() throws Exception {
        Fixture f = newFixture();
        mvc.perform(get("/api/v1/realty/groups/{publicId}/reviews", f.group.getPublicId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isEmpty())
            .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void doesNotIncludeReviewsFromUnrelatedAuctions() throws Exception {
        // Spec §13.4: "does NOT include user-side reviews where the leader was a buyer/seller
        // outside the group." Concretely: a review on an auction with no realty_group_id and
        // no realty_group_sl_group_id attribution must not appear in this group's reviews
        // page, even when the same users are involved.
        Fixture f = newFixture();
        long unrelatedAuctionId = insertAuction(null);
        insertReview(unrelatedAuctionId, f.alice.getId(), f.bob.getId(), 5, "Off-topic", true);

        mvc.perform(get("/api/v1/realty/groups/{publicId}/reviews", f.group.getPublicId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isEmpty())
            .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void hidesNonVisibleReviews() throws Exception {
        // Pending blind-reveal rows (visible = false) must not appear on the public
        // group-reviews page; only the user-side reviews endpoint exposes them to the
        // reviewer themselves.
        Fixture f = newFixture();
        long auctionId = insertAuction(f.group.getId());
        insertReview(auctionId, f.alice.getId(), f.bob.getId(), 3, "Hidden submission", false);

        mvc.perform(get("/api/v1/realty/groups/{publicId}/reviews", f.group.getPublicId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isEmpty())
            .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void returnsNotFoundForDissolvedGroup() throws Exception {
        Fixture f = newFixture();
        // Soft-dissolve the group; lookup uses findByPublicIdAndDissolvedAtIsNull which
        // returns empty for any dissolved row, so the controller 404s.
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            conn.setAutoCommit(true);
            stmt.execute("UPDATE realty_groups SET dissolved_at = now() WHERE id = "
                + f.group.getId());
        }

        mvc.perform(get("/api/v1/realty/groups/{publicId}/reviews", f.group.getPublicId()))
            .andExpect(status().isNotFound());
    }

    // ────────────────────────── fixtures ──────────────────────────

    private record Fixture(User leader, User alice, User bob, RealtyGroup group) {}

    private Fixture newFixture() {
        User leader = persistUser("l");
        User alice = persistUser("a");
        User bob = persistUser("b");

        String slug = "rgrc-" + UUID.randomUUID().toString().substring(0, 8);
        RealtyGroup group = groupRepo.save(RealtyGroup.builder()
            .name("RGRC Test Group " + slug)
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
            .displayName("Reviewer-" + tag.toUpperCase() + "-" + suffix)
            .verified(true)
            .slAvatarUuid(UUID.randomUUID())
            .build());
    }

    private long insertAuction(Long realtyGroupId) throws Exception {
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
                        bid_count, bot_check_failures, current_bid, duration_hours,
                        listing_fee_paid, snipe_protect, starting_bid, status, title,
                        seller_id, realty_group_id,
                        sl_parcel_uuid, created_at, updated_at, public_id
                    )
                    VALUES (0, 0, 0, 72, false, false, 1000, 'ACTIVE',
                            'Sunset Cove 256m', ?, ?, ?, now(), now(), ?)
                    RETURNING id
                    """)) {
                ps.setLong(1, sellerId);
                if (realtyGroupId != null) ps.setLong(2, realtyGroupId);
                else ps.setNull(2, java.sql.Types.BIGINT);
                ps.setObject(3, parcelUuid);
                ps.setObject(4, UUID.randomUUID());
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

    /** Insert a {@code reviews} row directly so the test does not have to satisfy the
     *  full ReviewService eligibility ladder (escrow/COMPLETED, 14-day window, etc.). */
    private void insertReview(long auctionId, Long reviewerId, Long revieweeId, int rating,
                              String text, boolean visible) throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("""
                    INSERT INTO reviews (
                        public_id, auction_id, reviewer_id, reviewee_id, reviewed_role,
                        rating, text, visible, flag_count, submitted_at, created_at, updated_at
                    )
                    VALUES (?, ?, ?, ?, 'SELLER', ?, ?, ?, 0, ?, ?, ?)
                    RETURNING id
                    """)) {
            conn.setAutoCommit(true);
            ps.setObject(1, UUID.randomUUID());
            ps.setLong(2, auctionId);
            ps.setLong(3, reviewerId);
            ps.setLong(4, revieweeId);
            ps.setInt(5, rating);
            ps.setString(6, text);
            ps.setBoolean(7, visible);
            ps.setObject(8, now);
            ps.setObject(9, now);
            ps.setObject(10, now);
            var rs = ps.executeQuery();
            rs.next();
            insertedReviewIds.add(rs.getLong(1));
        }
    }
}
