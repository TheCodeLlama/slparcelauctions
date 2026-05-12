package com.slparcelauctions.backend.realty.wallet.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Single realty-group ledger entry as surfaced to the frontend.
 * {@code refType}, {@code refPublicId}, and {@code actor} are nullable;
 * {@link JsonInclude#NON_NULL} drops them from the JSON when absent.
 * Spec §5.6.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GroupLedgerEntryDto(
        UUID publicId,
        String entryType,
        long amount,
        long balanceAfter,
        long reservedAfter,
        String refType,
        UUID refPublicId,
        LedgerActorDto actor,
        OffsetDateTime createdAt) {
}
