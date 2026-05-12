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
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
import com.slparcelauctions.backend.realty.slgroup.exception.RegisteredSlGroupHasListingsException;
import com.slparcelauctions.backend.realty.slgroup.exception.SlGroupAlreadyRegisteredException;
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

    static final Duration VERIFICATION_TTL = Duration.ofDays(7);

    private final RealtyGroupSlGroupRepository repo;
    private final RealtyGroupRepository groupRepo;
    private final RealtyGroupAuthorizer authorizer;
    private final SlWorldApiClient worldApi;
    private final SlGroupVerificationCodeGenerator codeGen;
    private final AuctionRepository auctionRepo;
    private final SlGroupAboutTextPollTask aboutTextPoller;
    private final Clock clock;

    @Transactional
    public RealtyGroupSlGroup register(Long callerUserId, UUID realtyGroupPublicId, UUID slGroupUuid) {
        RealtyGroup group = groupRepo.findByPublicIdAndDissolvedAtIsNull(realtyGroupPublicId)
                .orElseThrow(() -> new RealtyGroupNotFoundException(realtyGroupPublicId));
        authorizer.assertCan(callerUserId, group.getId(), RealtyGroupPermission.REGISTER_SL_GROUP);

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
                .verificationCodeExpiresAt(now.plus(VERIFICATION_TTL))
                .pollAttempts(0)
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
     * Blocked when any non-terminal case-3 auction still references this row
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
     * Re-runs the about-text poll for a pending registration on demand (spec §5.1).
     * Caller must hold REGISTER_SL_GROUP. Already-verified rows and rows belonging
     * to a different realty group are treated as a no-op success (row returned
     * unchanged) so the caller cannot probe existence.
     *
     * <p>Delegates the actual poll to {@link SlGroupAboutTextPollTask#pollOne}.
     */
    @Transactional
    public RealtyGroupSlGroup recheck(Long callerUserId, UUID realtyGroupPublicId,
                                      UUID slGroupPublicId) {
        RealtyGroup group = groupRepo.findByPublicIdAndDissolvedAtIsNull(realtyGroupPublicId)
                .orElseThrow(() -> new RealtyGroupNotFoundException(realtyGroupPublicId));
        authorizer.assertCan(callerUserId, group.getId(), RealtyGroupPermission.REGISTER_SL_GROUP);
        RealtyGroupSlGroup row = repo.findByPublicId(slGroupPublicId)
                .orElseThrow(() -> new RealtyGroupNotFoundException(slGroupPublicId));
        if (!row.getRealtyGroupId().equals(group.getId()) || row.isVerified()) {
            // already verified or wrong realty group -- caller treats as no-op success
            return row;
        }
        return aboutTextPoller.pollOne(row, OffsetDateTime.now(clock));
    }
}
