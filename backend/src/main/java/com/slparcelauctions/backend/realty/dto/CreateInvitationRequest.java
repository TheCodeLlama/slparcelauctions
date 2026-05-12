package com.slparcelauctions.backend.realty.dto;

import java.math.BigDecimal;
import java.util.Set;

import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code POST /api/v1/realty-groups/{publicId}/invitations}.
 *
 * <p>{@code invitedUsername} is the recipient's SLParcels username (case-insensitive lookup
 * via {@code UserRepository.findByUsername}). {@code permissions} is the proposed flag set
 * the invitee will hold once they accept; an empty set is legal (agent with no admin
 * flags). The flag set is copied verbatim onto the {@code RealtyGroupMember} row at accept
 * time.
 *
 * <p>{@code agentCommissionRate} is optional. When present it is persisted on the
 * invitation row and copied verbatim onto the new member row at accept time (spec §3.2,
 * §5.4). When {@code null} the invitation defaults to {@code 0} — the leader can edit the
 * rate post-accept via the edit-permissions surface.
 */
public record CreateInvitationRequest(
    @NotBlank String invitedUsername,
    @NotNull Set<RealtyGroupPermission> permissions,
    @DecimalMin("0.0") BigDecimal agentCommissionRate
) {}
