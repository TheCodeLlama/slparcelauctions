package com.slparcelauctions.backend.realty.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Admin list-row shape for the {@code GET /api/v1/admin/realty-groups} endpoint.
 *
 * <p>Compact denormalized projection: pre-resolves the leader's display name and the
 * current member count so the table renders without per-row follow-up queries. {@code
 * dissolvedAt} is populated for soft-deleted groups (status filter {@code dissolved} /
 * {@code all}); active rows leave it {@code null}.
 */
public record RealtyGroupRowDto(
    UUID publicId,
    String name,
    String slug,
    UUID leaderPublicId,
    String leaderDisplayName,
    int memberCount,
    boolean dissolved,
    OffsetDateTime createdAt,
    OffsetDateTime dissolvedAt
) {}
