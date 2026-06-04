package com.slparcelauctions.backend.promotion;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Config knobs for the PROMO-* promotion family. See spec
 * docs/superpowers/specs/2026-06-01-hq-featured-boards-design.md §2.1.
 */
@ConfigurationProperties(prefix = "slpa.promotions")
@Validated
public record PromotionConfigProperties(
        @Min(1) long featuredPriceLindens,
        @Min(1) @Max(13) int featuredSlotCount,
        @NotNull Duration featuredBoardCycle) {
}
