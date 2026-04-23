package com.slparcelauctions.backend.parcel;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
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

/**
 * SL parcel row. Shared across any number of auctions — parcels are not
 * 1:1 with drafts. The {@code verified} flag means "metadata was successfully
 * fetched from the SL World API at least once" (not an ownership claim).
 * Ownership lives per-auction on {@code auctions.verification_tier /
 * auctions.verified_at}. See spec §5.1.
 */
@Entity
@Table(name = "parcels")
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

    @Column(name = "owner_uuid")
    private UUID ownerUuid;

    @Column(name = "owner_type", length = 10)
    private String ownerType;   // "agent" or "group"

    @Column(name = "region_name", length = 100)
    private String regionName;

    @Column(name = "grid_x")
    private Double gridX;

    @Column(name = "grid_y")
    private Double gridY;

    @Column(name = "continent_name", length = 50)
    private String continentName;

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

    @Column(name = "maturity_rating", length = 10)
    private String maturityRating;  // "PG", "MATURE", "ADULT"

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
