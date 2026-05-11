package com.slparcelauctions.backend.realty.dto;

import java.util.UUID;

import com.slparcelauctions.backend.realty.OldLeaderAction;

import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code POST /api/v1/realty-groups/{publicId}/transfer-leadership}.
 *
 * <p>{@code newLeaderPublicId} must reference a current member of the group; otherwise the
 * service rejects with {@code TRANSFER_TARGET_NOT_MEMBER} (400). {@code oldLeaderAction}
 * dictates what happens to the outgoing leader's membership row:
 *
 * <ul>
 *   <li>{@code STAY}: row is preserved with every permission flag granted, so the former
 *       leader stays as a fully-empowered delegate.</li>
 *   <li>{@code LEAVE}: row is deleted; the former leader is removed from the group.</li>
 * </ul>
 */
public record TransferLeadershipRequest(
    @NotNull UUID newLeaderPublicId,
    @NotNull OldLeaderAction oldLeaderAction
) {}
