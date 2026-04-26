package com.slparcelauctions.backend.notification.slim.internal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "slpa.notifications.sl-im.dispatcher")
@Validated
public record SlImInternalProperties(
    @NotBlank String sharedSecret,
    @Positive @Max(50) int maxBatchLimit
) {}
