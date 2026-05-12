package com.slparcelauctions.backend.realty.reports;

import org.springframework.boot.context.properties.ConfigurationProperties;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

/**
 * Configuration for the user-submitted reports subsystem. Bound to
 * {@code slpa.reports.*}. Lives outside {@code slpa.realty.*} because the
 * report cap and threshold also govern listing-side reports.
 *
 * <p>{@code groupAlertThreshold} is the number of open user-submitted reports
 * a realty group can accumulate before the admin fan-out notification fires
 * ({@link com.slparcelauctions.backend.notification.NotificationCategory#GROUP_REPORT_THRESHOLD_REACHED}).
 * One-shot per cycle: see spec section 12.3. Default {@code 3}; minimum {@code 1}.
 *
 * <p>Registered via {@link com.slparcelauctions.backend.realty.RealtyConfig
 * RealtyConfig} -- the project uses {@code @EnableConfigurationProperties}
 * rather than {@code @ConfigurationPropertiesScan}.
 */
@ConfigurationProperties(prefix = "slpa.reports")
@Getter
@Setter
public class ReportsProperties {

    @Min(1)
    private int groupAlertThreshold = 3;
}
