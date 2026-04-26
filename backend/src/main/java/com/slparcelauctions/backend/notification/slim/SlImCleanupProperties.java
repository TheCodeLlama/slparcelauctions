package com.slparcelauctions.backend.notification.slim;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "slpa.notifications.sl-im.cleanup")
@Validated
public record SlImCleanupProperties(
    boolean enabled,
    @NotBlank String cron,
    @Positive int expiryAfterHours,
    @Positive int retentionAfterDays,
    @Positive int batchSize,
    @Min(0) @Max(20) int topUsersInLog
) {}
