package com.slparcelauctions.backend.realty.moderation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Configuration for the realty-group subsystem. Bound to {@code slpa.realty.*}.
 * See spec §20.2 for the admin-moderation deployable knobs.
 *
 * <p>Also carries three non-moderation realty knobs that share the
 * {@code slpa.realty} prefix: {@code defaultMemberSeatLimit} (the per-group
 * seat cap stamped on newly created groups), {@code invitationTtlDays} (the
 * realty-group invitation lifetime), and {@code slGroup.verificationTtlDays}
 * (the SL-group registration verification-code TTL). They live on this POJO
 * rather than a second {@code slpa.realty}-prefixed bean because Spring Boot
 * binds one prefix to one {@code @ConfigurationProperties} type.
 *
 * <p>Three groups of properties, one per scheduled task that the moderation
 * sub-project introduces:
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

    /**
     * Default per-group member seat cap stamped on a newly created
     * {@link com.slparcelauctions.backend.realty.RealtyGroup}. The entity
     * column stays per-group (an admin can raise an individual group's cap);
     * this is only the baseline {@code RealtyGroupService.createGroup} applies.
     */
    @jakarta.validation.constraints.Min(1)
    private int defaultMemberSeatLimit = 50;

    /**
     * Realty-group invitation lifetime before auto-expiry, in days (spec §3.3).
     * Consumed by {@code RealtyGroupInvitationService}.
     */
    @jakarta.validation.constraints.Min(1)
    private int invitationTtlDays = 7;

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

        /**
         * SL-group registration verification-code TTL, in days. The code
         * printed by the founder terminal is valid for this many days before
         * the pending registration must be re-issued. Consumed by
         * {@code RealtyGroupSlGroupService}.
         */
        @jakarta.validation.constraints.Min(1)
        private int verificationTtlDays = 7;

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
