package com.slparcelauctions.backend.admin.ledger.dto;

import java.time.OffsetDateTime;
import java.util.Set;

/**
 * Validated, internal-id-resolved filter state for the admin global ledger
 * query. Built by {@link com.slparcelauctions.backend.admin.ledger.AdminLedgerService}
 * from the controller's raw request params: enum parsing, date parsing, and
 * the {@code userPublicId → userInternalId} resolution all happen there.
 *
 * <p>An empty {@code kinds} set means "all five sources" — the query
 * repository compiles only the listed arms into the UNION ALL.
 *
 * <p>{@code search} is the raw text the admin typed; the repository
 * lowercases + wraps with {@code %} sentinels for the LIKE bind.
 */
public record AdminLedgerFilterParams(
    Set<AdminLedgerKind> kinds,
    Long userInternalId,
    String entryType,
    String refType,
    Long refId,
    OffsetDateTime dateFrom,
    OffsetDateTime dateTo,
    Long amountMin,
    Long amountMax,
    String search
) {}
