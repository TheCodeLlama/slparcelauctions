package com.slparcelauctions.backend.escrow.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.slparcelauctions.backend.escrow.terminal.EscrowConfigProperties;

/**
 * Registers {@link EscrowConfigProperties} as a configuration-properties bean.
 * Mirrors the pattern used by
 * {@code com.slparcelauctions.backend.sl.SlPropertiesConfig} — the project
 * uses {@code @EnableConfigurationProperties} rather than
 * {@code @ConfigurationPropertiesScan}.
 */
@Configuration
@EnableConfigurationProperties(EscrowConfigProperties.class)
public class EscrowPropertiesConfig {
}
