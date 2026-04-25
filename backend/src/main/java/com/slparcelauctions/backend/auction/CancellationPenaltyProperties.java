package com.slparcelauctions.backend.auction;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Configuration for the cancellation-penalty ladder (Epic 08 sub-spec 2 §2).
 * Bound to {@code slpa.cancellation.*}. The ladder fires only on cancellations
 * of {@code ACTIVE} auctions that already have at least one bid; the
 * {@code post-cancel-watch-hours} window arms the ownership watcher for the
 * same path.
 *
 * <p>Registered via {@code CancellationPenaltyConfig} —
 * the project uses {@code @EnableConfigurationProperties} rather than
 * {@code @ConfigurationPropertiesScan}.
 */
@ConfigurationProperties(prefix = "slpa.cancellation")
@Validated
public record CancellationPenaltyProperties(
        @NotNull @Valid Penalty penalty,
        @Min(1) int postCancelWatchHours) {

    public record Penalty(
            @Min(1) long secondOffenseL,
            @Min(1) long thirdOffenseL,
            @Min(1) int thirdOffenseSuspensionDays) {}
}
