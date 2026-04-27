package com.slparcelauctions.backend.admin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.admin.dto.AdminStatsResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminStatsController {

    private final AdminStatsService statsService;

    @GetMapping("/stats")
    public AdminStatsResponse stats() {
        return statsService.compute();
    }
}
