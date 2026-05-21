package com.slparcelauctions.backend.realty.listing;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionService;
import com.slparcelauctions.backend.auction.dto.AuctionCreateRequest;
import com.slparcelauctions.backend.parcel.ParcelLookupService;
import com.slparcelauctions.backend.parcel.dto.ParcelResponse;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupMember;
import com.slparcelauctions.backend.realty.RealtyGroupMemberRepository;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.auth.RealtyGroupAuthorizer;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException;
import com.slparcelauctions.backend.realty.moderation.RealtyGroupGuard;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
import com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroup;
import com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroupRepository;
import com.slparcelauctions.backend.realty.slgroup.exception.ParcelNotOwnedByRegisteredSlGroupException;

import java.math.BigDecimal;

import lombok.RequiredArgsConstructor;

/**
 * Entry point for listing an auction under a realty group. Wraps
 * {@link AuctionService#create} with the {@code CREATE_LISTING} permission gate, then
 * applies sub-project E group-sale validation + snapshot:
 * <ul>
 *   <li>The parcel must be group-owned ({@code ownerType == "group"}); personal land
 *       cannot list under a realty group.</li>
 *   <li>The parcel's owner SL group UUID must have a verified registration
 *       ({@link RealtyGroupSlGroup}) for the realty group the caller is listing under.</li>
 *   <li>The caller's per-member commission rate (from {@link RealtyGroupMember}) is
 *       snapshotted onto {@link Auction#getAgentCommissionRate()}.</li>
 *   <li>{@code listingAgent} is set to the seller (group sale: agent == seller).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class RealtyGroupListingService {

    private final RealtyGroupRepository groups;
    private final RealtyGroupMemberRepository members;
    private final RealtyGroupSlGroupRepository slGroups;
    private final RealtyGroupAuthorizer authorizer;
    private final RealtyGroupGuard realtyGroupGuard;
    private final AuctionService auctionService;
    private final ParcelLookupService parcelLookupService;

    @Transactional
    public Auction createGroupListing(Long callerUserId, AuctionCreateRequest req, String ip) {
        UUID groupPublicId = req.listAsGroupPublicId();
        RealtyGroup group = groups.findByPublicIdAndDissolvedAtIsNull(groupPublicId)
                .orElseThrow(() -> new RealtyGroupNotFoundException(groupPublicId));
        realtyGroupGuard.requireGroupCanOperate(group.getId());
        authorizer.assertCan(callerUserId, group.getId(), RealtyGroupPermission.CREATE_LISTING);

        // Look up the parcel up-front so we can validate ownership before any side effects.
        ParcelLookupService.ParcelLookupResult lookup = parcelLookupService.lookup(req.slParcelUuid());
        if (lookup.response() == null
                || !"group".equalsIgnoreCase(lookup.response().ownerType())) {
            throw new ParcelNotOwnedByRegisteredSlGroupException(
                    req.slParcelUuid(), groupPublicId,
                    "Personal land cannot list under a realty group.");
        }
        UUID slOwner = lookup.response().ownerUuid();
        if (slOwner == null) {
            throw new ParcelNotOwnedByRegisteredSlGroupException(
                    req.slParcelUuid(), groupPublicId,
                    "Parcel ownership UUID is missing from the World API response.");
        }
        RealtyGroupSlGroup slGroup = slGroups.findVerifiedForListing(group.getId(), slOwner)
                .orElseThrow(() -> new ParcelNotOwnedByRegisteredSlGroupException(
                        req.slParcelUuid(), groupPublicId,
                        "The SL group that owns this parcel is not registered/verified to "
                                + group.getName()));

        // Snapshot the member's commission rate.
        BigDecimal commissionRate = members
                .findCommissionRate(group.getId(), callerUserId)
                .orElse(BigDecimal.ZERO);

        Auction created = auctionService.create(callerUserId, req, ip);
        created.setRealtyGroupId(group.getId());
        created.setRealtyGroupSlGroupId(slGroup.getId());
        created.setListingAgent(created.getSeller());
        created.setAgentCommissionRate(commissionRate);
        return created;
    }

    /**
     * Sub-project E §5.3 — parcel-aware listing-eligible-groups read.
     *
     * <p>Returns the realty groups under which the caller can list the given parcel:
     * <ol>
     *   <li>Look the parcel up via the SL World API. If it's not group-owned (e.g.
     *       {@code ownerType == "agent"}, i.e. personal land), no group is eligible — the
     *       wizard will fall through to the personal-list path.</li>
     *   <li>Otherwise, find the verified {@code realty_group_sl_groups} rows for the
     *       parcel's owner SL group UUID, joined with the caller's membership; filter to
     *       members with {@code CREATE_LISTING} (or who are the group's leader, who implicitly
     *       hold every permission).</li>
     * </ol>
     *
     * <p>The per-member commission rate lives on the
     * {@link RealtyGroupMember#getAgentCommissionRate()} row, not the group, and is
     * projected directly into each row's {@link ListingEligibleGroupDto#agentCommissionRate()}
     * so the wizard's fee-preview renders without a second round-trip. Leaders also
     * hold a member row by convention; if a caller is missing one (defensive edge case)
     * the rate defaults to {@link BigDecimal#ZERO}.
     */
    @Transactional(readOnly = true)
    public List<ListingEligibleGroupDto> findEligibleForParcel(
            Long callerUserId, UUID slParcelUuid) {
        ParcelLookupService.ParcelLookupResult lookup = parcelLookupService.lookup(slParcelUuid);
        ParcelResponse parcel = lookup == null ? null : lookup.response();
        if (parcel == null || parcel.ownerType() == null
                || !"group".equalsIgnoreCase(parcel.ownerType())) {
            // Personal land (or unknown owner type) -> no realty group is eligible.
            return List.of();
        }
        UUID slOwnerUuid = parcel.ownerUuid();
        if (slOwnerUuid == null) {
            return List.of();
        }

        List<RealtyGroup> candidates =
                slGroups.findRealtyGroupsForListingCaller(callerUserId, slOwnerUuid);
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<ListingEligibleGroupDto> out = new ArrayList<>(candidates.size());
        for (RealtyGroup g : candidates) {
            boolean leader = Objects.equals(g.getLeaderId(), callerUserId);
            boolean hasPerm = false;
            if (!leader) {
                Optional<RealtyGroupMember> m =
                        members.findByGroupIdAndUserId(g.getId(), callerUserId);
                if (m.isPresent()) {
                    hasPerm = m.get().permissionSet()
                            .contains(RealtyGroupPermission.CREATE_LISTING);
                }
            }
            if (!leader && !hasPerm) {
                continue;
            }
            BigDecimal commissionRate = members
                    .findCommissionRate(g.getId(), callerUserId)
                    .orElse(BigDecimal.ZERO);
            String logoUrl = g.getLogoLightObjectKey() == null
                    ? null
                    : "/api/v1/realty-groups/" + g.getPublicId() + "/logo/image";
            out.add(new ListingEligibleGroupDto(
                    g.getPublicId(), g.getName(), g.getSlug(), logoUrl, commissionRate));
        }
        return out;
    }
}
