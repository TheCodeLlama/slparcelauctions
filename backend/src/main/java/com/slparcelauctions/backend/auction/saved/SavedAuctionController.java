package com.slparcelauctions.backend.auction.saved;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.auction.search.AuctionSearchQuery;
import com.slparcelauctions.backend.auction.search.AuctionSearchResultDto;
import com.slparcelauctions.backend.auction.search.AuctionSearchSort;
import com.slparcelauctions.backend.auction.search.ReserveStatusFilter;
import com.slparcelauctions.backend.auction.search.SearchPagedResponse;
import com.slparcelauctions.backend.auction.search.SnipeProtectionFilter;
import com.slparcelauctions.backend.auction.search.TagsMode;
import com.slparcelauctions.backend.auction.search.exception.InvalidFilterValueException;
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.parceltag.ParcelTag;
import com.slparcelauctions.backend.parceltag.ParcelTagRepository;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Authenticated saved-auctions surface for the bidder dashboard. All routes
 * are gated by the {@code /api/v1/**} catch-all in {@code SecurityConfig};
 * unauth requests resolve to a {@code JwtAuthenticationEntryPoint} 401
 * before this controller is invoked.
 *
 * <p>{@code POST /} is idempotent (duplicate save returns 200 with the
 * existing row's {@code savedAt}); {@code DELETE /{id}} is idempotent
 * (always 204 regardless of row presence). {@code GET /ids} is the hot
 * path for the browse-page heart overlay — {@code Cache-Control: no-store}
 * because the user-private "what have I saved" set must never be cached
 * by a shared intermediate.
 *
 * <p>{@code GET /auctions} is the paginated saved-list view — same filter
 * surface as {@code /auctions/search} except {@code near_region},
 * {@code distance}, and {@code seller_id} (those don't make sense in the
 * saved context).
 */
@RestController
@RequestMapping("/api/v1/me/saved")
@RequiredArgsConstructor
public class SavedAuctionController {

    private final SavedAuctionService service;
    private final ParcelTagRepository parcelTagRepo;
    private final AuctionRepository auctionRepository;

    @PostMapping
    public ResponseEntity<SavedAuctionDto> save(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody SaveAuctionRequest req) {
        SavedAuctionDto dto = service.save(principal.userId(), req.auctionPublicId());
        return ResponseEntity.ok(dto);
    }

    @DeleteMapping("/{auctionPublicId}")
    public ResponseEntity<Void> unsave(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable UUID auctionPublicId) {
        Long auctionId = auctionRepository.findByPublicId(auctionPublicId)
                .map(com.slparcelauctions.backend.auction.Auction::getId)
                .orElseThrow(() -> new AuctionNotFoundException(auctionPublicId));
        service.unsave(principal.userId(), auctionId);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @GetMapping("/ids")
    public ResponseEntity<SavedAuctionIdsResponse> ids(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().mustRevalidate())
                .body(service.listIds(principal.userId()));
    }

    @GetMapping("/auctions")
    public ResponseEntity<SearchPagedResponse<AuctionSearchResultDto>> listAuctions(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(name = "statusFilter", required = false) String statusFilter,
            @RequestParam(required = false) String region,
            @RequestParam(name = "min_area", required = false) Integer minArea,
            @RequestParam(name = "max_area", required = false) Integer maxArea,
            @RequestParam(name = "min_price", required = false) Long minPrice,
            @RequestParam(name = "max_price", required = false) Long maxPrice,
            @RequestParam(required = false) List<String> maturity,
            @RequestParam(required = false) List<String> tags,
            @RequestParam(name = "tags_mode", required = false) String tagsMode,
            @RequestParam(name = "reserve_status", required = false) String reserveStatus,
            @RequestParam(name = "snipe_protection", required = false) String snipeProtection,
            @RequestParam(name = "verification_tier", required = false) List<String> verificationTier,
            @RequestParam(name = "ending_within", required = false) Integer endingWithinHours,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "24") int size) {

        // near_region / distance / seller_id intentionally hardcoded null —
        // they don't make sense in the saved-context view.
        AuctionSearchQuery q = new AuctionSearchQuery(
                region, minArea, maxArea, minPrice, maxPrice,
                toSet(maturity), parseTags(tags),
                TagsMode.fromWire(tagsMode),
                ReserveStatusFilter.fromWire(reserveStatus),
                SnipeProtectionFilter.fromWire(snipeProtection),
                parseVerificationTiers(verificationTier),
                endingWithinHours, null, null, null,
                AuctionSearchSort.fromWire(sort),
                page, size);

        SavedStatusFilter filter = SavedStatusFilter.fromWire(statusFilter);
        return ResponseEntity.ok(service.listPaginated(principal.userId(), q, filter));
    }

    private static Set<String> toSet(List<String> list) {
        return list == null || list.isEmpty() ? null : new HashSet<>(list);
    }

    private Set<ParcelTag> parseTags(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        Set<String> requested = new HashSet<>(raw);
        List<ParcelTag> resolved = parcelTagRepo.findByCodeIn(requested);
        if (resolved.size() < requested.size()) {
            Set<String> resolvedCodes = new HashSet<>();
            for (ParcelTag tag : resolved) {
                resolvedCodes.add(tag.getCode());
            }
            for (String code : requested) {
                if (!resolvedCodes.contains(code)) {
                    throw new InvalidFilterValueException(
                            "tags", code, "known ParcelTag codes");
                }
            }
        }
        return new HashSet<>(resolved);
    }

    private static Set<VerificationTier> parseVerificationTiers(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        Set<VerificationTier> result = new HashSet<>();
        for (String name : raw) {
            try {
                result.add(VerificationTier.valueOf(name));
            } catch (IllegalArgumentException e) {
                throw new InvalidFilterValueException(
                        "verification_tier", name, "SCRIPT, BOT, HUMAN");
            }
        }
        return result;
    }
}
