package com.slparcelauctions.backend.realty;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.slparcelauctions.backend.realty.moderation.RealtyGroupModerationProperties;
import com.slparcelauctions.backend.realty.reports.ReportsProperties;
import com.slparcelauctions.backend.realty.wallet.admin.AdminWalletAdjustProperties;

/**
 * Registers realty-package {@code @ConfigurationProperties} beans. Carries
 * {@link RealtyGroupModerationProperties} (sub-project F),
 * {@link AdminWalletAdjustProperties} (sub-project G section 7.2), and
 * {@link ReportsProperties} (sub-project G section 12, the group-report
 * threshold knob). Mirrors the pattern used by
 * {@code com.slparcelauctions.backend.auction.config.CancellationPenaltyConfig}
 * -- the project uses {@code @EnableConfigurationProperties} rather than
 * {@code @ConfigurationPropertiesScan}.
 */
@Configuration
@EnableConfigurationProperties({
        RealtyGroupModerationProperties.class,
        AdminWalletAdjustProperties.class,
        ReportsProperties.class})
public class RealtyConfig {
}
