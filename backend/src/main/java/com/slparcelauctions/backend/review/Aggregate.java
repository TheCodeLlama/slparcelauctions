package com.slparcelauctions.backend.review;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * JPQL projection record for AVG + COUNT over a reviewee's visible
 * reviews. The JPQL returns {@link Double} and {@link Long}; the
 * constructor normalises {@code avg} to a {@link BigDecimal} at scale
 * 2 with {@link RoundingMode#HALF_UP} so the shape matches the
 * {@code precision(3, scale=2)} columns on {@code User.avgSellerRating}
 * / {@code avgBuyerRating}.
 *
 * <p>Task 1 defines the projection shape; Task 2 wires the repository
 * queries that return it and the aggregate-recompute path that consumes
 * it.
 */
public record Aggregate(BigDecimal avg, int count) {

    public Aggregate(Double avgRaw, Long countRaw) {
        this(
                avgRaw == null
                        ? null
                        : BigDecimal.valueOf(avgRaw).setScale(2, RoundingMode.HALF_UP),
                countRaw == null ? 0 : countRaw.intValue()
        );
    }
}
