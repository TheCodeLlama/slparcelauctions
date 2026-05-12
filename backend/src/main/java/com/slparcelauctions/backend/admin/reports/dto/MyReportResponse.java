package com.slparcelauctions.backend.admin.reports.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Envelope returned by the reporter-facing endpoints (per-auction
 * {@code POST /report} / {@code GET /my-report} and the merged
 * {@code GET /me/reports} list). The shape is a union of listing and realty-group
 * reports so the frontend can render both kinds in a single timeline.
 *
 * <p>Fields specific to one kind are null on the other:
 * <ul>
 *   <li>{@code subject} and {@code adminNotes}-style review notes only apply to
 *       listing reports; group reports carry their review prose in
 *       {@code resolutionNotes}.</li>
 *   <li>{@code entityType} discriminates the row: {@code "LISTING"} or
 *       {@code "REALTY_GROUP"}.</li>
 *   <li>{@code entityPublicId} carries the auction's or group's {@code publicId}
 *       so the frontend can build the deep link without an extra lookup.</li>
 * </ul>
 *
 * <p>{@code reason} and {@code status} are emitted as the enum {@code name()} so
 * the same DTO can carry either entity's typed enums on the wire.
 */
public record MyReportResponse(
    UUID publicId,
    String entityType,
    UUID entityPublicId,
    String subject,
    String reason,
    String details,
    String status,
    String resolutionNotes,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {}
