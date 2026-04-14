package com.slparcelauctions.backend.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code PUT /api/v1/users/me}. Both fields are optional — null
 * means "do not touch this column." An empty-string {@code bio} explicitly clears it.
 *
 * <p><strong>{@code ignoreUnknown = false} is load-bearing.</strong> It rejects
 * any extra field a client tries to sneak in ({@code email}, {@code role},
 * {@code verified}, etc), guarding against privilege escalation via field
 * injection. Do not remove this annotation. The security canary test
 * {@code UserControllerUpdateMeSliceTest.put_me_rejectsUnknownFields_returns400}
 * enforces this rule.
 *
 * <p><strong>{@code @Size(min=1)} null-passthrough semantics:</strong> Jakarta
 * Bean Validation's {@code @Size} does not fire when the value is null. So
 * {@code {"displayName": null}} passes validation (the service then skips the
 * column), while {@code {"displayName": ""}} fails (empty string has
 * {@code size() == 0}, below min).
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record UpdateUserRequest(
        @Size(min = 1, max = 50, message = "displayName must be 1-50 characters") String displayName,
        @Size(max = 500, message = "bio must be at most 500 characters") String bio
) {}
