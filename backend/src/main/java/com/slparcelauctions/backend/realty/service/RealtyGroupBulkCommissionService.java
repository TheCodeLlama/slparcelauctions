package com.slparcelauctions.backend.realty.service;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupMember;
import com.slparcelauctions.backend.realty.RealtyGroupMemberRepository;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.auth.RealtyGroupAuthorizer;
import com.slparcelauctions.backend.realty.dto.BulkCommissionRatesRequest;
import com.slparcelauctions.backend.realty.exception.GroupDissolvedException;
import com.slparcelauctions.backend.realty.exception.MemberNotInGroupException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException;
import com.slparcelauctions.backend.realty.moderation.RealtyGroupGuard;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Leader-side bulk-edit surface for per-member commission rates. Applies a batch of
 * {@code (memberPublicId, rate)} edits in a single transaction so a malformed entry
 * rolls back the entire batch — the caller can re-submit corrected input rather than
 * reasoning about partial writes.
 *
 * <p>Spec §6.7 / §15.1. Authorization uses
 * {@link RealtyGroupPermission#MANAGE_MEMBERS} — the per-member operations capability flag
 * the leader can delegate (alongside the §6.8 analytics view). The leader holds it
 * implicitly via the authorizer's leader-short-circuit, so a leader caller always passes;
 * an agent caller must have been granted the flag explicitly.
 *
 * <p>Suspension is enforced via {@link RealtyGroupGuard#requireGroupCanOperate(Long)}
 * before any rate is touched — a suspended/banned group can't have its commission
 * structure mutated.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RealtyGroupBulkCommissionService {

    private final RealtyGroupRepository groupRepo;
    private final RealtyGroupMemberRepository memberRepo;
    private final RealtyGroupGuard guard;
    private final RealtyGroupAuthorizer authorizer;

    /**
     * Apply every entry in the batch to the named group's members atomically.
     *
     * <ol>
     *   <li>Resolve the group; 404 if missing, 410 if dissolved.</li>
     *   <li>{@link RealtyGroupGuard#requireGroupCanOperate(Long)} — suspended/banned groups
     *       can't edit commissions.</li>
     *   <li>{@link RealtyGroupPermission#MANAGE_MEMBERS} required (leader holds implicitly).</li>
     *   <li>For each entry:
     *     <ul>
     *       <li>Resolve member by publicId; reject {@link MemberNotInGroupException} if
     *           absent or if the row belongs to a different group.</li>
     *       <li>{@link IllegalArgumentException} on negative rate (defense in depth on
     *           top of the DTO's {@code @DecimalMin}).</li>
     *       <li>Replace {@code agent_commission_rate} on the member row.</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * <p>Because the method is {@code @Transactional}, any thrown exception (validation,
     * not-found, suspension) aborts the whole batch — JPA's dirty-flush is suppressed and
     * no partial writes leak. Identical (memberPublicId, rate) pairs are idempotent at the
     * row level; the service does not de-duplicate the input list, so a duplicate entry
     * with conflicting rates is applied in list order with the last write winning.
     */
    @Transactional
    public void updateRates(UUID groupPublicId, Long callerUserId, BulkCommissionRatesRequest req) {
        RealtyGroup group = groupRepo.findByPublicId(groupPublicId)
            .orElseThrow(() -> new RealtyGroupNotFoundException(groupPublicId));
        if (group.isDissolved()) {
            throw new GroupDissolvedException(group.getPublicId());
        }
        guard.requireGroupCanOperate(group.getId());
        authorizer.assertCan(callerUserId, group.getId(), RealtyGroupPermission.MANAGE_MEMBERS);

        for (BulkCommissionRatesRequest.MemberRate entry : req.memberRates()) {
            RealtyGroupMember member = memberRepo.findByPublicId(entry.memberPublicId())
                .orElseThrow(() -> new MemberNotInGroupException(entry.memberPublicId()));
            if (!member.getGroupId().equals(group.getId())) {
                // Cross-group reference — surface as MEMBER_NOT_IN_GROUP rather than
                // leaking cross-tenant existence.
                throw new MemberNotInGroupException(entry.memberPublicId());
            }
            if (entry.rate().compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException(
                    "rate must be >= 0 (got " + entry.rate() + " for member "
                        + entry.memberPublicId() + ")");
            }
            member.setAgentCommissionRate(entry.rate());
        }

        log.info("Bulk commission rate update: groupPublicId={} entries={} callerUserId={}",
            groupPublicId, req.memberRates().size(), callerUserId);
    }
}
