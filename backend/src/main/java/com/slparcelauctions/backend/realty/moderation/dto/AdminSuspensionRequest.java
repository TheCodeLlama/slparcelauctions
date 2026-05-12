package com.slparcelauctions.backend.realty.moderation.dto;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.realty.moderation.SuspensionReason;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/admin/realty-groups/{publicId}/suspensions}.
 *
 * <p>A null {@code expiresAt} means a permanent ban; a non-null value must be in the
 * future at request time (Bean Validation {@code @Future} enforces this). The
 * {@code bulkSuspendListings} flag opts into the cascading bulk listing suspend
 * flow (implemented by {@code BulkListingSuspendService} in Task 11).
 *
 * <p>Sub-project F spec §6.2, §9.
 */
public record AdminSuspensionRequest(
    @NotNull SuspensionReason reason,
    @NotBlank @Size(max = 1000) String notes,
    @Future OffsetDateTime expiresAt,
    boolean bulkSuspendListings
) {}
