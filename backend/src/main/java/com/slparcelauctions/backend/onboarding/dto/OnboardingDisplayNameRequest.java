package com.slparcelauctions.backend.onboarding.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/users/me/onboarding/display-name}.
 *
 * <p>Onboarding has explicit "skip" semantics: {@code null}, empty string,
 * and whitespace-only values all flip the {@code display_name_step_completed}
 * flag without writing to the {@code display_name} column. Only non-blank
 * values are persisted. Trim + emptiness logic lives in
 * {@code OnboardingService.setDisplayName} — the DTO only enforces the
 * 50-char ceiling.
 *
 * <p>The stricter "must be 1–50 chars, no padded whitespace" contract on
 * {@code UpdateUserRequest} (used by {@code PUT /api/v1/users/me}) is
 * intentionally retained for post-onboarding edits.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = false)} mirrors the
 * existing posture on {@code UpdateUserRequest}; the global
 * {@code spring.jackson.deserialization.fail-on-unknown-properties: true}
 * is the load-bearing layer.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record OnboardingDisplayNameRequest(
        @Size(max = 50, message = "displayName must be at most 50 characters")
        String displayName) {}
