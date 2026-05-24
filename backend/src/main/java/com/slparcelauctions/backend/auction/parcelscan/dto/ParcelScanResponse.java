package com.slparcelauctions.backend.auction.parcelscan.dto;

import java.time.OffsetDateTime;

/**
 * Public read DTO for {@code GET /api/v1/auctions/{publicId}/parcel-scan}.
 * See docs/superpowers/specs/2026-05-24-parcel-map-frontend-design.md.
 *
 * <p>Both byte arrays are base64-encoded so the response is JSON-safe.
 * {@code layoutCellsBase64} decodes to 512 bytes (4096 bits, MSB-first within
 * each byte, row-major SW-first). {@code heightCellsBase64} decodes to 4096
 * uint8s. Per-cell elevation:
 * <pre>elevationMeters = baseMeters + (cells[i] &amp; 0xFF) * stepMeters</pre>
 */
public record ParcelScanResponse(
        Integer gridSize,
        Integer cellSizeMeters,
        String layoutCellsBase64,
        String heightCellsBase64,
        Float baseMeters,
        Float stepMeters,
        OffsetDateTime scannedAt
) {
}
