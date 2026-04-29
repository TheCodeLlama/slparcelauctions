package com.slparcelauctions.backend.admin.infrastructure.bots;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/bot-pool")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminBotPoolController {

    private final AdminBotPoolService service;

    @GetMapping("/health")
    public List<BotPoolHealthRow> health() {
        return service.getHealth();
    }
}
