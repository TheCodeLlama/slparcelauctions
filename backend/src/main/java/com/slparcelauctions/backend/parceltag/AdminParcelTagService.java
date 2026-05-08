package com.slparcelauctions.backend.parceltag;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.admin.audit.AdminActionService;
import com.slparcelauctions.backend.admin.audit.AdminActionTargetType;
import com.slparcelauctions.backend.admin.audit.AdminActionType;
import com.slparcelauctions.backend.parceltag.dto.AdminParcelTagDto;
import com.slparcelauctions.backend.parceltag.dto.CreateParcelTagRequest;
import com.slparcelauctions.backend.parceltag.dto.UpdateParcelTagRequest;
import com.slparcelauctions.backend.parceltag.exception.ParcelTagCodeConflictException;
import com.slparcelauctions.backend.parceltag.exception.ParcelTagNotFoundException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Write-side admin operations for {@link ParcelTag}. Read-side admin list
 * runs through the same repository as the public service but returns
 * inactive rows too.
 *
 * <p>Each write records an {@code AdminAction} via {@link AdminActionService}
 * for audit-log traceability.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminParcelTagService {

    private final ParcelTagRepository repo;
    private final AdminActionService adminActionService;

    @Transactional(readOnly = true)
    public List<AdminParcelTagDto> listAll() {
        return repo.findAllByOrderByCategoryAscLabelAsc().stream()
                .map(AdminParcelTagDto::from)
                .toList();
    }

    @Transactional
    public AdminParcelTagDto create(Long adminUserId, CreateParcelTagRequest req) {
        if (repo.existsByCode(req.code())) {
            throw new ParcelTagCodeConflictException(req.code());
        }
        ParcelTag tag = ParcelTag.builder()
                .code(req.code())
                .label(req.label())
                .category(req.category())
                .description(req.description())
                .active(true)
                .build();
        ParcelTag saved = repo.save(tag);
        log.info("Admin {} created parcel tag {}", adminUserId, saved.getCode());

        Map<String, Object> details = new HashMap<>();
        details.put("code", saved.getCode());
        details.put("label", saved.getLabel());
        details.put("category", saved.getCategory());
        adminActionService.record(adminUserId,
                AdminActionType.PARCEL_TAG_CREATED,
                AdminActionTargetType.PARCEL_TAG,
                saved.getId(), null, details);

        return AdminParcelTagDto.from(saved);
    }

    @Transactional
    public AdminParcelTagDto update(Long adminUserId, String code, UpdateParcelTagRequest req) {
        ParcelTag tag = repo.findByCode(code)
                .orElseThrow(() -> new ParcelTagNotFoundException(code));

        Map<String, Object> changes = new HashMap<>();
        if (req.label() != null && !req.label().equals(tag.getLabel())) {
            changes.put("label", Map.of("from", tag.getLabel(), "to", req.label()));
            tag.setLabel(req.label());
        }
        if (req.category() != null && !req.category().equals(tag.getCategory())) {
            changes.put("category", Map.of("from", tag.getCategory(), "to", req.category()));
            tag.setCategory(req.category());
        }
        if (req.description() != null && !req.description().equals(tag.getDescription())) {
            changes.put("description", Map.of(
                    "from", tag.getDescription() == null ? "" : tag.getDescription(),
                    "to", req.description()));
            tag.setDescription(req.description());
        }

        ParcelTag saved = repo.save(tag);

        if (!changes.isEmpty()) {
            Map<String, Object> details = new HashMap<>();
            details.put("code", saved.getCode());
            details.put("changes", changes);
            adminActionService.record(adminUserId,
                    AdminActionType.PARCEL_TAG_UPDATED,
                    AdminActionTargetType.PARCEL_TAG,
                    saved.getId(), null, details);
            log.info("Admin {} updated parcel tag {}: {}", adminUserId, saved.getCode(), changes.keySet());
        }

        return AdminParcelTagDto.from(saved);
    }

    @Transactional
    public AdminParcelTagDto toggleActive(Long adminUserId, String code) {
        ParcelTag tag = repo.findByCode(code)
                .orElseThrow(() -> new ParcelTagNotFoundException(code));
        boolean before = tag.getActive();
        tag.setActive(!before);
        ParcelTag saved = repo.save(tag);

        Map<String, Object> details = new HashMap<>();
        details.put("code", saved.getCode());
        details.put("from", before);
        details.put("to", saved.getActive());
        adminActionService.record(adminUserId,
                AdminActionType.PARCEL_TAG_TOGGLED_ACTIVE,
                AdminActionTargetType.PARCEL_TAG,
                saved.getId(), null, details);
        log.info("Admin {} toggled parcel tag {} active {} -> {}",
                adminUserId, saved.getCode(), before, saved.getActive());

        return AdminParcelTagDto.from(saved);
    }
}
