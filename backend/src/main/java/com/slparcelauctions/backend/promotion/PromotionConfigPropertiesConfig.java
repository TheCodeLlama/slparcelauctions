package com.slparcelauctions.backend.promotion;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link PromotionConfigProperties} as a configuration-properties bean.
 * Mirrors {@code AuctionConfigPropertiesConfig} - the project uses
 * {@code @EnableConfigurationProperties} rather than
 * {@code @ConfigurationPropertiesScan}.
 */
@Configuration
@EnableConfigurationProperties(PromotionConfigProperties.class)
public class PromotionConfigPropertiesConfig {
}
