package com.slparcelauctions.backend.realty;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.slparcelauctions.backend.realty.moderation.RealtyGroupModerationProperties;

/**
 * Registers realty-package {@code @ConfigurationProperties} beans. Currently
 * carries {@link RealtyGroupModerationProperties} (sub-project F). Mirrors the
 * pattern used by
 * {@code com.slparcelauctions.backend.auction.config.CancellationPenaltyConfig}
 * — the project uses {@code @EnableConfigurationProperties} rather than
 * {@code @ConfigurationPropertiesScan}.
 */
@Configuration
@EnableConfigurationProperties(RealtyGroupModerationProperties.class)
public class RealtyConfig {
}
