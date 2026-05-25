package com.slparcelauctions.backend.auction.parcelscan.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Bot-to-backend body for {@code POST /api/v1/bot/tasks/{taskId}/scan-result}.
 *
 * <p>{@code gridSize} and {@code cellSizeMeters} are constrained to the
 * current scan resolution (64 / 4). Raising the cap is a schema-versioned
 * change. {@code layoutCellsBase64} decodes to {@code gridSize^2 / 8} bytes,
 * {@code heightCellsBase64} decodes to {@code gridSize^2} bytes, and
 * {@code landUseCellsBase64} decodes to {@code gridSize^2} bytes (one byte
 * per cell, values 0..4). The service validates lengths after decoding.
 */
public record BotScanResultRequest(
        @NotNull @Min(64) @Max(64) Integer gridSize,
        @NotNull @Min(4) @Max(4) Integer cellSizeMeters,
        @NotBlank String layoutCellsBase64,
        @NotNull Float heightBaseMeters,
        @NotNull @Positive Float heightStepMeters,
        @NotBlank String heightCellsBase64,
        @NotBlank String landUseCellsBase64) {
}
