package com.slparcelauctions.backend.auction.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.monitoring.config.OwnershipMonitorProperties;

/**
 * Unit coverage for {@link OwnershipCheckTimestampInitializer}. Verifies the
 * jitter window bounds and that the stamp is always in the past (not the
 * future, not the exact same instant on every call — that would defeat the
 * load-spreading purpose).
 */
class OwnershipCheckTimestampInitializerTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-04-16T12:00:00Z");
    private static final Clock FIXED = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    private static final OffsetDateTime NOW = OffsetDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);

    @Test
    void onActivated_setsTimestampWithinJitterWindow_andHitsMultipleValues() {
        OwnershipMonitorProperties props = new OwnershipMonitorProperties();
        props.setJitterMaxMinutes(5);
        OwnershipCheckTimestampInitializer init =
                new OwnershipCheckTimestampInitializer(props, FIXED);

        Set<Long> observedOffsetMinutes = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            Auction a = new Auction();
            init.onActivated(a);
            assertThat(a.getLastOwnershipCheckAt()).isNotNull();
            Duration offset = Duration.between(a.getLastOwnershipCheckAt(), NOW);
            long minutes = offset.toMinutes();
            // stamp is at-or-before NOW (offset >= 0) and within [0, 5] minutes.
            assertThat(minutes).isBetween(0L, 5L);
            assertThat(offset.isNegative()).isFalse();
            observedOffsetMinutes.add(minutes);
        }
        // With 200 iterations across a 6-value range (0..5 inclusive), we
        // expect to observe more than one distinct value. A single value
        // would mean the jitter isn't actually randomizing.
        assertThat(observedOffsetMinutes).hasSizeGreaterThan(1);
    }

    @Test
    void onActivated_zeroJitter_stampsExactlyNow() {
        OwnershipMonitorProperties props = new OwnershipMonitorProperties();
        props.setJitterMaxMinutes(0);
        OwnershipCheckTimestampInitializer init =
                new OwnershipCheckTimestampInitializer(props, FIXED);

        Auction a = new Auction();
        init.onActivated(a);

        assertThat(a.getLastOwnershipCheckAt()).isEqualTo(NOW);
    }

    @Test
    void onActivated_negativeJitter_treatedAsZero() {
        // Defensive: misconfigured jitterMaxMinutes shouldn't blow up at
        // runtime. <=0 collapses to "no jitter" and stamps exactly NOW.
        OwnershipMonitorProperties props = new OwnershipMonitorProperties();
        props.setJitterMaxMinutes(-10);
        OwnershipCheckTimestampInitializer init =
                new OwnershipCheckTimestampInitializer(props, FIXED);

        Auction a = new Auction();
        init.onActivated(a);

        assertThat(a.getLastOwnershipCheckAt()).isEqualTo(NOW);
    }
}
