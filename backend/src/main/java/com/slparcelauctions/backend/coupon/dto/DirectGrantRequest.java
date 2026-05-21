package com.slparcelauctions.backend.coupon.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;

/**
 * Admin payload for direct-granting an existing coupon to one or more
 * users (no user-typed code involved). Service treats it as idempotent:
 * users who already hold a grant at the coupon's {@code maxPerUser}
 * ceiling are skipped silently.
 */
public record DirectGrantRequest(@NotEmpty List<UUID> userPublicIds) {}
