package com.slparcelauctions.backend.auction.search;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.auction.search.exception.InvalidFilterValueException;
import com.slparcelauctions.backend.parceltag.ParcelTag;
import com.slparcelauctions.backend.parceltag.ParcelTagRepository;

import lombok.RequiredArgsConstructor;

/**
 * Public browse / search endpoint for auctions. Every parameter is
 * optional; absent params skip their respective predicate. See
 * Epic 07 sub-spec 1 §5.1 for the full surface.
 *
 * <p>The response is wrapped in a 30s public {@code Cache-Control} so
 * intermediate caches and the browser can serve repeats without re-hitting
 * the backend, in addition to the Redis-side {@link SearchResponseCache}
 * read-through that fronts the service layer.
 */
@RestController
@RequestMapping("/api/v1/auctions/search")
@RequiredArgsConstructor
public class AuctionSearchController {

    private final AuctionSearchService service;
    private final ParcelTagRepository parcelTagRepo;

    @GetMapping
    public ResponseEntity<SearchPagedResponse<AuctionSearchResultDto>> search(
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
            @RequestParam(name = "near_region", required = false) String nearRegion,
            @RequestParam(required = false) Integer distance,
            @RequestParam(name = "seller_id", required = false) Long sellerId,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "24") int size) {

        AuctionSearchQuery q = new AuctionSearchQuery(
                region, minArea, maxArea, minPrice, maxPrice,
                toSet(maturity), parseTags(tags),
                TagsMode.fromWire(tagsMode),
                ReserveStatusFilter.fromWire(reserveStatus),
                SnipeProtectionFilter.fromWire(snipeProtection),
                parseVerificationTiers(verificationTier),
                endingWithinHours, nearRegion, distance, sellerId,
                AuctionSearchSort.fromWire(sort),
                page, size);

        SearchPagedResponse<AuctionSearchResultDto> body = service.search(q);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic())
                .body(body);
    }

    private static Set<String> toSet(List<String> list) {
        return list == null || list.isEmpty() ? null : new HashSet<>(list);
    }

    /**
     * Resolves {@code tags=} codes via {@link ParcelTagRepository#findByCodeIn}
     * — one batched query rather than N per-code lookups. Any unknown code
     * (i.e. requested codes minus resolved codes) raises a 400 surfacing the
     * first offender. {@link ParcelTag} is a JPA entity, not an enum, so
     * {@code valueOf} doesn't apply.
     */
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
