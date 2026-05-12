package com.slparcelauctions.backend.realty.moderation.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.realty.moderation.SuspensionReason;

/**
 * Wire representation of a {@code RealtyGroupSuspension} row. Used by:
 * <ul>
 *   <li>{@code POST /api/v1/admin/realty-groups/{publicId}/suspensions} response (201)</li>
 *   <li>{@code GET  /api/v1/admin/realty-groups/{publicId}/suspensions} response (list)</li>
 * </ul>
 *
 * <p>The {@code status} string is computed from the row's lifecycle timestamps:
 * <ul>
 *   <li>{@code ACTIVE_TIMED}     — not lifted, has expiry, expiry in the future.</li>
 *   <li>{@code ACTIVE_PERMANENT} — not lifted, no expiry (permanent ban).</li>
 *   <li>{@code LIFTED}           — {@code liftedAt} is set.</li>
 *   <li>{@code EXPIRED}          — not lifted, has expiry, expiry has passed
 *                                  (terminal state until the expiry-sweep task
 *                                  stamps {@code liftedAt}, after which the row
 *                                  reports {@code LIFTED}).</li>
 * </ul>
 *
 * <p>Sub-project F spec §6.2.
 */
public record SuspensionDto(
    UUID publicId,
    SuspensionReason reason,
    String notes,
    OffsetDateTime issuedAt,
    OffsetDateTime expiresAt,
    OffsetDateTime liftedAt,
    String liftedNotes,
    AdminSummaryDto issuedByAdmin,
    AdminSummaryDto liftedByAdmin,
    String status
) {}
