package com.slparcelauctions.backend.admin.infrastructure.reconciliation;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/reconciliation")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminReconciliationController {

    private final AdminReconciliationService service;

    @GetMapping("/runs")
    public List<ReconciliationRunRow> runs(@RequestParam(defaultValue = "7") int days) {
        return service.recentRuns(days);
    }
}
