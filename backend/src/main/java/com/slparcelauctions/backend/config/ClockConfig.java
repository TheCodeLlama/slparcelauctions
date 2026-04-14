package com.slparcelauctions.backend.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides a {@link Clock} bean so services can inject the clock for test determinism.
 * Production wires {@code Clock.systemUTC()}. Tests override with {@code Clock.fixed(...)}
 * via {@code @TestConfiguration} or direct substitution in unit tests.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
