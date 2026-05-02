package com.slparcelauctions.backend.testsupport;

import java.util.UUID;

import com.slparcelauctions.backend.region.Region;

/**
 * Test-only factory for {@link Region} fixtures. Use {@link #mainland()} when
 * a parcel under test just needs <em>some</em> Mainland-shaped region for the
 * FK to be satisfied; pass a name + coords explicitly when the test depends
 * on those specific values.
 *
 * <p>Default coordinates land inside the Sansara continent box
 * ({@code 1014, 1014} → world meters {@code 259584, 259584}).
 */
public final class TestRegions {

    private TestRegions() {
    }

    public static Region mainland() {
        return Region.builder()
                .slUuid(UUID.randomUUID())
                .name("TestRegion-" + UUID.randomUUID().toString().substring(0, 8))
                .gridX(1014.0)
                .gridY(1014.0)
                .maturityRating("GENERAL")
                .build();
    }

    public static Region named(String name) {
        return Region.builder()
                .slUuid(UUID.randomUUID())
                .name(name)
                .gridX(1014.0)
                .gridY(1014.0)
                .maturityRating("GENERAL")
                .build();
    }

    public static Region of(String name, String maturity, double gridX, double gridY) {
        return Region.builder()
                .slUuid(UUID.randomUUID())
                .name(name)
                .gridX(gridX)
                .gridY(gridY)
                .maturityRating(maturity)
                .build();
    }
}
