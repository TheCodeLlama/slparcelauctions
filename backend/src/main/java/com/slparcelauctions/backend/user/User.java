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

    @Column(name = "listing_suspension_until")
    private OffsetDateTime listingSuspensionUntil;

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
