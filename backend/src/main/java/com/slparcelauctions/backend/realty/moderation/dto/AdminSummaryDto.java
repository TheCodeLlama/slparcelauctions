package com.slparcelauctions.backend.realty.moderation.dto;

import java.util.UUID;

/**
 * Compact admin summary embedded inside {@link SuspensionDto} (and any future
 * moderation DTOs in this sub-project) to identify the admin actor without
 * leaking the internal numeric user id.
 *
 * <p>Sub-project F spec §6.2.
 */
public record AdminSummaryDto(UUID publicId, String displayName) {}
