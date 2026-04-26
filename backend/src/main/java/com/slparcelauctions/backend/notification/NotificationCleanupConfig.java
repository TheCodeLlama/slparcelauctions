package com.slparcelauctions.backend.notification;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link NotificationCleanupProperties} as a configuration-properties
 * bean. Mirrors the pattern used by
 * {@code com.slparcelauctions.backend.auction.config.CancellationPenaltyConfig} —
 * the project uses {@code @EnableConfigurationProperties} rather than
 * {@code @ConfigurationPropertiesScan}.
 */
@Configuration
@EnableConfigurationProperties(NotificationCleanupProperties.class)
public class NotificationCleanupConfig {
}
