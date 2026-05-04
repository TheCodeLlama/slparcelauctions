package com.slparcelauctions.backend.region;

import java.util.UUID;

import com.slparcelauctions.backend.common.BaseMutableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * SL region row. Persisted on first sight (or refreshed in place) every time
 * a parcel inside the region is looked up; parcels FK into it. {@code slUuid}
 * is the immutable natural key — names can in principle be renamed, UUIDs
 * never change.
 *
 * <p>Grid coordinates stored in <em>region units</em> (1 unit = 1 region =
 * 256m), matching what {@code world.secondlife.com/region/{uuid}} exposes.
 * The Mainland check in {@link RegionService} multiplies by 256 before
 * consulting {@code MainlandContinents}, which is keyed on world meters.
 */
@Entity
@Table(name = "regions",
        indexes = {
            @Index(name = "ix_regions_grid_coords", columnList = "grid_x, grid_y"),
            @Index(name = "ix_regions_maturity", columnList = "maturity_rating")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Region extends BaseMutableEntity {

    @Column(name = "sl_uuid", nullable = false, unique = true)
    private UUID slUuid;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(name = "grid_x", nullable = false)
    private Double gridX;

    @Column(name = "grid_y", nullable = false)
    private Double gridY;

    @Column(name = "maturity_rating", nullable = false, length = 10)
    private String maturityRating;
}
