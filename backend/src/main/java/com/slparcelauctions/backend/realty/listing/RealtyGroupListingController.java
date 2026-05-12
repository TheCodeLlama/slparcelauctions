package com.slparcelauctions.backend.realty.listing;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionDtoMapper;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.dto.PublicAuctionResponse;
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException;

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

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Sub-project E read surface (was sub-project C):
 * <ul>
 *   <li>{@code GET /api/v1/realty/me/listing-eligible-groups?slParcelUuid=...} — caller's
 *       groups under which they can list the given parcel. Now <strong>parcel-aware</strong>:
 *       the parcel must be group-owned and a verified
 *       {@link com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroup registration}
 *       must exist for the parcel's owner SL group UUID, joined with the caller's
 *       {@code CREATE_LISTING} membership (or leader-implicit). Drives the wizard's
 *       List-as picker. Personal land returns an empty list — the wizard then offers the
 *       personal-list path.</li>
 *   <li>{@code GET /api/v1/realty/groups/{publicId}/listings} — paged list of the group's
 *       auctions filtered by {@code status} (CSV; defaults to ACTIVE). Public endpoint.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/realty")
@RequiredArgsConstructor
public class RealtyGroupListingController {

    private final RealtyGroupListingService listingService;
    private final RealtyGroupRepository groups;
    private final AuctionRepository auctions;
    private final AuctionDtoMapper auctionMapper;

    @GetMapping("/me/listing-eligible-groups")
    public List<ListingEligibleGroupDto> listingEligibleGroups(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam("slParcelUuid") UUID slParcelUuid) {
        return listingService.findEligibleForParcel(principal.userId(), slParcelUuid);
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
        // Sub-project G section 6.1 batch wiring for this controller is
        // deferred -- it needs an EscrowRepository injection to drive the
        // batch entry point without regressing per-row escrow resolution.
        // Single-DTO mapper still runs (single per-row group/photo/winner
        // queries) here.
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
