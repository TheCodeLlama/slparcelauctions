package com.slparcelauctions.backend.parceltag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.parceltag.dto.ParcelTagGroupResponse;
import com.slparcelauctions.backend.parceltag.dto.ParcelTagResponse;

import lombok.RequiredArgsConstructor;

/**
 * Public read service for parcel tags. Tags + categories are admin-managed
 * via the {@code /api/v1/admin/parcel-tags} and
 * {@code /api/v1/admin/parcel-tag-categories} endpoints — no first-boot seed
 * is provided (admins create the catalogue from scratch).
 */
@Service
@RequiredArgsConstructor
public class ParcelTagService {

    private final ParcelTagRepository repo;

    @Transactional(readOnly = true)
    public List<ParcelTagGroupResponse> listGroupedActive() {
        Map<String, List<ParcelTagResponse>> grouped = new LinkedHashMap<>();
        for (ParcelTag t : repo.findByActiveTrueAndCategoryActiveTrueOrderByCategory_LabelAscLabelAsc()) {
            grouped.computeIfAbsent(t.getCategory().getLabel(), k -> new ArrayList<>())
                    .add(ParcelTagResponse.from(t));
        }
        return grouped.entrySet().stream()
                .map(e -> new ParcelTagGroupResponse(e.getKey(), e.getValue()))
                .toList();
    }
}
