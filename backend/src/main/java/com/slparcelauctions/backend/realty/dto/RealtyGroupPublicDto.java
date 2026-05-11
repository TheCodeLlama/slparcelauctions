package com.slparcelauctions.backend.realty.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Wire shape for the public group profile endpoint.
 *
 * <p>{@code memberSince} mirrors the group's {@code createdAt}. {@code agentFeeRate} and
 * {@code agentFeeSplit} are emitted now (consumed by sub-project C) so agents-considering-
 * joining can see them on the public page. {@code memberCount} is the count of active member
 * rows ({@link com.slparcelauctions.backend.realty.RealtyGroupMember}). {@code logoUrl} and
 * {@code coverUrl} are relative paths pointing at the byte-serving endpoints (Phase 10);
 * the frontend wraps them with {@code apiUrl(...)} so the browser hits the backend rather
 * than the page origin. Either may be {@code null} when no image has been uploaded.
 */
public record RealtyGroupPublicDto(
    UUID publicId,
    String name,
    String slug,
    String description,
    String website,
    String logoUrl,
    String coverUrl,
    OffsetDateTime memberSince,
    LeaderCardDto leader,
    List<AgentCardDto> agents,
    BigDecimal agentFeeRate,
    BigDecimal agentFeeSplit,
    int memberSeatLimit,
    int memberCount
) {}
