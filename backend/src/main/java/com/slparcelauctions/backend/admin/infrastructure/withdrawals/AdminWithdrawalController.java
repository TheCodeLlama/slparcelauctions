package com.slparcelauctions.backend.admin.infrastructure.withdrawals;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/withdrawals")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminWithdrawalController {

    private final AdminWithdrawalService service;

    @PostMapping
    public WithdrawalRow create(
            @Valid @RequestBody WithdrawalRequest body,
            @AuthenticationPrincipal AuthPrincipal principal) {
        Withdrawal w = service.requestWithdrawal(body, principal.userId());
        return toRow(w);
    }

    @GetMapping
    public Page<WithdrawalRow> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(page, size).map(this::toRow);
    }

    @GetMapping("/available")
    public Map<String, Long> available() {
        return Map.of("available", service.availableToWithdraw());
    }

    private WithdrawalRow toRow(Withdrawal w) {
        return new WithdrawalRow(
                w.getId(), w.getAmount(), w.getRecipientUuid(),
                w.getAdminUserId(), w.getNotes(), w.getStatus(),
                w.getRequestedAt(), w.getCompletedAt(), w.getFailureReason());
    }
}
