package com.slparcelauctions.backend.user.dto;

/**
 * Public response shape for {@code GET /api/v1/users/me/default-cover} and
 * {@code PUT /api/v1/users/me/default-cover}. {@code url} is a short-lived
 * presigned S3 URL the browser can render directly.
 */
public record UserDefaultCoverDto(String url, String contentType, Long sizeBytes) {}
