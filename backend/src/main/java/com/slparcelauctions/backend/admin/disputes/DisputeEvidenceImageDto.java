package com.slparcelauctions.backend.admin.disputes;

import java.time.OffsetDateTime;

public record DisputeEvidenceImageDto(
        String s3Key,
        String contentType,
        long size,
        OffsetDateTime uploadedAt,
        String presignedUrl,
        OffsetDateTime presignedUntil) {
}
