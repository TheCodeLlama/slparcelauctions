package com.slparcelauctions.backend.notification.slim.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link SlImInternalProperties} as a configuration-properties bean
 * and exposes {@link SlImTerminalAuthFilter} as a named {@code @Bean}.
 *
 * <p>{@link SlImTerminalAuthFilter} is NOT annotated with {@code @Component} —
 * manual registration here prevents {@code @WebMvcTest} slice tests from
 * auto-detecting it and failing on a missing {@code SlImInternalProperties} bean.
 *
 * <p>The project uses {@code @EnableConfigurationProperties} rather than
 * {@code @ConfigurationPropertiesScan} — mirrors the pattern from
 * {@code NotificationCleanupConfig} and {@code SlpaWebPropertiesConfig}.
 */
@Configuration
@EnableConfigurationProperties(SlImInternalProperties.class)
public class SlImInternalConfig {

    @Bean
    public SlImTerminalAuthFilter slImTerminalAuthFilter(SlImInternalProperties props) {
        return new SlImTerminalAuthFilter(props);
    }
}
