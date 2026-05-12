package com.slparcelauctions.backend.realty.slgroup;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.realty.slgroup.dto.RealtyGroupSlGroupDto;
import com.slparcelauctions.backend.realty.slgroup.dto.RealtyGroupSlGroupDtoMapper;
import com.slparcelauctions.backend.realty.slgroup.dto.RegisterSlGroupRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Realty-group-scoped SL group registration surface (spec §5.1). All routes
 * resolve the parent realty group by {@code publicId} and delegate authorization
 * to {@link RealtyGroupSlGroupService} (which calls into {@code RealtyGroupAuthorizer}).
 *
 * <p>Exception mappings live in {@code RealtyExceptionHandler} — this controller
 * does not handle them inline.
 */
@RestController
@RequestMapping("/api/v1/realty/groups/{publicId}/sl-groups")
@RequiredArgsConstructor
public class RealtyGroupSlGroupController {

    private final RealtyGroupSlGroupService service;
    private final RealtyGroupSlGroupDtoMapper mapper;

    @PostMapping
    public RealtyGroupSlGroupDto register(
            @PathVariable UUID publicId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody RegisterSlGroupRequest req) {
        RealtyGroupSlGroup row = service.register(principal.userId(), publicId, req.slGroupUuid());
        return mapper.toDto(row);
    }

    @GetMapping
    public List<RealtyGroupSlGroupDto> list(
            @PathVariable UUID publicId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return service.listForGroup(principal.userId(), publicId).stream()
                .map(mapper::toDto)
                .toList();
    }

    @DeleteMapping("/{slGroupPublicId}")
    public ResponseEntity<Void> unregister(
            @PathVariable UUID publicId,
            @PathVariable UUID slGroupPublicId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        service.unregister(principal.userId(), publicId, slGroupPublicId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{slGroupPublicId}/recheck")
    public RealtyGroupSlGroupDto recheck(
            @PathVariable UUID publicId,
            @PathVariable UUID slGroupPublicId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        RealtyGroupSlGroup row = service.recheck(principal.userId(), publicId, slGroupPublicId);
        return mapper.toDto(row);
    }
}
