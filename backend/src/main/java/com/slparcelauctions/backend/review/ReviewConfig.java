package com.slparcelauctions.backend.review;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link ReviewProperties} as a configuration-properties bean. Mirrors
 * the pattern used by
 * {@code com.slparcelauctions.backend.auction.config.CancellationPenaltyConfig}
 * -- the project uses {@code @EnableConfigurationProperties} rather than
 * {@code @ConfigurationPropertiesScan}.
 */
@Configuration
@EnableConfigurationProperties(ReviewProperties.class)
public class ReviewConfig {
}
