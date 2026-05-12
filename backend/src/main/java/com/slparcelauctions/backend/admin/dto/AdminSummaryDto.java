package com.slparcelauctions.backend.admin.dto;

import java.util.UUID;

/**
 * Compact admin summary embedded inside admin-facing DTOs (suspension records,
 * ban records, dispute resolutions, ledger entries, etc.) to identify the admin
 * actor without leaking the internal numeric user id.
 *
 * <p>Shared across the {@code admin} and {@code realty.moderation} sub-projects
 * so any DTO that needs an actor reference can use it without an upward
 * cross-package import.
 */
public record AdminSummaryDto(UUID publicId, String displayName) {}
