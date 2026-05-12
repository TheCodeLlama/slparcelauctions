package com.slparcelauctions.backend.realty.wallet.dto;

import java.util.UUID;

/**
 * Actor who triggered a realty-group ledger entry.
 * Used in {@link GroupLedgerEntryDto#actor()}. Spec §5.6.
 */
public record LedgerActorDto(UUID publicId, String displayName) {
}
