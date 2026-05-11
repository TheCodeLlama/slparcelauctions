package com.slparcelauctions.backend.realty.listing;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionDtoMapper;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.dto.PublicAuctionResponse;
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupMember;
import com.slparcelauctions.backend.realty.RealtyGroupMemberRepository;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Sub-project C read surface:
 * <ul>
 *   <li>{@code GET /api/v1/realty/me/listing-eligible-groups} — caller's groups where they
 *       hold {@code CREATE_LISTING} (or are leader → all-implicit). Drives the wizard's
 *       List-as picker.</li>
 *   <li>{@code GET /api/v1/realty/groups/{publicId}/listings} — paged list of the group's
 *       auctions filtered by {@code status} (CSV; defaults to ACTIVE). Public endpoint.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/realty")
@RequiredArgsConstructor
public class RealtyGroupListingController {

    private final RealtyGroupMemberRepository members;
    private final RealtyGroupRepository groups;
    private final AuctionRepository auctions;
    private final AuctionDtoMapper auctionMapper;

    @GetMapping("/me/listing-eligible-groups")
    @Transactional(readOnly = true)
    public List<ListingEligibleGroupDto> myEligibleGroups(
            @AuthenticationPrincipal AuthPrincipal principal) {
        List<RealtyGroupMember> myMemberships =
                members.findByUserIdOrderByJoinedAtDesc(principal.userId());
        List<ListingEligibleGroupDto> out = new ArrayList<>(myMemberships.size());
        for (RealtyGroupMember m : myMemberships) {
            RealtyGroup g = groups.findById(m.getGroupId()).orElse(null);
            if (g == null || g.getDissolvedAt() != null) continue;
            boolean leader = Objects.equals(g.getLeaderId(), principal.userId());
            boolean hasPerm = m.permissionSet().contains(RealtyGroupPermission.CREATE_LISTING);
            if (!leader && !hasPerm) continue;
            String logoUrl = g.getLogoObjectKey() == null
                    ? null
                    : "/api/v1/realty-groups/" + g.getPublicId() + "/logo/image";
            out.add(new ListingEligibleGroupDto(
                    g.getPublicId(), g.getName(), g.getSlug(), logoUrl, g.getAgentFeeRate()));
        }
        return out;
    }

    @GetMapping("/groups/{publicId}/listings")
    @Transactional(readOnly = true)
    public Page<PublicAuctionResponse> groupListings(
            @PathVariable UUID publicId,
            @RequestParam(defaultValue = "ACTIVE") String status,
            @PageableDefault(size = 20) Pageable pageable) {
        RealtyGroup g = groups.findByPublicId(publicId)
                .orElseThrow(() -> new RealtyGroupNotFoundException(publicId));
        Set<AuctionStatus> statuses = parseStatuses(status);
        Page<Auction> page = auctions.findByRealtyGroupIdAndStatusIn(g.getId(), statuses, pageable);
        return page.map(auctionMapper::toPublicResponse);
    }

    private static Set<AuctionStatus> parseStatuses(String csv) {
        Set<AuctionStatus> out = new HashSet<>();
        for (String s : Arrays.asList(csv.split(","))) {
            out.add(AuctionStatus.valueOf(s.trim().toUpperCase()));
        }
        return out;
    }
}
