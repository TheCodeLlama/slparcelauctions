package com.slparcelauctions.backend.parcel;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.slparcelauctions.backend.region.Region;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * SL parcel row. Shared across any number of auctions — parcels are not
 * 1:1 with drafts. The {@code verified} flag means "metadata was successfully
 * fetched from the SL World API at least once" (not an ownership claim).
 * Ownership lives per-auction on {@code auctions.verification_tier /
 * auctions.verified_at}. See spec §5.1.
 */
@Entity
@Table(name = "parcels",
        indexes = {
            @Index(name = "ix_parcels_region", columnList = "region_id"),
            @Index(name = "ix_parcels_area_sqm", columnList = "area_sqm")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Parcel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sl_parcel_uuid", nullable = false, unique = true)
    private UUID slParcelUuid;

    // EAGER + JOIN fetch: callers all but invariably read at least one region
    // field when they touch the parcel. Hibernate's ManyToOne EAGER alone
    // still hands back a bytecode proxy that needs an open session to
    // initialize — @Fetch(JOIN) forces a JOIN at query time so the region is
    // a real entity by the time we return, safe to access after the session
    // closes (DTO mappers run after the service's @Transactional ends).
    // Cascade PERSIST so test fixtures that build a Parcel with an in-memory
    // Region don't trip the transient-FK assertion on flush.
    @ManyToOne(fetch = FetchType.EAGER, optional = false, cascade = CascadeType.PERSIST)
    @org.hibernate.annotations.Fetch(org.hibernate.annotations.FetchMode.JOIN)
    @JoinColumn(name = "region_id", nullable = false)
    private Region region;

    @Column(name = "owner_uuid")
    private UUID ownerUuid;

    @Column(name = "owner_type", length = 10)
    private String ownerType;   // "agent" or "group"

    @Column(name = "owner_name", length = 255)
    private String ownerName;   // SL display name; null/blank when ownertype=group

    @Column(name = "parcel_name", length = 255)
    // SL-side parcel display name (from the parcel page's <meta name="parcel">).
    // Used as the default listing title when a seller first picks this parcel
    // in the create-listing wizard.
    private String parcelName;

    @Column(name = "area_sqm")
    private Integer areaSqm;

    @Column(name = "position_x")
    private Double positionX;

    @Column(name = "position_y")
    private Double positionY;

    @Column(name = "position_z")
    private Double positionZ;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "snapshot_url", columnDefinition = "text")
    private String snapshotUrl;

    @Column(name = "layout_map_url", columnDefinition = "text")
    private String layoutMapUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "layout_map_data", columnDefinition = "jsonb")
    private Map<String, Object> layoutMapData;

    @Column(name = "layout_map_at")
    private OffsetDateTime layoutMapAt;

    @Column(length = 100)
    private String location;

    @Column(columnDefinition = "text")
    private String slurl;

    @Builder.Default
    @Column(nullable = false)
    private Boolean verified = false;

    @Column(name = "verified_at")
    private OffsetDateTime verifiedAt;

    @Column(name = "last_checked")
    private OffsetDateTime lastChecked;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
