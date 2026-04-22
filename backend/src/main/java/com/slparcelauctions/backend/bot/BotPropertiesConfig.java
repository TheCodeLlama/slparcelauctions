package com.slparcelauctions.backend.bot;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link BotTaskConfigProperties} as a configuration-properties bean.
 * Mirrors the pattern used by
 * {@code com.slparcelauctions.backend.escrow.config.EscrowPropertiesConfig} —
 * the project uses {@code @EnableConfigurationProperties} rather than
 * {@code @ConfigurationPropertiesScan}.
 */
@Configuration
@EnableConfigurationProperties(BotTaskConfigProperties.class)
public class BotPropertiesConfig {
}
