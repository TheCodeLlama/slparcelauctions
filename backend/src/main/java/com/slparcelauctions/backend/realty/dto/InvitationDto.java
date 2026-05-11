package com.slparcelauctions.backend.realty.dto;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import com.slparcelauctions.backend.realty.InvitationStatus;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;

/**
 * Wire shape for an invitation, used on both the user side ("my invitations") and the
 * group side ("pending invitations on this group"). Carries enough to render the accept
 * dialog (group name + proposed permission set) and to time the invitee's response window.
 */
public record InvitationDto(
    UUID publicId,
    UUID groupPublicId,
    String groupName,
    String groupSlug,
    UUID invitedByPublicId,
    String invitedByDisplayName,
    Set<RealtyGroupPermission> permissions,
    InvitationStatus status,
    OffsetDateTime expiresAt,
    OffsetDateTime createdAt,
    OffsetDateTime respondedAt
) {}
