package com.slparcelauctions.backend.realty.service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupMember;
import com.slparcelauctions.backend.realty.RealtyGroupMemberRepository;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.auth.RealtyGroupAuthorizer;
import com.slparcelauctions.backend.realty.dto.CreateRealtyGroupRequest;
import com.slparcelauctions.backend.realty.dto.UpdateRealtyGroupRequest;
import com.slparcelauctions.backend.realty.exception.ActiveListingsBlockDissolveException;
import com.slparcelauctions.backend.realty.exception.GroupDissolvedException;
import com.slparcelauctions.backend.realty.exception.InvalidWebsiteUrlException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNameTakenException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupPermissionDeniedException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupRenameCooldownException;
import com.slparcelauctions.backend.realty.exception.SlGroupRegisteredBlocksDissolveException;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
import com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroupRepository;
import com.slparcelauctions.backend.realty.slug.RealtyGroupSlugFactory;
import com.slparcelauctions.backend.realty.wallet.exception.GroupHasInFlightEscrowsException;
import com.slparcelauctions.backend.realty.wallet.exception.GroupHasNonzeroBalanceException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

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

    /** Non-admin renames are gated to one per 30-day window per spec §3.5. */
    static final Duration RENAME_COOLDOWN = Duration.ofDays(30);

    private final RealtyGroupRepository groups;
    private final RealtyGroupMemberRepository members;
    private final RealtyGroupSlugFactory slugFactory;
    private final RealtyGroupAuthorizer authorizer;
    private final NotificationPublisher notifications;
    private final UserRepository users;
    private final AuctionRepository auctions;
    private final EscrowRepository escrows;
    private final RealtyGroupSlGroupRepository slGroupRepo;

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

    // ─────────────────────── update (non-admin) ───────────────────────

    /**
     * Non-admin profile edit. Profile fields (name/description/website) are gated by
     * {@link RealtyGroupPermission#EDIT_GROUP_PROFILE}; fee fields by {@link
     * RealtyGroupPermission#CONFIGURE_FEES}. A rename is on a 30-day cooldown: if
     * {@code lastRenamedAt + 30 days > now}, rejected with {@link
     * RealtyGroupRenameCooldownException}. On a successful rename the slug is
     * recomputed via the slug factory (excluding self) and {@code lastRenamedAt} is
     * bumped to now. Same-name updates (no-op renames) skip the cooldown check.
     */
    public RealtyGroup updateGroup(UUID publicId, UpdateRealtyGroupRequest req, Long callerUserId) {
        RealtyGroup group = loadActive(publicId);

        boolean touchesProfile = req.name() != null || req.description() != null || req.website() != null;
        boolean touchesFees = req.agentFeeRate() != null || req.agentFeeSplit() != null;
        if (touchesProfile) {
            authorizer.assertCan(callerUserId, group.getId(), RealtyGroupPermission.EDIT_GROUP_PROFILE);
        }
        if (touchesFees) {
            authorizer.assertCan(callerUserId, group.getId(), RealtyGroupPermission.CONFIGURE_FEES);
        }

        return applyUpdate(group, req, /* admin = */ false);
    }

    /**
     * Admin profile edit. Bypasses both the EDIT_GROUP_PROFILE/CONFIGURE_FEES gates and
     * the 30-day rename cooldown. Intentionally does NOT bump {@code lastRenamedAt} so
     * a leader is not punished by an admin-initiated rename — the leader's cooldown
     * ledger is only advanced by their own renames.
     */
    public RealtyGroup updateGroupAsAdmin(UUID publicId, UpdateRealtyGroupRequest req, Long adminUserId) {
        RealtyGroup group = loadActive(publicId);
        log.info("Admin {} updating realty group publicId={}", adminUserId, publicId);
        return applyUpdate(group, req, /* admin = */ true);
    }

    private RealtyGroup applyUpdate(RealtyGroup group, UpdateRealtyGroupRequest req, boolean admin) {
        if (req.name() != null) {
            String newName = req.name();
            // Same-name "rename" is a true no-op — no cooldown, no slug recompute, no
            // lastRenamedAt bump. This lets callers PATCH all fields at once without the
            // cooldown firing on unchanged input.
            if (!newName.equals(group.getName())) {
                if (!admin) {
                    OffsetDateTime last = group.getLastRenamedAt();
                    if (last != null) {
                        OffsetDateTime cooldownEnds = last.plus(RENAME_COOLDOWN);
                        if (cooldownEnds.isAfter(OffsetDateTime.now())) {
                            throw new RealtyGroupRenameCooldownException(cooldownEnds);
                        }
                    }
                }
                // Case-insensitive uniqueness, excluding self (the JPQL already filters
                // active-only; this `id`-equality check ensures a row that happens to
                // match its own current name isn't a false-positive collision).
                // De-dupe by publicId (always set at construction); using `id` would
                // false-positive in unit tests where both entities are pre-persist.
                groups.findByNameIgnoreCaseActive(newName).ifPresent(existing -> {
                    if (!existing.getPublicId().equals(group.getPublicId())) {
                        throw new RealtyGroupNameTakenException(newName);
                    }
                });
                group.setName(newName);
                String newSlug = slugFactory.derive(newName, group.getId());
                if ("group".equals(newSlug)) {
                    // Non-ASCII new name — patch slug to publicId-suffixed fallback.
                    newSlug = "group-" + group.getPublicId().toString().substring(0, 8);
                }
                group.setSlug(newSlug);
                if (!admin) {
                    group.setLastRenamedAt(OffsetDateTime.now());
                }
            }
        }

        if (req.description() != null) {
            group.setDescription(blankToNull(req.description()));
        }
        if (req.website() != null) {
            group.setWebsite(normalizeAndValidateWebsite(req.website()));
        }
        if (req.agentFeeRate() != null) {
            group.setAgentFeeRate(req.agentFeeRate());
        }
        if (req.agentFeeSplit() != null) {
            group.setAgentFeeSplit(req.agentFeeSplit());
        }
        return groups.save(group);
    }

    // ─────────────────────── member permissions ───────────────────────

    /**
     * Replace a member's permission set wholesale (not additive). Leader-only —
     * delegating this would let an {@link RealtyGroupPermission#INVITE_AGENTS} delegate
     * escalate themselves via the invite-self / edit-perms loop.
     *
     * <p>Targeting the leader's own membership row is rejected with {@link
     * RealtyGroupPermissionDeniedException}: the leader holds every permission implicitly
     * so the column is unused for that row and must not be edited (it would create
     * misleading state if a future caller starts trusting it).
     *
     * <p>A {@code null} permission set is treated as empty (revoke all flags).
     *
     * <p>When {@code newCommissionRate} is non-null it replaces the member's stored
     * commission rate. A {@code null} value leaves the rate unchanged, so a leader can
     * patch the permission flags without touching the rate.
     */
    public RealtyGroupMember updateMemberPermissions(UUID groupPublicId,
                                                     UUID memberPublicId,
                                                     Set<RealtyGroupPermission> newPerms,
                                                     BigDecimal newCommissionRate,
                                                     Long callerUserId) {
        RealtyGroup group = loadActive(groupPublicId);
        authorizer.assertLeader(callerUserId, group.getId());

        RealtyGroupMember member = members.findByPublicId(memberPublicId)
            .orElseThrow(() -> new RealtyGroupNotFoundException(memberPublicId));
        if (!java.util.Objects.equals(group.getId(), member.getGroupId())) {
            // Member belongs to a different group — surface as not-found in this scope
            // rather than leaking cross-group existence.
            throw new RealtyGroupNotFoundException(memberPublicId);
        }
        if (java.util.Objects.equals(member.getUserId(), group.getLeaderId())) {
            throw new RealtyGroupPermissionDeniedException("Cannot edit leader permissions");
        }

        Set<RealtyGroupPermission> previous = member.permissionSet();
        Set<RealtyGroupPermission> effective = (newPerms == null)
            ? EnumSet.noneOf(RealtyGroupPermission.class)
            : newPerms;
        Set<RealtyGroupPermission> added = EnumSet.noneOf(RealtyGroupPermission.class);
        added.addAll(effective);
        added.removeAll(previous);
        Set<RealtyGroupPermission> removed = EnumSet.noneOf(RealtyGroupPermission.class);
        removed.addAll(previous);
        removed.removeAll(effective);
        member.setPermissionSet(effective);
        if (newCommissionRate != null) {
            member.setAgentCommissionRate(newCommissionRate);
        }
        RealtyGroupMember saved = members.save(member);
        // Skip the notification fire when nothing actually changed; same-set PATCH should
        // not spam the member.
        if (!added.isEmpty() || !removed.isEmpty()) {
            notifications.realtyGroupPermissionsChanged(group, saved, added, removed);
        }
        log.info("Realty group permissions updated: groupPublicId={} memberPublicId={} perms={} callerUserId={}",
            groupPublicId, memberPublicId, effective, callerUserId);
        return saved;
    }

    // ─────────────────────── dissolve ───────────────────────

    /**
     * Leader-initiated dissolve. Sets {@code dissolved_at = NOW()}; the partial unique
     * indexes on {@code name_lower} and {@code slug} immediately allow the name + slug to
     * be reused by a brand-new group. Already-dissolved → {@link
     * GroupDissolvedException} (410). Non-leader → {@link
     * com.slparcelauctions.backend.realty.exception.RealtyGroupPermissionDeniedException}.
     */
    public RealtyGroup dissolveGroup(UUID publicId, Long callerUserId) {
        RealtyGroup group = loadActive(publicId);
        authorizer.assertLeader(callerUserId, group.getId());
        if (auctions.existsActiveListingsByGroupId(group.getId())) {
            throw new ActiveListingsBlockDissolveException();
        }
        if (group.getBalanceLindens() != 0L || group.getReservedLindens() != 0L) {
            throw new GroupHasNonzeroBalanceException();
        }
        if (escrows.existsInFlightForGroup(group.getId())) {
            throw new GroupHasInFlightEscrowsException();
        }
        long slGroupCount = slGroupRepo.countByRealtyGroupId(group.getId());
        if (slGroupCount > 0) {
            throw new SlGroupRegisteredBlocksDissolveException(group.getPublicId(), slGroupCount);
        }
        List<User> formerMembers = loadCurrentMembersAsUsers(group.getId());
        group.setDissolvedAt(OffsetDateTime.now());
        RealtyGroup saved = groups.save(group);
        notifications.realtyGroupDissolved(saved, formerMembers);
        log.info("Realty group dissolved by leader: publicId={} leaderId={}", publicId, callerUserId);
        return saved;
    }

    /**
     * Admin force-dissolve. Bypasses the leader gate; otherwise identical to {@link
     * #dissolveGroup}. Already-dissolved still rejected (no idempotent re-dissolve).
     */
    public RealtyGroup dissolveGroupAsAdmin(UUID publicId, Long adminUserId) {
        RealtyGroup group = loadActive(publicId);
        List<User> formerMembers = loadCurrentMembersAsUsers(group.getId());
        group.setDissolvedAt(OffsetDateTime.now());
        RealtyGroup saved = groups.save(group);
        notifications.realtyGroupDissolved(saved, formerMembers);
        log.info("Realty group force-dissolved by admin: publicId={} adminUserId={}", publicId, adminUserId);
        return saved;
    }

    /**
     * Resolve every current member of the group to its {@link User} entity, in join-order.
     * Used by dissolve flows to fan out notifications. Performs one query for the member
     * rows and one for the user entities; gaps (member row pointing at a vanished user) are
     * skipped silently.
     */
    private List<User> loadCurrentMembersAsUsers(Long groupId) {
        List<RealtyGroupMember> rows = members.findByGroupIdOrderByJoinedAtAsc(groupId);
        if (rows.isEmpty()) return new ArrayList<>();
        List<Long> userIds = new ArrayList<>(rows.size());
        for (RealtyGroupMember row : rows) userIds.add(row.getUserId());
        Map<Long, User> byId = new HashMap<>();
        for (User u : users.findAllById(userIds)) byId.put(u.getId(), u);
        List<User> ordered = new ArrayList<>(rows.size());
        for (RealtyGroupMember row : rows) {
            User u = byId.get(row.getUserId());
            if (u != null) ordered.add(u);
        }
        return ordered;
    }

    // ─────────────────────── helpers ───────────────────────

    /**
     * Public re-export of the active-group lookup for controllers that need a fresh entity
     * after delegating a mutation to another service. Throws {@link
     * RealtyGroupNotFoundException} (404) when the publicId is unknown and {@link
     * GroupDissolvedException} (410) when the group is soft-deleted.
     */
    public RealtyGroup loadActiveByPublicId(UUID publicId) {
        return loadActive(publicId);
    }

    private RealtyGroup loadActive(UUID publicId) {
        RealtyGroup group = groups.findByPublicId(publicId)
            .orElseThrow(() -> new RealtyGroupNotFoundException(publicId));
        if (group.isDissolved()) {
            throw new GroupDissolvedException(group.getPublicId());
        }
        return group;
    }


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
