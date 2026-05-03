package com.slparcelauctions.backend.auction.fraud;

import java.time.OffsetDateTime;
import java.util.Map;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.user.User;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Record of a fraud-related anomaly detected by the ownership monitor. Created
 * when an auction's underlying parcel exhibits behaviour that warrants human
 * review (ownership changed to an untrusted avatar, parcel went missing,
 * repeated World API failures, etc.). Resolution fields are populated by the
 * Epic 10 admin dashboard. See spec §8.2 and §8.8.
 */
@Entity
@Table(name = "fraud_flags")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FraudFlag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "auction_id")
    private Auction auction;

    /**
     * Denormalized SL parcel UUID at the time the flag was raised. No FK to
     * a parcel table — the legacy {@code parcels} table was removed in the
     * per-auction snapshot refactor; the canonical parcel identity lives on
     * {@code auctions.sl_parcel_uuid}.
     */
    @Column(name = "sl_parcel_uuid")
    private UUID slParcelUuid;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private FraudFlagReason reason;

    @Column(name = "detected_at", nullable = false)
    private OffsetDateTime detectedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_json", columnDefinition = "jsonb")
    private Map<String, Object> evidenceJson;

    @Builder.Default
    @Column(nullable = false)
    private boolean resolved = false;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_by_user_id")
    private User resolvedBy;

    @Column(name = "admin_notes", columnDefinition = "text")
    private String adminNotes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
