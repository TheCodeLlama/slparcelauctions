package com.slparcelauctions.backend.common;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces {@link Clock} with a mutable fixed instance for deadline-
 * sensitive integration tests. Import via
 * {@code @Import(ClockOverrideConfig.class)}; inject
 * {@link MutableFixedClock} to advance inside a test body.
 *
 * <p>Differs from {@link Clock#fixed} in that the instant is {@code volatile}
 * and mutable — tests can step the clock through deadline boundaries
 * without rebuilding the Spring context. Zone is pinned to UTC to match
 * backend conventions; calls to {@link #withZone} return {@code this} so
 * callers can't accidentally fork the zone.
 */
@TestConfiguration
public class ClockOverrideConfig {

    public static final Instant DEFAULT_INSTANT = Instant.parse("2026-05-01T12:00:00Z");

    @Bean
    @Primary
    public MutableFixedClock testClock() {
        return new MutableFixedClock(DEFAULT_INSTANT);
    }

    public static final class MutableFixedClock extends Clock {
        private volatile Instant instant;

        public MutableFixedClock(Instant initial) {
            this.instant = initial;
        }

        public void set(Instant next) {
            this.instant = next;
        }

        public void advance(java.time.Duration d) {
            this.instant = this.instant.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
