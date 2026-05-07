package com.slparcelauctions.backend.admin.ledger;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.admin.ledger.dto.AdminLedgerKind;
import com.slparcelauctions.backend.admin.ledger.dto.AdminLedgerRowDto;
import com.slparcelauctions.backend.admin.ledger.exception.AdminLedgerStateException;
import com.slparcelauctions.backend.common.PagedResponse;

import lombok.RequiredArgsConstructor;

/**
 * Admin global ledger view — single endpoint reading a UNION ALL across the
 * five money-event source tables. URL state is the source of truth for the
 * frontend; every filter is bookmarkable via the query string.
 *
 * <p>Spec: {@code docs/superpowers/specs/2026-05-07-admin-ledger-view-design.md}.
 */
@RestController
@RequestMapping("/api/v1/admin/ledger")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminLedgerController {

    private final AdminLedgerService service;

    @GetMapping
    public PagedResponse<AdminLedgerRowDto> list(
            @RequestParam(name = "kinds", required = false) List<String> kindsParam,
            @RequestParam(required = false) UUID userPublicId,
            @RequestParam(required = false) String entryType,
            @RequestParam(required = false) String refType,
            @RequestParam(required = false) Long refId,
            @RequestParam(required = false) OffsetDateTime dateFrom,
            @RequestParam(required = false) OffsetDateTime dateTo,
            @RequestParam(required = false) Long amountMin,
            @RequestParam(required = false) Long amountMax,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {

        Set<AdminLedgerKind> kinds = parseKinds(kindsParam);
        Page<AdminLedgerRowDto> result = service.list(
            kinds, userPublicId, normalize(entryType), normalize(refType), refId,
            dateFrom, dateTo, amountMin, amountMax, normalize(search),
            PageRequest.of(page, size, parseSort(sort))
        );
        return PagedResponse.from(result);
    }

    private static Set<AdminLedgerKind> parseKinds(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return EnumSet.noneOf(AdminLedgerKind.class);  // service treats empty = all
        }
        EnumSet<AdminLedgerKind> result = EnumSet.noneOf(AdminLedgerKind.class);
        for (String s : raw) {
            if (s == null || s.isBlank()) continue;
            try {
                result.add(AdminLedgerKind.valueOf(s.trim()));
            } catch (IllegalArgumentException e) {
                throw new AdminLedgerStateException(
                    "INVALID_KIND",
                    "Unknown ledger kind: " + s);
            }
        }
        return result;
    }

    private static String normalize(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static Sort parseSort(String sortParam) {
        if (sortParam == null || sortParam.isBlank()) {
            return Sort.unsorted();
        }
        List<Sort.Order> orders = new ArrayList<>();
        for (String chunk : sortParam.split(";")) {
            String[] parts = chunk.trim().split(",");
            if (parts.length == 0 || parts[0].isBlank()) continue;
            Sort.Direction dir = parts.length > 1 && parts[1].trim().equalsIgnoreCase("desc")
                    ? Sort.Direction.DESC
                    : Sort.Direction.ASC;
            orders.add(new Sort.Order(dir, parts[0].trim()));
        }
        return orders.isEmpty() ? Sort.unsorted() : Sort.by(orders);
    }
}
