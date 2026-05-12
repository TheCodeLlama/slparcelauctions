package com.slparcelauctions.backend.realty.moderation;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.realty.moderation.dto.AdminSummaryDto;
import com.slparcelauctions.backend.realty.moderation.dto.SuspensionDto;
import com.slparcelauctions.backend.user.User;

import lombok.RequiredArgsConstructor;

/**
 * Maps {@link RealtyGroupSuspension} entities to wire-shape {@link SuspensionDto}s.
 *
 * <p>The {@code status} string is derived from the row's lifecycle timestamps at
 * the current clock time so the admin UI can render the correct chip without
 * recomputing the logic client-side. See {@link SuspensionDto} javadoc for the
 * four-state taxonomy.
 *
 * <p>Admin-actor lookups consult the eagerly-fetchable {@code User} associations
 * on the entity; we read {@code publicId} + {@code displayName} only. The
 * {@code displayName} accessor on {@link User} already falls back to
 * {@code username} when no display name is set.
 *
 * <p>Sub-project F spec §6.2.
 */
@Component
@RequiredArgsConstructor
public class SuspensionDtoMapper {

    private final Clock clock;

    /**
     * Build the wire DTO. Safe to call against a managed (or detached) entity
     * — {@code issuedByAdmin} / {@code liftedByAdmin} accesses will trigger a
     * lazy load if the entity is still attached. Caller is expected to invoke
     * this from a transactional context.
     */
    public SuspensionDto toDto(RealtyGroupSuspension entity) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        return new SuspensionDto(
            entity.getPublicId(),
            entity.getReason(),
            entity.getNotes(),
            entity.getIssuedAt(),
            entity.getExpiresAt(),
            entity.getLiftedAt(),
            entity.getLiftedNotes(),
            toAdminSummary(entity.getIssuedByAdmin()),
            toAdminSummary(entity.getLiftedByAdmin()),
            computeStatus(entity, now));
    }

    private static AdminSummaryDto toAdminSummary(User user) {
        if (user == null) return null;
        return new AdminSummaryDto(user.getPublicId(), user.getDisplayName());
    }

    private static String computeStatus(RealtyGroupSuspension entity, OffsetDateTime now) {
        if (entity.getLiftedAt() != null) {
            return "LIFTED";
        }
        OffsetDateTime expiresAt = entity.getExpiresAt();
        if (expiresAt == null) {
            return "ACTIVE_PERMANENT";
        }
        if (expiresAt.isAfter(now)) {
            return "ACTIVE_TIMED";
        }
        return "EXPIRED";
    }
}
