package com.slparcelauctions.backend.parceltag;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.parceltag.dto.ParcelTagGroupResponse;

import lombok.RequiredArgsConstructor;

/**
 * Public parcel-tag reference. Returns all active tags grouped by category
 * and ordered by per-category {@code sort_order}. Available to any
 * authenticated user (see {@code SecurityConfig}).
 */
@RestController
@RequestMapping("/api/v1/parcel-tags")
@RequiredArgsConstructor
public class ParcelTagController {

    private final ParcelTagService service;

    @GetMapping
    public List<ParcelTagGroupResponse> list() {
        return service.listGroupedActive();
    }
}
