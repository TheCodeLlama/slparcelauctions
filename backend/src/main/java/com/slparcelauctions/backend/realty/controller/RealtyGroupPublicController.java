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
        return cached(mapper.toPublicDto(group, callerId(principal), isAdmin(principal)), principal);
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
        return cached(mapper.toPublicDto(group, callerId(principal), isAdmin(principal)), principal);
    }

    @GetMapping("/{publicId}/members")
    @Transactional(readOnly = true)
    public ResponseEntity<List<AgentCardDto>> getMembers(
            @PathVariable UUID publicId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        RealtyGroup group = groups.findByPublicId(publicId)
            .orElseThrow(() -> new RealtyGroupNotFoundException(publicId));
        if (group.isDissolved()) {
            throw new GroupDissolvedException(group.getPublicId());
        }
        List<AgentCardDto> body = mapper.toAgentCards(group, callerId(principal), isAdmin(principal));
        // Same caller-dependent body as the group-by-slug / by-publicId
        // endpoints (members see commission rates + joinedAt, anonymous
        // viewers don't). Same cache rule: public for anonymous, no-store
        // for authenticated so a fresh PATCH is never masked by a stale
        // cached response.
        CacheControl cache = principal == null
            ? CacheControl.maxAge(60, TimeUnit.SECONDS).cachePublic()
            : CacheControl.noStore();
        return ResponseEntity.ok().cacheControl(cache).body(body);
    }

    // ─────────────────────── helpers ───────────────────────

    private static Long callerId(AuthPrincipal principal) {
        return principal == null ? null : principal.userId();
    }

    private static boolean isAdmin(AuthPrincipal principal) {
        return principal != null && principal.role() == Role.ADMIN;
    }

    /**
     * Cache-Control for {@code getByPublicId} / {@code getBySlug}.
     *
     * <p>The response body depends on the caller's identity — members and
     * admins see {@code agentCommissionRate}, {@code joinedAt}, and the full
     * agent {@code permissions} flag set on each {@link AgentCardDto}, while
     * anonymous (and non-member, non-admin) callers see those fields nulled
     * out by the privacy gate in {@link RealtyGroupDtoMapper}.
     *
     * <p>A {@code public, max-age=N} header would let a shared cache (CDN /
     * Amplify edge) serve one variant to every caller regardless of auth,
     * which:
     * <ul>
     *   <li>leaks the member view ({@code agentCommissionRate} populated) to
     *       anonymous viewers when an authenticated request warmed the
     *       cache, and</li>
     *   <li>masks a fresh write — a leader who PATCHes a commission rate
     *       and immediately re-fetches the group could see the stale
     *       anonymous-view response cached during the previous window.</li>
     * </ul>
     *
     * <p>Fix: anonymous responses are publicly cacheable for 60 s (the body
     * is the same for every anonymous caller, and these surfaces dominate
     * the request volume); authenticated responses set {@code no-store} so
     * each member/admin request goes to origin and reflects the latest
     * server state.
     */
    private static ResponseEntity<RealtyGroupPublicDto> cached(
            RealtyGroupPublicDto body, AuthPrincipal principal) {
        if (principal == null) {
            return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(60, TimeUnit.SECONDS).cachePublic())
                .body(body);
        }
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(body);
    }
}
