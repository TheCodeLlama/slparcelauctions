package com.slparcelauctions.backend.user;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import com.slparcelauctions.backend.common.BaseMutableEntity;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class User extends BaseMutableEntity {

    @Column(unique = true)
    private String email;

    @Column(nullable = false, length = 64)
    private String username;

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

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    /**
     * Outstanding penalty debt in L$ owed by this seller from cancelled-with-
     * bids offenses on the cancellation ladder (Epic 08 sub-spec 2 §2). Pay-at-
     * terminal model: while this is &gt; 0 the seller is suspended from creating
     * new listings; payment at any SLParcels terminal pays it down to zero, at which
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

    /**
     * Total wallet balance in L$. Includes both available and reserved L$.
     * The wallet model parks resident-paid L$ here on deposit; bids
     * hard-reserve from this balance via {@code reservedLindens}; auction
     * close debits this balance and releases the reservation. See spec
     * docs/superpowers/specs/2026-04-30-wallet-model-design.md §3.1.
     *
     * <p>Available balance ({@link #availableLindens()}) is computed as
     * {@code balanceLindens - reservedLindens}.
     */
    @Builder.Default
    @Column(name = "balance_lindens", nullable = false,
            columnDefinition = "bigint not null default 0")
    private Long balanceLindens = 0L;

    /**
     * Sum of active bid reservations for this user (denormalized from
     * {@code bid_reservations WHERE released_at IS NULL}). DB-enforced
     * {@code reserved_lindens >= 0} and {@code balance_lindens >=
     * reserved_lindens}. Reconciliation job verifies the denorm against the
     * live sum daily.
     */
    @Builder.Default
    @Column(name = "reserved_lindens", nullable = false,
            columnDefinition = "bigint not null default 0")
    private Long reservedLindens = 0L;

    /**
     * Stamped by {@code WalletDormancyJob} phase 1 when a user becomes
     * inactive (no refresh-token rotation in 30d) with a positive balance.
     * Cleared on any successful login or refresh-token rotation.
     */
    @Column(name = "wallet_dormancy_started_at")
    private OffsetDateTime walletDormancyStartedAt;

    /**
     * Set by an admin to block every wallet outflow path (withdraw,
     * pay-penalty, pay-listing-fee, bid reservation). Inflows still
     * work. Cleared by an admin via the unfreeze endpoint.
     */
    @Column(name = "wallet_frozen_at")
    private OffsetDateTime walletFrozenAt;

    @Column(name = "wallet_frozen_by_admin_id")
    private Long walletFrozenByAdminId;

    @Column(name = "wallet_frozen_reason", columnDefinition = "text")
    private String walletFrozenReason;

    /**
     * Current dormancy notification phase: 1-4 during the 4-week
     * notification window, 99 = COMPLETED (auto-return executed).
     * NULL = not in dormancy state.
     */
    @Column(name = "wallet_dormancy_phase")
    private Integer walletDormancyPhase;

    /**
     * Stamped on first wallet-terms-of-use click-through (via
     * {@code POST /me/wallet/accept-terms}). The wallet endpoints don't
     * gate on this flag — terms acceptance is a UX gate enforced by the
     * frontend's deposit-instructions modal.
     */
    @Column(name = "wallet_terms_accepted_at")
    private OffsetDateTime walletTermsAcceptedAt;

    /**
     * Version string of the wallet ToU the user accepted (e.g., "1.0").
     * Material changes bump the application's
     * {@code slpa.wallet.terms-version} config, prompting re-acceptance
     * on next visit when this value diverges.
     */
    @Column(name = "wallet_terms_version", length = 16)
    private String walletTermsVersion;

    /**
     * Available wallet balance — what the user can withdraw, allocate, or
     * commit to a new bid/listing-fee/penalty. Computed as
     * {@code balanceLindens - reservedLindens}.
     */
    @Transient
    public long availableLindens() {
        return balanceLindens - reservedLindens;
    }

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
