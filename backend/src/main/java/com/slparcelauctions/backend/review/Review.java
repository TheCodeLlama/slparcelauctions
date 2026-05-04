package com.slparcelauctions.backend.review;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.common.BaseMutableEntity;
import com.slparcelauctions.backend.user.User;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Blind-reveal review of one party's behaviour on a completed auction
 * (Epic 08 sub-spec 1 §3.1). Created via
 * {@code POST /api/v1/auctions/{id}/reviews} with {@code visible=false}
 * and flipped to {@code visible=true} either (a) when the counterparty
 * also submits inside the 14-day window or (b) when the day-14 scheduler
 * sweep runs. The {@code reviewedRole} column is persisted (not derived
 * from reviewer/reviewee roles) so per-role aggregate queries use an
 * index-only scan over {@code idx_reviews_reviewee_visible}.
 *
 * <p>The uniqueness constraint on {@code (auction_id, reviewer_id)}
 * enforces "one review per party per auction" at the DB level —
 * {@code ReviewService.submit} checks this in application code first so
 * callers get a {@code 409 ReviewAlreadySubmittedException} instead of a
 * constraint-violation stack trace, but the DB constraint is the last-
 * line-of-defence for concurrent submits.
 */
@Entity
@Table(name = "reviews",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_reviews_auction_reviewer",
                columnNames = {"auction_id", "reviewer_id"}),
        indexes = {
                @Index(name = "idx_reviews_reviewee_visible",
                        columnList = "reviewee_id, reviewed_role, visible"),
                @Index(name = "idx_reviews_auction", columnList = "auction_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Review extends BaseMutableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auction_id", nullable = false)
    private Auction auction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reviewer_id", nullable = false)
    private User reviewer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reviewee_id", nullable = false)
    private User reviewee;

    @Enumerated(EnumType.STRING)
    @Column(name = "reviewed_role", nullable = false, length = 10)
    private ReviewedRole reviewedRole;

    /**
     * 1..5 integer rating. Range enforced at the DTO layer via Bean
     * Validation {@code @Min(1) @Max(5)}, not at the entity column —
     * the entity is the persistence model, not the input validator.
     */
    @Column(nullable = false)
    private Integer rating;

    /**
     * Optional free-text body, capped at 500 chars. Nullable by design:
     * rating-only reviews are legitimate input.
     */
    @Column(length = 500)
    private String text;

    /**
     * Visibility gate. {@code false} on submit; flipped to {@code true}
     * by {@code ReviewService.reveal} once the counterparty also
     * submits (Task 2 simultaneous-reveal path) or the day-14 scheduler
     * sweeps. Task 1 only ever persists this column as {@code false}.
     */
    @Builder.Default
    @Column(nullable = false)
    private Boolean visible = false;

    @CreationTimestamp
    @Column(name = "submitted_at", nullable = false, updatable = false)
    private OffsetDateTime submittedAt;

    /**
     * Stamped by {@code ReviewService.reveal} at the moment the row
     * becomes visible. Null until then. Task 2 populates this.
     */
    @Column(name = "revealed_at")
    private OffsetDateTime revealedAt;

    /**
     * Running count of {@link ReviewFlag} rows pointing at this
     * review. Incremented by the flag endpoint (Task 3). No auto-hide;
     * flags are moderation signal, not censorship.
     */
    @Builder.Default
    @Column(name = "flag_count", nullable = false)
    private Integer flagCount = 0;

    @Column(name = "response_closing_reminder_sent_at")
    private OffsetDateTime responseClosingReminderSentAt;
}
