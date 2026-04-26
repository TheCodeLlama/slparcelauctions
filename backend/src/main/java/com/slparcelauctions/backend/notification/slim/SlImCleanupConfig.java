package com.slparcelauctions.backend.notification.slim;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link SlImCleanupProperties} as a configuration-properties bean.
 *
 * <p>The project uses {@code @EnableConfigurationProperties} rather than
 * {@code @ConfigurationPropertiesScan} — mirrors the pattern from
 * {@code SlImInternalConfig}, {@code NotificationCleanupConfig}, and
 * {@code SlpaWebPropertiesConfig}.
 */
@Configuration
@EnableConfigurationProperties(SlImCleanupProperties.class)
public class SlImCleanupConfig {
}
