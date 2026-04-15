package com.slparcelauctions.backend.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code PUT /api/v1/users/me}. Both fields are optional —
 * null means "do not touch this column." An empty-string {@code bio}
 * explicitly clears it.
 *
 * <p><strong>Unknown-field rejection is enforced globally</strong> via
 * {@code spring.jackson.deserialization.fail-on-unknown-properties: true}
 * in {@code application.yml}. That flag is the load-bearing layer — removing
 * it would open a privilege-escalation hole where a client could send
 * {@code {"email": "...", "role": "admin"}} and have Jackson silently drop
 * the extras. The security canary test
 * {@code UserControllerUpdateMeSliceTest.put_me_rejectsUnknownFields_returns400}
 * enforces that layer.
 *
 * <p>The {@code @JsonIgnoreProperties(ignoreUnknown = false)} annotation on
 * this record is documentation-only under Jackson 3 / Spring Boot 4 — the
 * runtime behavior comes from the global flag, not this annotation.
 *
 * <p><strong>{@code @Size(min=1)} null-passthrough semantics:</strong> Jakarta
 * Bean Validation's {@code @Size} does not fire when the value is null. So
 * {@code {"displayName": null}} passes validation (service skips the column),
 * while {@code {"displayName": ""}} fails (empty string has
 * {@code size() == 0}, below min).
 *
 * <p><strong>Whitespace-only displayName:</strong> {@code @Size(min=1)} accepts
 * {@code " "} (a single space is 1 char), which is nonsensical for a display
 * name. The {@code @Pattern} below rejects blank/whitespace-only values while
 * still allowing multi-word names like {@code "Alice Resident"}.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record UpdateUserRequest(
        @Size(min = 1, max = 50, message = "displayName must be 1-50 characters")
        @Pattern(
                regexp = "^\\S+(?:\\s+\\S+)*$",
                message = "displayName must not be blank or padded with whitespace")
        String displayName,
        @Size(max = 500, message = "bio must be at most 500 characters") String bio
) {}
