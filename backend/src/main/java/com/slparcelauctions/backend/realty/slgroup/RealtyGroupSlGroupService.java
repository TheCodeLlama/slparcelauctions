package com.slparcelauctions.backend.realty.slgroup;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.auth.RealtyGroupAuthorizer;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupPermissionDeniedException;
import com.slparcelauctions.backend.realty.moderation.RealtyGroupGuard;
import com.slparcelauctions.backend.realty.moderation.RealtyGroupModerationProperties;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
import com.slparcelauctions.backend.realty.slgroup.exception.RegisteredSlGroupHasListingsException;
import com.slparcelauctions.backend.realty.slgroup.exception.SlGroupAlreadyRegisteredException;
import com.slparcelauctions.backend.realty.slgroup.exception.SlGroupFounderMismatchException;
import com.slparcelauctions.backend.realty.slgroup.exception.SlGroupRegisteredToSuspendedGroupException;
import com.slparcelauctions.backend.realty.slgroup.exception.SlGroupVerificationExpiredException;
import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.sl.dto.GroupPageData;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Owns the lifecycle of {@link RealtyGroupSlGroup} rows. Sub-project E spec §7.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RealtyGroupSlGroupService {

    private final RealtyGroupSlGroupRepository repo;
    private final RealtyGroupRepository groupRepo;
    private final RealtyGroupAuthorizer authorizer;
    private final RealtyGroupGuard realtyGroupGuard;
    private final SlWorldApiClient worldApi;
    private final SlGroupVerificationCodeGenerator codeGen;
    private final AuctionRepository auctionRepo;
    private final RealtyGroupModerationProperties realtyProperties;
    private final Clock clock;

    /**
     * SL-group registration verification-code TTL —
     * {@code slpa.realty.sl-group.verification-ttl-days} days (default 7).
     */
    private Duration verificationTtl() {
        return Duration.ofDays(realtyProperties.getSlGroup().getVerificationTtlDays());
    }

    @Transactional
    public RealtyGroupSlGroup register(Long callerUserId, UUID realtyGroupPublicId, UUID slGroupUuid) {
        RealtyGroup group = groupRepo.findByPublicIdAndDissolvedAtIsNull(realtyGroupPublicId)
                .orElseThrow(() -> new RealtyGroupNotFoundException(realtyGroupPublicId));
        realtyGroupGuard.requireGroupCanOperate(group.getId());
        authorizer.assertCan(callerUserId, group.getId(), RealtyGroupPermission.REGISTER_SL_GROUP);

        // Sub-project G section 14 -- reverse-search ban-evasion gate. If the SL
        // group UUID is currently registered to any realty group that has an
        // active (unlifted) suspension row, hard-block the registration with a
        // distinct error code so the UI can surface a "contact support" message
        // rather than the generic "already registered" copy. This gate fires
        // before the uniqueness check so the stronger constraint wins.
        if (repo.existsForSuspendedRealtyGroup(slGroupUuid)) {
            throw new SlGroupRegisteredToSuspendedGroupException(slGroupUuid);
        }

        repo.findBySlGroupUuid(slGroupUuid).ifPresent(existing -> {
            throw new SlGroupAlreadyRegisteredException(slGroupUuid);
        });

        // Fetch the SL group page to capture sl_group_name. World API errors propagate so the
        // controller can render a 422 with diagnostic; we don't squat on a UUID we couldn't
        // confirm existed in SL.
        GroupPageData page = worldApi.fetchGroupPage(slGroupUuid).block();
        String slGroupName = page == null ? null : page.name();

        OffsetDateTime now = OffsetDateTime.now(clock);
        RealtyGroupSlGroup row = RealtyGroupSlGroup.builder()
                .realtyGroupId(group.getId())
                .slGroupUuid(slGroupUuid)
                .slGroupName(slGroupName)
                .verified(false)
                .verificationCode(codeGen.generate())
                .verificationCodeExpiresAt(now.plus(verificationTtl()))
                .build();
        RealtyGroupSlGroup saved = repo.save(row);
        log.info("SL group registered (pending): realtyGroupId={} slGroupUuid={} code={}",
                group.getId(), slGroupUuid, saved.getVerificationCode());
        return saved;
    }

    /**
     * Lists every SL group registration (pending and verified) belonging to the
     * given realty group, newest first. Any group member can view; non-members
     * receive a 403. Spec §5.1.
     */
    @Transactional(readOnly = true)
    public List<RealtyGroupSlGroup> listForGroup(Long callerUserId, UUID realtyGroupPublicId) {
        RealtyGroup group = groupRepo.findByPublicIdAndDissolvedAtIsNull(realtyGroupPublicId)
                .orElseThrow(() -> new RealtyGroupNotFoundException(realtyGroupPublicId));
        if (!authorizer.isMember(callerUserId, group.getId())) {
            throw new RealtyGroupPermissionDeniedException("Not a member");
        }
        return repo.findByRealtyGroupIdOrderByCreatedAtDesc(group.getId());
    }

    /**
     * Removes an SL group registration. Caller must hold REGISTER_SL_GROUP.
     * Blocked when any non-terminal group-sale auction still references this row
     * (spec §12.2). Rows belonging to a different realty group surface as
     * not-found so existence isn't leaked across tenants.
     */
    @Transactional
    public void unregister(Long callerUserId, UUID realtyGroupPublicId, UUID slGroupPublicId) {
        RealtyGroup group = groupRepo.findByPublicIdAndDissolvedAtIsNull(realtyGroupPublicId)
                .orElseThrow(() -> new RealtyGroupNotFoundException(realtyGroupPublicId));
        authorizer.assertCan(callerUserId, group.getId(), RealtyGroupPermission.REGISTER_SL_GROUP);

        RealtyGroupSlGroup row = repo.findByPublicId(slGroupPublicId)
                .orElseThrow(() -> new RealtyGroupNotFoundException(slGroupPublicId));

        if (!row.getRealtyGroupId().equals(group.getId())) {
            // Don't leak existence of an SL group registered to a different realty group.
            throw new RealtyGroupNotFoundException(slGroupPublicId);
        }

        if (auctionRepo.existsCase3ForSlGroup(row.getId())) {
            throw new RegisteredSlGroupHasListingsException(slGroupPublicId);
        }
        repo.delete(row);
        log.info("SL group unregistered: realtyGroupId={} slGroupPublicId={}",
                group.getId(), slGroupPublicId);
    }

    /**
     * Re-runs verification for a pending registration on demand (spec §5.1).
     * Caller must hold REGISTER_SL_GROUP.
     *
     * <p>{@code FOUNDER_TERMINAL} is the only verification path and is driven by
     * an in-world LSL callback, not by a backend-initiated poll. {@code recheck}
     * therefore returns the row unchanged for any pending registration -- the
     * endpoint is preserved so the existing UI affordance keeps compiling, but
     * it no longer mutates state. Rows belonging to a different realty group
     * surface as a no-op success so the caller cannot probe existence.
     */
    @Transactional
    public RealtyGroupSlGroup recheck(Long callerUserId, UUID realtyGroupPublicId,
                                      UUID slGroupPublicId) {
        RealtyGroup group = groupRepo.findByPublicIdAndDissolvedAtIsNull(realtyGroupPublicId)
                .orElseThrow(() -> new RealtyGroupNotFoundException(realtyGroupPublicId));
        authorizer.assertCan(callerUserId, group.getId(), RealtyGroupPermission.REGISTER_SL_GROUP);
        return repo.findByPublicId(slGroupPublicId)
                .orElseThrow(() -> new RealtyGroupNotFoundException(slGroupPublicId));
    }

    /**
     * LSL founder-terminal callback path (spec §5.1, §7.3). The terminal owner-says
     * the verification code typed in-world and the avatar UUID of the resident
     * currently on the terminal. Looks up the pending registration by code, fetches
     * the SL group's page, and flips the row to verified iff the page-reported
     * founder matches the terminal avatar.
     *
     * <p>World API failures surface as a founder mismatch — the terminal owner-says
     * the same diagnostic line for either case, and we never want to verify against
     * a founder UUID we couldn't actually confirm.
     */
    @Transactional
    public RealtyGroupSlGroup handleTerminalCallback(String verificationCode, UUID founderAvatarUuid) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        RealtyGroupSlGroup row = repo.findPendingByCode(verificationCode, now)
                .orElseThrow(() -> new SlGroupVerificationExpiredException(null));

        GroupPageData page;
        try {
            page = worldApi.fetchGroupPage(row.getSlGroupUuid()).block();
        } catch (RuntimeException e) {
            // World API failure surfaces as a founder mismatch; the terminal owner-says.
            throw new SlGroupFounderMismatchException(founderAvatarUuid, null);
        }
        if (page == null || page.founderUuid() == null) {
            throw new SlGroupFounderMismatchException(founderAvatarUuid, null);
        }
        if (!page.founderUuid().equals(founderAvatarUuid)) {
            throw new SlGroupFounderMismatchException(founderAvatarUuid, page.founderUuid());
        }

        row.setVerified(true);
        row.setVerifiedAt(now);
        row.setVerifiedVia(SlGroupVerifyMethod.FOUNDER_TERMINAL);
        row.setFounderAvatarUuid(founderAvatarUuid);
        row.setVerificationCode(null);
        if (page.name() != null && row.getSlGroupName() == null) {
            row.setSlGroupName(page.name());
        }
        return repo.save(row);
    }
}
