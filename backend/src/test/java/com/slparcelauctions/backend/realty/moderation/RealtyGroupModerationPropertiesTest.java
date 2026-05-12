package com.slparcelauctions.backend.realty.moderation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Verifies {@link RealtyGroupModerationProperties} binds defaults from
 * {@code application.yml} and accepts overrides via {@code @TestPropertySource}.
 *
 * <p>Two nested test classes: one for the defaults path (no overrides), one
 * for the override path (every knob flipped). Both run as {@code @SpringBootTest}
 * with the {@code dev} profile + the standard scheduler-disable cohort so the
 * context starts cleanly without spinning up background jobs.
 */
class RealtyGroupModerationPropertiesTest {

    @Nested
    @SpringBootTest
    @ActiveProfiles("dev")
    @TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.auction-end.enabled=false",
        "slpa.ownership-monitor.enabled=false",
        "slpa.escrow.ownership-monitor-job.enabled=false",
        "slpa.escrow.timeout-job.enabled=false",
        "slpa.escrow.command-dispatcher-job.enabled=false",
        "slpa.escrow.listing-fee-refund-job.enabled=false",
        "slpa.review.scheduler.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false",
        "slpa.realty.invitation-expiry.enabled=false"
    })
    class Defaults {

        @Autowired
        RealtyGroupModerationProperties props;

        @Test
        void bindsApplicationYmlDefaults() {
            assertThat(props.getGroupBulkSuspend().getAutoCancelHours()).isEqualTo(48);
            assertThat(props.getGroupBulkSuspend().isEnabled()).isTrue();

            assertThat(props.getSlGroup().getReverifyCadenceDays()).isEqualTo(30);
            assertThat(props.getSlGroup().getReverifyFetchFailureThreshold()).isEqualTo(3);
            assertThat(props.getSlGroup().getReverify().isEnabled()).isTrue();

            assertThat(props.getGroupSuspensionExpiry().isEnabled()).isTrue();
        }
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles("dev")
    @TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.auction-end.enabled=false",
        "slpa.ownership-monitor.enabled=false",
        "slpa.escrow.ownership-monitor-job.enabled=false",
        "slpa.escrow.timeout-job.enabled=false",
        "slpa.escrow.command-dispatcher-job.enabled=false",
        "slpa.escrow.listing-fee-refund-job.enabled=false",
        "slpa.review.scheduler.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false",
        "slpa.realty.invitation-expiry.enabled=false",
        // Overrides under test:
        "slpa.realty.group-bulk-suspend.auto-cancel-hours=72",
        "slpa.realty.group-bulk-suspend.enabled=false",
        "slpa.realty.sl-group.reverify-cadence-days=14",
        "slpa.realty.sl-group.reverify-fetch-failure-threshold=5",
        "slpa.realty.sl-group.reverify.enabled=false",
        "slpa.realty.group-suspension-expiry.enabled=false"
    })
    class Overrides {

        @Autowired
        RealtyGroupModerationProperties props;

        @Test
        void appliesPerKeyOverrides() {
            assertThat(props.getGroupBulkSuspend().getAutoCancelHours()).isEqualTo(72);
            assertThat(props.getGroupBulkSuspend().isEnabled()).isFalse();

            assertThat(props.getSlGroup().getReverifyCadenceDays()).isEqualTo(14);
            assertThat(props.getSlGroup().getReverifyFetchFailureThreshold()).isEqualTo(5);
            assertThat(props.getSlGroup().getReverify().isEnabled()).isFalse();

            assertThat(props.getGroupSuspensionExpiry().isEnabled()).isFalse();
        }
    }
}
