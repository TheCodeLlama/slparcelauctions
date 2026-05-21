package com.slparcelauctions.backend.user.dto;

/**
 * Public response shape for the variant-aware default-cover endpoints
 * ({@code GET / POST /api/v1/users/me/default-cover/{variant}}). {@code url}
 * is a short-lived presigned S3 URL the browser can render directly.
 */
public record UserDefaultCoverDto(String url, String contentType, Long sizeBytes) {}
