package com.slparcelauctions.backend.admin.ledger;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.admin.ledger.dto.AdminLedgerFilterParams;
import com.slparcelauctions.backend.admin.ledger.dto.AdminLedgerKind;
import com.slparcelauctions.backend.admin.ledger.dto.AdminLedgerRowDto;
import com.slparcelauctions.backend.admin.ledger.exception.AdminLedgerStateException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * Backs the admin global ledger view's read endpoint. Validates the request,
 * resolves the {@code userPublicId} (UUID) to an internal {@code Long}, then
 * delegates to {@link AdminLedgerQueryRepository}.
 *
 * <p>Validation rules (per spec §5):
 * <ul>
 *   <li>{@code entryType} requires exactly one {@code kind} (otherwise the
 *       subtype string is ambiguous across sources).</li>
 *   <li>{@code refId} requires {@code refType} (a stray ref id with no type
 *       would match nothing).</li>
 *   <li>{@code dateFrom} must not exceed {@code dateTo}.</li>
 * </ul>
 *
 * <p>Sort-column whitelist enforcement happens inside the repository so the
 * controller can pass a raw {@code Pageable} through without parsing the sort
 * twice.
 */
@Service
@RequiredArgsConstructor
public class AdminLedgerService {

    private final AdminLedgerQueryRepository queryRepo;
    private final UserRepository userRepo;

    @Transactional(readOnly = true)
    public Page<AdminLedgerRowDto> list(
            Set<AdminLedgerKind> kinds,
            UUID userPublicId,
            String entryType,
            String refType,
            Long refId,
            OffsetDateTime dateFrom,
            OffsetDateTime dateTo,
            Long amountMin,
            Long amountMax,
            String search,
            Pageable pageable) {

        // entryType requires exactly one kind
        if (entryType != null && (kinds == null || kinds.size() != 1)) {
            throw new AdminLedgerStateException(
                "ENTRY_TYPE_REQUIRES_SINGLE_KIND",
                "entryType filter requires exactly one kind to be selected");
        }

        // refId requires refType
        if (refId != null && refType == null) {
            throw new AdminLedgerStateException(
                "REF_ID_REQUIRES_REF_TYPE",
                "refId filter requires refType to be set");
        }

        // dateFrom <= dateTo
        if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
            throw new AdminLedgerStateException(
                "INVALID_DATE_RANGE",
                "dateFrom must not be after dateTo");
        }

        Long userInternalId = null;
        if (userPublicId != null) {
            userInternalId = userRepo.findByPublicId(userPublicId)
                .map(User::getId)
                .orElseThrow(() -> new AdminLedgerStateException(
                    "USER_NOT_FOUND",
                    "User not found: " + userPublicId));
        }

        AdminLedgerFilterParams params = new AdminLedgerFilterParams(
            kinds, userInternalId, entryType, refType, refId,
            dateFrom, dateTo, amountMin, amountMax, search
        );

        return queryRepo.search(params, pageable);
    }
}
