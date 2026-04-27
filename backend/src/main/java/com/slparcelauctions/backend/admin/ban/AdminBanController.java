package com.slparcelauctions.backend.admin.ban;

import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.admin.ban.dto.AdminBanRowDto;
import com.slparcelauctions.backend.admin.ban.dto.CreateBanRequest;
import com.slparcelauctions.backend.admin.ban.dto.LiftBanRequest;
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.common.PagedResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/admin/bans")
@RequiredArgsConstructor
public class AdminBanController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AdminBanService service;

    @GetMapping
    public PagedResponse<AdminBanRowDto> list(
            @RequestParam(defaultValue = "active") String status,
            @RequestParam(required = false) BanType type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        int clampedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return service.list(status, type, PageRequest.of(page, clampedSize));
    }

    @PostMapping
    public AdminBanRowDto create(
            @Valid @RequestBody CreateBanRequest body,
            @AuthenticationPrincipal AuthPrincipal admin) {
        return service.create(body, admin.userId());
    }

    @PostMapping("/{id}/lift")
    public AdminBanRowDto lift(
            @PathVariable Long id,
            @Valid @RequestBody LiftBanRequest body,
            @AuthenticationPrincipal AuthPrincipal admin) {
        return service.lift(id, admin.userId(), body.liftedReason());
    }
}
