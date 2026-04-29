package com.slparcelauctions.backend.admin.disputes;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.escrow.EscrowState;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/disputes")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminDisputeController {

    private final AdminDisputeQueryService queryService;
    private final AdminDisputeService disputeService;

    @GetMapping
    public Page<AdminDisputeQueueRow> list(
            @RequestParam(required = false) EscrowState status,
            @RequestParam(required = false) String reasonCategory,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return queryService.list(status, reasonCategory, page, size);
    }

    @GetMapping("/{escrowId}")
    public AdminDisputeDetail detail(@PathVariable Long escrowId) {
        return queryService.detail(escrowId);
    }

    @PostMapping("/{escrowId}/resolve")
    public AdminDisputeResolveResponse resolve(
            @PathVariable Long escrowId,
            @Valid @RequestBody AdminDisputeResolveRequest body,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return disputeService.resolve(escrowId, body, principal.userId());
    }
}
