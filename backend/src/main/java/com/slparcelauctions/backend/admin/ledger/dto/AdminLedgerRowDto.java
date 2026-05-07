package com.slparcelauctions.backend.admin.ledger.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row in the admin global ledger view — a unified shape across all five
 * money-event sources. {@link AdminLedgerKind} discriminates the source;
 * {@code nativeId} is the source table's primary key. {@code eventId} is a
 * composite "{kind}-{nativeId}" string that uniquely identifies the row
 * across the unioned feed (used as the React key on the frontend).
 *
 * <p><b>Sign convention for {@code amountLindens}:</b> always signed from the
 * resolved primary user's wallet perspective. Money leaving the user's wallet
 * is negative; money arriving is positive. For rows where no user resolves
 * (e.g. ESCROW commission flowing platform-to-platform), the sign is
 * positive by convention but the user column renders empty.
 *
 * <p>{@code status} is null for {@code USER_LEDGER} rows (the user ledger is
 * append-only and never has a pending state). {@code counterpartyPublicId}
 * is null for kinds that are intrinsically single-party
 * ({@code USER_LEDGER}, {@code TERMINAL_CMD} except where the recipient is
 * the bot, {@code BID_RESERVATION}).
 */
public record AdminLedgerRowDto(
    AdminLedgerKind kind,
    String eventId,
    Long nativeId,
    OffsetDateTime createdAt,
    UUID userPublicId,
    String username,
    UUID counterpartyPublicId,
    String counterpartyUsername,
    Long amountLindens,
    String entryType,
    String status,
    String refType,
    Long refId,
    String description
) {}
