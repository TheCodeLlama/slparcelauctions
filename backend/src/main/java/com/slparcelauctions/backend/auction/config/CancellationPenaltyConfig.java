package com.slparcelauctions.backend.auction.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.slparcelauctions.backend.auction.CancellationPenaltyProperties;

/**
 * Registers {@link CancellationPenaltyProperties} as a configuration-properties
 * bean. Mirrors the pattern used by
 * {@code com.slparcelauctions.backend.escrow.config.EscrowPropertiesConfig} —
 * the project uses {@code @EnableConfigurationProperties} rather than
 * {@code @ConfigurationPropertiesScan}.
 */
@Configuration
@EnableConfigurationProperties(CancellationPenaltyProperties.class)
public class CancellationPenaltyConfig {
}
