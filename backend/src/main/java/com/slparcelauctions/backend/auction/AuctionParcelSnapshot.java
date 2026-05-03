package com.slparcelauctions.backend.auction;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.region.Region;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Per-auction snapshot of the parcel-shape SL data at the moment the
 * auction was listed (and refreshed in place on subsequent re-lookups by
 * the same seller). 1:1 with {@link Auction} — primary key IS the
 * auction's id (via @MapsId), no separate snapshot id.
 *
 * <p>Region is referenced by FK for global identity but the seller-visible
 * name + maturity are denormalized so a region rename in SL doesn't
 * retroactively change historical listings.
 */
@Entity
@Table(name = "auction_parcel_snapshots")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuctionParcelSnapshot {

    @Id
    @Column(name = "auction_id")
    private Long auctionId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "auction_id")
    private Auction auction;

    @Column(name = "sl_parcel_uuid", nullable = false)
    private UUID slParcelUuid;

    @Column(name = "owner_uuid")
    private UUID ownerUuid;

    @Column(name = "owner_type")
    private String ownerType;

    @Column(name = "owner_name")
    private String ownerName;

    @Column(name = "parcel_name")
    private String parcelName;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "region_id")
    private Region region;

    @Column(name = "region_name")
    private String regionName;

    @Column(name = "region_maturity_rating")
    private String regionMaturityRating;

    @Column(name = "area_sqm")
    private Integer areaSqm;

    @Column(name = "position_x")
    private Double positionX;

    @Column(name = "position_y")
    private Double positionY;

    @Column(name = "position_z")
    private Double positionZ;

    @Column(name = "slurl")
    private String slurl;

    @Column(name = "layout_map_url")
    private String layoutMapUrl;

    @Column(name = "layout_map_data", columnDefinition = "text")
    private String layoutMapData;

    @Column(name = "layout_map_at")
    private OffsetDateTime layoutMapAt;

    @Column(name = "verified_at")
    private OffsetDateTime verifiedAt;

    @Column(name = "last_checked")
    private OffsetDateTime lastChecked;
}
