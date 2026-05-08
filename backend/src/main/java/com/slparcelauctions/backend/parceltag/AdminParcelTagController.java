package com.slparcelauctions.backend.parceltag;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.parceltag.dto.AdminParcelTagDto;
import com.slparcelauctions.backend.parceltag.dto.CreateParcelTagRequest;
import com.slparcelauctions.backend.parceltag.dto.UpdateParcelTagRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Admin write surface for parcel tags. ROLE_ADMIN gating happens in
 * {@code SecurityConfig} via the existing {@code /api/v1/admin/**}
 * matcher — no per-method {@code @PreAuthorize} needed.
 */
@RestController
@RequestMapping("/api/v1/admin/parcel-tags")
@RequiredArgsConstructor
public class AdminParcelTagController {

    private final AdminParcelTagService service;

    @GetMapping
    public List<AdminParcelTagDto> list() {
        return service.listAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminParcelTagDto create(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody CreateParcelTagRequest body) {
        return service.create(principal.userId(), body);
    }

    @PatchMapping("/{code}")
    public AdminParcelTagDto update(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String code,
            @Valid @RequestBody UpdateParcelTagRequest body) {
        return service.update(principal.userId(), code, body);
    }

    @PostMapping("/{code}/toggle-active")
    public AdminParcelTagDto toggleActive(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String code) {
        return service.toggleActive(principal.userId(), code);
    }
}
