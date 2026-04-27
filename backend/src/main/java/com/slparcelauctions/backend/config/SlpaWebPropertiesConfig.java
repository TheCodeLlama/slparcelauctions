package com.slparcelauctions.backend.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link SlpaWebProperties} as a configuration-properties bean.
 * Mirrors the pattern used by {@code SlPropertiesConfig} and
 * {@code NotificationCleanupConfig} — the project uses
 * {@code @EnableConfigurationProperties} rather than
 * {@code @ConfigurationPropertiesScan}.
 */
@Configuration
@EnableConfigurationProperties(SlpaWebProperties.class)
public class SlpaWebPropertiesConfig {
}
