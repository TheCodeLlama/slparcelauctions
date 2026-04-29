package com.slparcelauctions.backend.admin.infrastructure.terminals;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
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
}
