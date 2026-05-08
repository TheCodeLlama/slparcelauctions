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
import com.slparcelauctions.backend.parceltag.dto.AdminParcelTagCategoryDto;
import com.slparcelauctions.backend.parceltag.dto.CreateParcelTagCategoryRequest;
import com.slparcelauctions.backend.parceltag.dto.UpdateParcelTagCategoryRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/admin/parcel-tag-categories")
@RequiredArgsConstructor
public class AdminParcelTagCategoryController {

    private final AdminParcelTagCategoryService service;

    @GetMapping
    public List<AdminParcelTagCategoryDto> list() {
        return service.listAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminParcelTagCategoryDto create(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody CreateParcelTagCategoryRequest body) {
        return service.create(principal.userId(), body);
    }

    @PatchMapping("/{code}")
    public AdminParcelTagCategoryDto update(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String code,
            @Valid @RequestBody UpdateParcelTagCategoryRequest body) {
        return service.update(principal.userId(), code, body);
    }

    @PostMapping("/{code}/toggle-active")
    public AdminParcelTagCategoryDto toggleActive(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String code) {
        return service.toggleActive(principal.userId(), code);
    }
}
