package com.slparcelauctions.backend.admin.infrastructure.terminals;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/terminals")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminTerminalsController {

    private final AdminTerminalsService service;

    @GetMapping
    public List<AdminTerminalRow> list() {
        return service.list();
    }

    /**
     * Deactivates a registered terminal (soft delete — flips active=false).
     * The row stays for forensics; the dispatcher stops routing to it.
     * Re-registering via POST /sl/terminal/register sets active=true again.
     */
    @DeleteMapping("/{terminalId}")
    public ResponseEntity<Void> deactivate(@PathVariable String terminalId) {
        service.deactivate(terminalId);
        return ResponseEntity.noContent().build();
    }
}
