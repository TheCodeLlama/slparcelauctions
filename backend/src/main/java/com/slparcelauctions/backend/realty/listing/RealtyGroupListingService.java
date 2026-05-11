package com.slparcelauctions.backend.realty.listing;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionService;
import com.slparcelauctions.backend.auction.dto.AuctionCreateRequest;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.auth.RealtyGroupAuthorizer;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;

import lombok.RequiredArgsConstructor;

/**
 * Sub-project C entry point for listing an auction under a realty group. Wraps
 * {@link AuctionService#create} with: (a) the {@code CREATE_LISTING} permission gate,
 * (b) a snapshot of the group's {@code agent_fee_rate} and {@code agent_fee_split} onto
 * the returned auction row, (c) setting {@code listing_agent} (case 1 of DESIGN.md §4.4.5:
 * agent == seller).
 *
 * <p>Case 2 (agent != seller) and case 3 (SL-group-owned parcel) ship in sub-project E.
 */
@Service
@RequiredArgsConstructor
public class RealtyGroupListingService {

    private final RealtyGroupRepository groups;
    private final RealtyGroupAuthorizer authorizer;
    private final AuctionService auctionService;

    @Transactional
    public Auction createGroupListing(Long callerUserId, AuctionCreateRequest req, String ip) {
        UUID groupPublicId = req.listAsGroupPublicId();
        RealtyGroup group = groups.findByPublicIdAndDissolvedAtIsNull(groupPublicId)
                .orElseThrow(() -> new RealtyGroupNotFoundException(groupPublicId));
        authorizer.assertCan(callerUserId, group.getId(), RealtyGroupPermission.CREATE_LISTING);

        Auction created = auctionService.create(callerUserId, req, ip);
        created.setRealtyGroupId(group.getId());
        created.setListingAgent(created.getSeller()); // case 1: agent == seller
        created.setAgentFeeRate(group.getAgentFeeRate());
        created.setAgentFeeSplit(group.getAgentFeeSplit());
        return created;
    }
}
