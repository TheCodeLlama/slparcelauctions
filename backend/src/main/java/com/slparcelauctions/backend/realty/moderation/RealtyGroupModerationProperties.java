package com.slparcelauctions.backend.realty.moderation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Configuration for the realty-group admin-moderation subsystem (sub-project F).
 * Bound to {@code slpa.realty.*}. See spec §20.2 for the deployable knobs.
 *
 * <p>Three groups of properties, one per scheduled task that this sub-project
 * introduces:
 * <ul>
 *   <li>{@code group-bulk-suspend} — controls
 *       {@code BulkSuspendedListingExpiryTask} (Task 13). Listings auto-cancel
 *       once they've been bulk-suspended for {@code autoCancelHours} hours.</li>
 *   <li>{@code sl-group} — controls the SL-group reverification cadence and
 *       failure threshold consumed by the reverify task (later slice).</li>
 *   <li>{@code group-suspension-expiry} — controls
 *       {@link com.slparcelauctions.backend.realty.moderation
 *       .GroupSuspensionExpiryTask GroupSuspensionExpiryTask} (Task 8), which
 *       sweeps timed suspensions whose {@code expires_at} has passed.</li>
 * </ul>
 *
 * <p>Each {@code enabled} flag is a kill-switch consumed by the corresponding
 * {@code @ConditionalOnProperty} on the scheduled task. Integration tests flip
 * them to {@code false} via {@code @TestPropertySource} consistent with the
 * codebase-wide scheduler-disable pattern.
 *
 * <p>Registered via {@link com.slparcelauctions.backend.realty.RealtyConfig
 * RealtyConfig} — the project uses {@code @EnableConfigurationProperties}
 * rather than {@code @ConfigurationPropertiesScan}.
 */
@ConfigurationProperties(prefix = "slpa.realty")
@Getter
@Setter
public class RealtyGroupModerationProperties {

    private GroupBulkSuspend groupBulkSuspend = new GroupBulkSuspend();
    private SlGroupReverify slGroup = new SlGroupReverify();
    private GroupSuspensionExpiry groupSuspensionExpiry = new GroupSuspensionExpiry();

    @Getter
    @Setter
    public static class GroupBulkSuspend {
        private int autoCancelHours = 48;
        private boolean enabled = true;
    }

    @Getter
    @Setter
    public static class SlGroupReverify {
        private int reverifyCadenceDays = 30;
        private int reverifyFetchFailureThreshold = 3;
        /**
         * Sub-project G §9.2 — per-tick cap on the number of due rows fetched
         * by {@link com.slparcelauctions.backend.realty.slgroup.SlGroupReverifyTask}.
         * Default {@link Integer#MAX_VALUE} (effectively unbounded, matching the
         * pre-G behaviour). Operators dial down via
         * {@code slpa.realty.sl-group.reverify-batch-size} + redeploy if a sweep
         * starts starving other transactions on the same connection pool.
         */
        @jakarta.validation.constraints.Min(1)
        private int reverifyBatchSize = Integer.MAX_VALUE;

        private Enabled reverify = new Enabled();

        @Getter
        @Setter
        public static class Enabled {
            private boolean enabled = true;
        }
    }

    @Getter
    @Setter
    public static class GroupSuspensionExpiry {
        private boolean enabled = true;
    }
}
