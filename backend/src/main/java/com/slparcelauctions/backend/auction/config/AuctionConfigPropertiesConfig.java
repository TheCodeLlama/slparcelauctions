package com.slparcelauctions.backend.auction.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.slparcelauctions.backend.auction.AuctionConfigProperties;

/**
 * Registers {@link AuctionConfigProperties} as a configuration-properties bean.
 * Mirrors {@link CancellationPenaltyConfig} - the project uses
 * {@code @EnableConfigurationProperties} rather than
 * {@code @ConfigurationPropertiesScan}.
 */
@Configuration
@EnableConfigurationProperties(AuctionConfigProperties.class)
public class AuctionConfigPropertiesConfig {
}
