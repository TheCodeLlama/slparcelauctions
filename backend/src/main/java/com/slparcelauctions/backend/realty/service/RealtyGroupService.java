package com.slparcelauctions.backend.realty.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupMember;
import com.slparcelauctions.backend.realty.RealtyGroupMemberRepository;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.auth.RealtyGroupAuthorizer;
import com.slparcelauctions.backend.realty.dto.CreateRealtyGroupRequest;
import com.slparcelauctions.backend.realty.exception.InvalidWebsiteUrlException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNameTakenException;
import com.slparcelauctions.backend.realty.slug.RealtyGroupSlugFactory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Core mutation API for the realty groups slice. Begins with {@link
 * #createGroup(CreateRealtyGroupRequest, Long)}; subsequent tasks layer on profile edit,
 * dissolve, and per-member permission edits.
 *
 * <p>Membership lifecycle (invite/accept/leave/remove/transfer) lives in a sibling
 * {@code RealtyGroupMembershipService}; invitation lifecycle in {@code
 * RealtyGroupInvitationService}. Splitting keeps each surface independently testable.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class RealtyGroupService {

    private final RealtyGroupRepository groups;
    private final RealtyGroupMemberRepository members;
    private final RealtyGroupSlugFactory slugFactory;
    @SuppressWarnings("unused") // wired now; used by update/dissolve/permissions in later tasks.
    private final RealtyGroupAuthorizer authorizer;

    /**
     * Persist a new realty group with the caller as leader.
     *
     * <p>Single tx: validates case-insensitive name uniqueness against active groups,
     * validates the optional website URL (HTTP/HTTPS only via {@link URI}), derives a
     * collision-free slug, inserts the group, and inserts the leader's member row with
     * empty permissions (leader permissions are all-implicit so the column is unused
     * for that row).
     *
     * <p>If the slug factory returns the placeholder {@code "group"} (the name was
     * non-ASCII / empty-base), the slug is patched to {@code group-<first 8 of
     * publicId>} after the initial save and the row is re-saved. The deferred patch is
     * needed because the publicId is only known once the entity exists.
     */
    public RealtyGroup createGroup(CreateRealtyGroupRequest req, Long creatorUserId) {
        String name = req.name();
        groups.findByNameIgnoreCaseActive(name).ifPresent(existing -> {
            throw new RealtyGroupNameTakenException(name);
        });

        String website = normalizeAndValidateWebsite(req.website());
        String description = blankToNull(req.description());

        String slug = slugFactory.derive(name, null);

        RealtyGroup group = RealtyGroup.builder()
            .name(name)
            .slug(slug)
            .leaderId(creatorUserId)
            .description(description)
            .website(website)
            .build();
        group = groups.save(group);

        if ("group".equals(slug)) {
            String patched = "group-" + group.getPublicId().toString().substring(0, 8);
            group.setSlug(patched);
            group = groups.save(group);
        }

        RealtyGroupMember leaderRow = RealtyGroupMember.builder()
            .groupId(group.getId())
            .userId(creatorUserId)
            .joinedAt(OffsetDateTime.now())
            .build();
        // Leader's permissions field is unused (all-implicit); explicit empty for clarity.
        leaderRow.setPermissionSet(java.util.Collections.emptySet());
        members.save(leaderRow);

        log.info("Created realty group publicId={} name='{}' slug={} leaderId={}",
            group.getPublicId(), group.getName(), group.getSlug(), creatorUserId);
        return group;
    }

    // ─────────────────────── helpers ───────────────────────

    /**
     * Bean Validation only bounds length; URL validity (HTTP/HTTPS only) is enforced
     * here so the same logic applies to every entry point (REST, admin, future LSL).
     * Returns {@code null} for blank/empty input.
     */
    static String normalizeAndValidateWebsite(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        try {
            URI uri = new URI(trimmed);
            String scheme = uri.getScheme();
            if (scheme == null) {
                throw new InvalidWebsiteUrlException("Website URL must include http or https scheme: " + raw);
            }
            String s = scheme.toLowerCase();
            if (!s.equals("http") && !s.equals("https")) {
                throw new InvalidWebsiteUrlException("Website URL scheme must be http or https: " + raw);
            }
            if (uri.getHost() == null) {
                throw new InvalidWebsiteUrlException("Website URL must include a host: " + raw);
            }
            return trimmed;
        } catch (URISyntaxException e) {
            throw new InvalidWebsiteUrlException("Invalid website URL: " + raw);
        }
    }

    static String blankToNull(String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
