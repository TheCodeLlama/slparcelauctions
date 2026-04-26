package com.slparcelauctions.backend.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "slpa.web")
@Validated
public record SlpaWebProperties(
    @NotBlank String baseUrl
) {}
