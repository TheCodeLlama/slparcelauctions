package com.slparcelauctions.backend.auction.parcelscan;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.auction.Auction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Per-auction region-wide elevation raster (64x64 cells, 4m each, in meters).
 *
 * <p>Row-major from the SW corner. {@code cells} is 4096 uint8s. Decode:
 * <pre>elevationMeters = baseMeters + (cells[i] &amp; 0xFF) * stepMeters</pre>
 * {@code stepMeters} is auto-fitted at encode time so that {@code 255 * step}
 * covers the region's actual elevation range. ~4 KB + 8-byte header total.
 */
@Entity
@Table(name = "auction_parcel_height_maps")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class AuctionParcelHeightMap {

    @Id
    @Column(name = "auction_id")
    private Long id;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auction_id")
    private Auction auction;

    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    @Column(name = "grid_size", nullable = false)
    private Integer gridSize;

    @Column(name = "cell_size_meters", nullable = false)
    private Integer cellSizeMeters;

    @Column(name = "base_meters", nullable = false)
    private Float baseMeters;

    @Column(name = "step_meters", nullable = false)
    private Float stepMeters;

    @Column(name = "cells", nullable = false)
    private byte[] cells;

    @Column(name = "scanned_at", nullable = false)
    private OffsetDateTime scannedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (publicId == null) publicId = UUID.randomUUID();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
