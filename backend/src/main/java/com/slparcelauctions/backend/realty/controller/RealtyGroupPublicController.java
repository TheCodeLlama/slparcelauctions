package com.slparcelauctions.backend.realty.controller;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.dto.AgentCardDto;
import com.slparcelauctions.backend.realty.dto.RealtyGroupDtoMapper;
import com.slparcelauctions.backend.realty.dto.RealtyGroupPublicDto;
import com.slparcelauctions.backend.realty.exception.GroupDissolvedException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException;
import com.slparcelauctions.backend.user.Role;

import lombok.RequiredArgsConstructor;

/**
 * Anonymous-safe read surface for a realty group. Hides {@code permissions} and
 * {@code joinedAt} from non-member, non-admin requesters per spec §5.2.
 *
 * <p>Dissolved groups return {@code 410 GROUP_DISSOLVED} so the frontend can render a
 * "this group has been dissolved" state rather than 404-ing the page.
 *
 * <p>The detail endpoints emit {@code Cache-Control: public, max-age=60} mirroring the
 * featured-rail posture; the members endpoint omits the cache header because per-member
 * private fields depend on the requester's membership state.
 */
@RestController
@RequestMapping("/api/v1/realty-groups")
@RequiredArgsConstructor
public class RealtyGroupPublicController {

    private final RealtyGroupRepository groups;
    private final RealtyGroupDtoMapper mapper;

    @GetMapping("/{publicId}")
    @Transactional(readOnly = true)
    public ResponseEntity<RealtyGroupPublicDto> getByPublicId(
            @PathVariable UUID publicId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        RealtyGroup group = groups.findByPublicId(publicId)
            .orElseThrow(() -> new RealtyGroupNotFoundException(publicId));
        if (group.isDissolved()) {
            throw new GroupDissolvedException(group.getPublicId());
        }
        return cached(mapper.toPublicDto(group, callerId(principal), isAdmin(principal)));
    }

    @GetMapping("/by-slug/{slug}")
    @Transactional(readOnly = true)
    public ResponseEntity<RealtyGroupPublicDto> getBySlug(
            @PathVariable String slug,
            @AuthenticationPrincipal AuthPrincipal principal) {
        RealtyGroup group = groups.findBySlugAndDissolvedAtIsNull(slug)
            .orElseGet(() -> {
                // Distinguish "never existed" (404) from "dissolved" (410): re-query for
                // any row with this slug regardless of dissolution. If one exists, surface
                // 410 so the frontend can render a "this group has been dissolved" state
                // rather than 404-ing the page.
                RealtyGroup dissolved = groups
                    .findFirstBySlugAndDissolvedAtIsNotNullOrderByDissolvedAtDesc(slug)
                    .orElseThrow(() -> new RealtyGroupNotFoundException(slug));
                throw new GroupDissolvedException(dissolved.getPublicId());
            });
        return cached(mapper.toPublicDto(group, callerId(principal), isAdmin(principal)));
    }

    @GetMapping("/{publicId}/members")
    @Transactional(readOnly = true)
    public List<AgentCardDto> getMembers(
            @PathVariable UUID publicId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        RealtyGroup group = groups.findByPublicId(publicId)
            .orElseThrow(() -> new RealtyGroupNotFoundException(publicId));
        if (group.isDissolved()) {
            throw new GroupDissolvedException(group.getPublicId());
        }
        return mapper.toAgentCards(group, callerId(principal), isAdmin(principal));
    }

    // ─────────────────────── helpers ───────────────────────

    private static Long callerId(AuthPrincipal principal) {
        return principal == null ? null : principal.userId();
    }

    private static boolean isAdmin(AuthPrincipal principal) {
        return principal != null && principal.role() == Role.ADMIN;
    }

    private static ResponseEntity<RealtyGroupPublicDto> cached(RealtyGroupPublicDto body) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(60, TimeUnit.SECONDS).cachePublic())
            .body(body);
    }
}
