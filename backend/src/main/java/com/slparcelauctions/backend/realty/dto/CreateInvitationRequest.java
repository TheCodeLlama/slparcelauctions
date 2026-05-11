package com.slparcelauctions.backend.realty.dto;

import java.util.Set;

import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;

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
 */
public record CreateInvitationRequest(
    @NotBlank String invitedUsername,
    @NotNull Set<RealtyGroupPermission> permissions
) {}
