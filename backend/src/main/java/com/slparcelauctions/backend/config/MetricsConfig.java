package com.slparcelauctions.backend.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Provides a fallback {@link MeterRegistry} for components that need to
 * record latency / counters when the full actuator starter is not on the
 * classpath. Backed by an in-memory {@link SimpleMeterRegistry}, which
 * is sufficient for dev / test and gives downstream components a real
 * bean to time against.
 *
 * <p>Annotated with {@link ConditionalOnMissingBean} so adding the
 * actuator starter later (which auto-configures a registry — typically
 * Prometheus) takes precedence without code changes here.
 */
@Configuration
public class MetricsConfig {

    @Bean
    @ConditionalOnMissingBean(MeterRegistry.class)
    public MeterRegistry simpleMeterRegistry() {
        return new SimpleMeterRegistry();
    }
}
