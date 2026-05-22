package com.slparcelauctions.backend.review;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;

/**
 * Configuration for the blind-review subsystem (Epic 08 sub-spec 1). Bound to
 * {@code slpa.review.*}.
 *
 * <p>{@code windowDays} is the SINGLE definition of the blind-review business
 * window. {@link ReviewService} uses it as the submission cutoff (a review
 * submitted strictly after {@code escrow.completedAt + windowDays} is rejected)
 * and {@link BlindReviewRevealTask} uses it as the reveal-eligibility threshold.
 * These are the same business window viewed from two sides and MUST stay equal,
 * so both beans read this one key.
 *
 * <p>{@code revealBatchLimit} caps how many pending reviews the hourly reveal
 * scheduler processes per tick.
 *
 * <p>Registered via {@link ReviewConfig} -- the project uses
 * {@code @EnableConfigurationProperties} rather than
 * {@code @ConfigurationPropertiesScan}.
 */
@ConfigurationProperties(prefix = "slpa.review")
@Validated
public record ReviewProperties(
        @Min(1) int windowDays,
        @Min(1) int revealBatchLimit) {
}
