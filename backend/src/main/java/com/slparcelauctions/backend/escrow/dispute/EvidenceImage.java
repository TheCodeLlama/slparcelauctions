package com.slparcelauctions.backend.escrow.dispute;

import java.time.OffsetDateTime;

public record EvidenceImage(
        String s3Key,
        String contentType,
        long size,
        OffsetDateTime uploadedAt) {
}
