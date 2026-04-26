package com.slparcelauctions.backend.notification;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "slpa.notifications.cleanup")
@Validated
public record NotificationCleanupProperties(
    boolean enabled,
    @NotBlank String cron,
    @Positive int retentionDays,
    @Positive int batchSize
) {}
