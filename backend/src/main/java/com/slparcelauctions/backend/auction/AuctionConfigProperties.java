package com.slparcelauctions.backend.auction;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Configuration for auction browse / search / dashboard tunables. Bound to
 * {@code slpa.auction.*}.
 *
 * <p>Registered via {@code AuctionConfigPropertiesConfig} - the project uses
 * {@code @EnableConfigurationProperties} rather than
 * {@code @ConfigurationPropertiesScan}.
 *
 * <p>{@code searchDefaultPageSize} mirrors the {@code @RequestParam(defaultValue)}
 * literal on {@code AuctionSearchController} / {@code SavedAuctionController};
 * those annotations need a compile-time constant so the literal cannot be
 * replaced, but the value is exposed here for any non-annotation reader and
 * must stay aligned with the controller default.
 */
@ConfigurationProperties(prefix = "slpa.auction")
@Validated
public record AuctionConfigProperties(
        @Min(1) int savedAuctionsCap,
        @Min(1) int searchMaxPageSize,
        @Min(1) int searchMaxDistance,
        @Min(1) int searchDefaultDistance,
        @Min(1) int searchDefaultPageSize,
        @Min(1) int cancellationStatusMaxPage,
        @Min(1) int myBidsDefaultPageSize,
        @Min(1) int searchSuggestListingsLimit,
        @Min(1) int searchSuggestRegionsLimit,
        @Min(1) int searchSuggestResolvableRegionsLimit,
        @NotNull Duration snapshotFetchTimeout,
        @NotNull Duration featuredCacheTtl,
        @NotNull Duration searchCacheTtl,
        @Min(1) long featuredPriceLindens,
        @Min(1) @Max(13) int featuredSlotCount,
        @Min(1) int featuredBoardCycleSeconds) {
}
