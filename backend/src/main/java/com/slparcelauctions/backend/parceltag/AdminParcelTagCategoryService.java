package com.slparcelauctions.backend.parceltag;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.admin.audit.AdminActionService;
import com.slparcelauctions.backend.admin.audit.AdminActionTargetType;
import com.slparcelauctions.backend.admin.audit.AdminActionType;
import com.slparcelauctions.backend.parceltag.dto.AdminParcelTagCategoryDto;
import com.slparcelauctions.backend.parceltag.dto.CreateParcelTagCategoryRequest;
import com.slparcelauctions.backend.parceltag.dto.UpdateParcelTagCategoryRequest;
import com.slparcelauctions.backend.parceltag.exception.ParcelTagCategoryCodeConflictException;
import com.slparcelauctions.backend.parceltag.exception.ParcelTagCategoryNotFoundException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminParcelTagCategoryService {

    private final ParcelTagCategoryRepository repo;
    private final AdminActionService adminActionService;

    @Transactional(readOnly = true)
    public List<AdminParcelTagCategoryDto> listAll() {
        return repo.findAllByOrderByLabelAsc().stream()
                .map(AdminParcelTagCategoryDto::from)
                .toList();
    }

    @Transactional
    public AdminParcelTagCategoryDto create(Long adminUserId, CreateParcelTagCategoryRequest req) {
        if (repo.existsByCode(req.code())) {
            throw new ParcelTagCategoryCodeConflictException(req.code());
        }
        ParcelTagCategory category = ParcelTagCategory.builder()
                .code(req.code())
                .label(req.label())
                .description(req.description())
                .active(true)
                .build();
        ParcelTagCategory saved = repo.save(category);
        log.info("Admin {} created parcel tag category {}", adminUserId, saved.getCode());

        Map<String, Object> details = new HashMap<>();
        details.put("code", saved.getCode());
        details.put("label", saved.getLabel());
        adminActionService.record(adminUserId,
                AdminActionType.PARCEL_TAG_CATEGORY_CREATED,
                AdminActionTargetType.PARCEL_TAG_CATEGORY,
                saved.getId(), null, details);

        return AdminParcelTagCategoryDto.from(saved);
    }

    @Transactional
    public AdminParcelTagCategoryDto update(
            Long adminUserId, String code, UpdateParcelTagCategoryRequest req) {
        ParcelTagCategory cat = repo.findByCode(code)
                .orElseThrow(() -> new ParcelTagCategoryNotFoundException(code));

        Map<String, Object> changes = new HashMap<>();
        if (req.label() != null && !req.label().equals(cat.getLabel())) {
            changes.put("label", Map.of("from", cat.getLabel(), "to", req.label()));
            cat.setLabel(req.label());
        }
        if (req.description() != null && !req.description().equals(cat.getDescription())) {
            changes.put("description", Map.of(
                    "from", cat.getDescription() == null ? "" : cat.getDescription(),
                    "to", req.description()));
            cat.setDescription(req.description());
        }

        ParcelTagCategory saved = repo.save(cat);

        if (!changes.isEmpty()) {
            Map<String, Object> details = new HashMap<>();
            details.put("code", saved.getCode());
            details.put("changes", changes);
            adminActionService.record(adminUserId,
                    AdminActionType.PARCEL_TAG_CATEGORY_UPDATED,
                    AdminActionTargetType.PARCEL_TAG_CATEGORY,
                    saved.getId(), null, details);
            log.info("Admin {} updated parcel tag category {}: {}",
                    adminUserId, saved.getCode(), changes.keySet());
        }

        return AdminParcelTagCategoryDto.from(saved);
    }

    @Transactional
    public AdminParcelTagCategoryDto toggleActive(Long adminUserId, String code) {
        ParcelTagCategory cat = repo.findByCode(code)
                .orElseThrow(() -> new ParcelTagCategoryNotFoundException(code));
        boolean before = cat.getActive();
        cat.setActive(!before);
        ParcelTagCategory saved = repo.save(cat);

        Map<String, Object> details = new HashMap<>();
        details.put("code", saved.getCode());
        details.put("from", before);
        details.put("to", saved.getActive());
        adminActionService.record(adminUserId,
                AdminActionType.PARCEL_TAG_CATEGORY_TOGGLED_ACTIVE,
                AdminActionTargetType.PARCEL_TAG_CATEGORY,
                saved.getId(), null, details);
        log.info("Admin {} toggled parcel tag category {} active {} -> {}",
                adminUserId, saved.getCode(), before, saved.getActive());

        return AdminParcelTagCategoryDto.from(saved);
    }
}
