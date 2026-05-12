package com.slparcelauctions.backend.realty.slgroup;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.auth.RealtyGroupAuthorizer;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
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
}
