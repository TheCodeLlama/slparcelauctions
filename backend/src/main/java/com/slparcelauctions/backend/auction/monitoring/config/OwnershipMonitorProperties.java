package com.slparcelauctions.backend.auction.monitoring.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;
import lombok.Setter;

/**
 * Configuration for the ownership-monitoring subsystem. Bound to
 * {@code slpa.ownership-monitor.*}. See spec §8.2 for the polling rules and
 * §8.8 for the World API failure-threshold semantics.
 *
 * <ul>
 *   <li>{@code enabled} -- master kill-switch for the monitor scheduler.</li>
 *   <li>{@code checkIntervalMinutes} -- target cadence between checks for a
 *       given auction.</li>
 *   <li>{@code schedulerFrequency} -- how often the scheduler wakes to pick
 *       due auctions.</li>
 *   <li>{@code jitterMaxMinutes} -- random jitter added to the per-auction
 *       check interval to spread load.</li>
 *   <li>{@code mismatchStreakThreshold} -- count of consecutive World API
 *       owner mismatches required before an ACTIVE auction is suspended.
 *       Defaults to 2 so a single transient World API result cannot tip a
 *       live listing into SUSPENDED. Does NOT apply to the post-cancel
 *       watcher path -- that flow is a one-shot forensic probe and flags
 *       on the first observed mismatch.</li>
 * </ul>
 */
@Configuration
@ConfigurationProperties(prefix = "slpa.ownership-monitor")
@Getter
@Setter
public class OwnershipMonitorProperties {

    private boolean enabled = true;
    private int checkIntervalMinutes = 30;
    private Duration schedulerFrequency = Duration.ofSeconds(30);
    private int jitterMaxMinutes = 5;
    private int mismatchStreakThreshold = 2;
}
