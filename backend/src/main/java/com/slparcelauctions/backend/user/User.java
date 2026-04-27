package com.slparcelauctions.backend.user;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "sl_avatar_uuid", unique = true)
    private UUID slAvatarUuid;

    @Column(name = "sl_avatar_name")
    private String slAvatarName;

    @Column(name = "sl_display_name")
    private String slDisplayName;

    @Column(name = "sl_username")
    private String slUsername;

    @Column(name = "sl_born_date")
    private LocalDate slBornDate;

    @Column(name = "sl_payinfo")
    private Integer slPayinfo;

    @Column(name = "display_name")
    private String displayName;

    @Column(columnDefinition = "text")
    private String bio;

    @Column(name = "profile_pic_url", columnDefinition = "text")
    private String profilePicUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10,
            columnDefinition = "varchar(10) not null default 'USER'")
    @Builder.Default
    private Role role = Role.USER;

    @Builder.Default
    @Column(nullable = false)
    private Boolean verified = false;

    @Column(name = "verified_at")
    private OffsetDateTime verifiedAt;

    @Column(name = "avg_seller_rating", precision = 3, scale = 2)
    private BigDecimal avgSellerRating;

    @Column(name = "avg_buyer_rating", precision = 3, scale = 2)
    private BigDecimal avgBuyerRating;

    @Builder.Default
    @Column(name = "total_seller_reviews", nullable = false)
    private Integer totalSellerReviews = 0;

    @Builder.Default
    @Column(name = "total_buyer_reviews", nullable = false)
    private Integer totalBuyerReviews = 0;

    @Builder.Default
    @Column(name = "completed_sales", nullable = false)
    private Integer completedSales = 0;

    @Builder.Default
    @Column(name = "cancelled_with_bids", nullable = false)
    private Integer cancelledWithBids = 0;

    /**
     * Count of escrows whose 72h transfer deadline elapsed with no parcel
     * handover — the seller-fault denominator in the three-counter
     * completion-rate formula. Incremented inside
     * {@code EscrowService.expireTransfer} in the same transaction that
     * flips the escrow to {@code EXPIRED}. Not touched by
     * {@code expirePayment} (buyer-fault). Added in Epic 08 sub-spec 1
     * §3.4; see {@link SellerCompletionRateMapper#compute(int, int, int)}.
     *
     * <p>The {@code columnDefinition} supplies a SQL-side default so
     * Hibernate's {@code ddl-auto: update} can add this NOT NULL column to
     * existing rows on local dev databases without failing. The
     * {@code @Builder.Default} handles the Java-side default for newly-
     * constructed entities.
     */
    @Builder.Default
    @Column(name = "escrow_expired_unfulfilled", nullable = false,
            columnDefinition = "integer not null default 0")
    private Integer escrowExpiredUnfulfilled = 0;

    @Column(name = "listing_suspension_until")
    private OffsetDateTime listingSuspensionUntil;

    /**
     * Outstanding penalty debt in L$ owed by this seller from cancelled-with-
     * bids offenses on the cancellation ladder (Epic 08 sub-spec 2 §2). Pay-at-
     * terminal model: while this is &gt; 0 the seller is suspended from creating
     * new listings; payment at any SLPA terminal pays it down to zero, at which
     * point the suspension lifts. Incremented atomically inside
     * {@code CancellationService.cancel} when the ladder selects
     * {@code PENALTY} or {@code PENALTY_AND_30D}; decremented by
     * {@code PenaltyTerminalService} on payment receipt.
     *
     * <p>The {@code columnDefinition} supplies a SQL-side default so
     * Hibernate's {@code ddl-auto: update} can add this NOT NULL column to
     * existing rows on local dev databases without failing. The
     * {@code @Builder.Default} handles the Java-side default for newly-
     * constructed entities.
     */
    @Builder.Default
    @Column(name = "penalty_balance_owed", nullable = false,
            columnDefinition = "bigint not null default 0")
    private Long penaltyBalanceOwed = 0L;

    /**
     * Permanent listing ban flag. Set to {@code true} on the seller's 4th+
     * cancelled-with-bids offense by {@code CancellationService.cancel} when
     * the ladder selects {@code PERMANENT_BAN}. Once set, listing creation is
     * permanently denied — there is no automatic clear path; admins must
     * intervene. See spec §2 (ladder) and §7.7 (gate semantics).
     *
     * <p>The {@code columnDefinition} supplies a SQL-side default so
     * Hibernate's {@code ddl-auto: update} can add this NOT NULL column to
     * existing rows on local dev databases without failing. The
     * {@code @Builder.Default} handles the Java-side default for newly-
     * constructed entities.
     */
    @Builder.Default
    @Column(name = "banned_from_listing", nullable = false,
            columnDefinition = "boolean not null default false")
    private Boolean bannedFromListing = false;

    /**
     * Count of listing reports submitted by this user that were dismissed by
     * admins (Epic 10 sub-spec 2 §5). Used as a frivolous-reporter signal:
     * high dismissed counts may be surfaced in the admin queue to flag repeat
     * low-quality reporters. Incremented inside
     * {@code AdminReportService.dismissReport} when a report transitions to
     * {@code DISMISSED}. Never decremented.
     *
     * <p>The {@code columnDefinition} supplies a SQL-side default so
     * Hibernate's {@code ddl-auto: update} can add this NOT NULL column to
     * existing rows on local dev databases without failing. The
     * {@code @Builder.Default} handles the Java-side default for newly-
     * constructed entities.
     */
    @Builder.Default
    @Column(name = "dismissed_reports_count", nullable = false,
            columnDefinition = "bigint not null default 0")
    private Long dismissedReportsCount = 0L;

    @Builder.Default
    @Column(name = "email_verified", nullable = false)
    private Boolean emailVerified = false;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "notify_email", columnDefinition = "jsonb")
    private Map<String, Object> notifyEmail = defaultNotifyEmail();

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "notify_sl_im", columnDefinition = "jsonb")
    private Map<String, Object> notifySlIm = defaultNotifySlIm();

    @Builder.Default
    @Column(name = "notify_email_muted", nullable = false)
    private Boolean notifyEmailMuted = false;

    @Builder.Default
    @Column(name = "notify_sl_im_muted", nullable = false)
    private Boolean notifySlImMuted = false;

    @Column(name = "sl_im_quiet_start")
    private LocalTime slImQuietStart;

    @Column(name = "sl_im_quiet_end")
    private LocalTime slImQuietEnd;

    /**
     * Freshness-mitigation counter for the auth slice. Incremented on events that should
     * invalidate all live access tokens for this user (ban, suspension, password change,
     * logout-all, role change, account deletion). Access tokens carry this value as a
     * {@code tv} claim; write-path services compare against the live value at the integrity
     * boundary. See spec §2 and FOOTGUNS §B.4.
     *
     * <p>The {@code columnDefinition} supplies a SQL-side default so Hibernate's
     * {@code ddl-auto: update} can add this NOT NULL column to existing rows on local dev
     * databases without failing. The {@code @Builder.Default} handles the Java-side default
     * for newly-constructed entities.
     */
    @Column(name = "token_version", nullable = false,
            columnDefinition = "bigint not null default 0")
    @Builder.Default
    private Long tokenVersion = 0L;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    private static Map<String, Object> defaultNotifyEmail() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bidding", true);
        m.put("auction_result", true);
        m.put("escrow", true);
        m.put("listing_status", true);
        m.put("reviews", true);
        m.put("realty_group", true);
        m.put("marketing", false);
        return m;
    }

    private static Map<String, Object> defaultNotifySlIm() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bidding", true);
        m.put("auction_result", true);
        m.put("escrow", true);
        m.put("listing_status", true);
        m.put("reviews", false);
        m.put("realty_group", false);
        m.put("marketing", false);
        return m;
    }
}
