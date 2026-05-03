package com.slparcelauctions.backend.auction;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.admin.ban.BanCheckService;
import com.slparcelauctions.backend.auction.dto.AuctionCreateRequest;
import com.slparcelauctions.backend.auction.dto.AuctionUpdateRequest;
import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.auction.exception.InvalidAuctionStateException;
import com.slparcelauctions.backend.auction.exception.SellerSuspendedException;
import com.slparcelauctions.backend.auction.exception.SuspensionReason;
import com.slparcelauctions.backend.parcel.ParcelLookupService;
import com.slparcelauctions.backend.parcel.ParcelLookupService.ParcelLookupResult;
import com.slparcelauctions.backend.parcel.dto.ParcelResponse;
import com.slparcelauctions.backend.parceltag.ParcelTag;
import com.slparcelauctions.backend.parceltag.ParcelTagRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuctionService {

    private static final Set<Integer> ALLOWED_DURATIONS = Set.of(24, 48, 72, 168, 336);
    private static final Set<Integer> ALLOWED_SNIPE_WINDOWS = Set.of(5, 10, 15, 30, 60);

    private final AuctionRepository auctionRepo;
    private final UserRepository userRepo;
    private final ParcelTagRepository tagRepo;
    private final BanCheckService banCheckService;
    private final ParcelLookupService parcelLookupService;
    private final ParcelSnapshotPhotoService parcelSnapshotPhotoService;
    private final Clock clock;

    @Value("${slpa.commission.default-rate:0.05}")
    private BigDecimal defaultCommissionRate;

    @Transactional
    public Auction create(Long sellerId, AuctionCreateRequest req, String ipAddress) {
        validateTitle(req.title());
        validatePricing(req.startingBid(), req.reservePrice(), req.buyNowPrice());
        validateDuration(req.durationHours());
        validateSnipe(req.snipeProtect(), req.snipeWindowMin());

        User seller = userRepo.findById(sellerId)
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found: " + sellerId));

        banCheckService.assertNotBanned(ipAddress, seller.getSlAvatarUuid());

        // Listing-creation suspension gate (Epic 08 sub-spec 2 §7.7). Order is
        // most-restrictive-first: a permanent ban shadows a timed suspension,
        // which shadows an outstanding penalty balance. The first match
        // surfaces as the {@code code} on the 403 ProblemDetail so the
        // frontend can branch on the discriminator without parsing the
        // human-readable detail.
        SuspensionReason suspended = checkCanCreateListing(seller);
        if (suspended != null) {
            throw new SellerSuspendedException(suspended);
        }

        ParcelLookupResult lookupResult = parcelLookupService.lookup(req.slParcelUuid());

        Set<ParcelTag> tags = resolveTags(req.tags());

        // Per sub-spec 2 §7.1, verificationMethod is NOT set at create time — it
        // is chosen by the seller on PUT /auctions/{id}/verify and persisted by
        // AuctionVerificationService.triggerVerification(...). The entity column
        // is nullable until that point.
        Auction a = Auction.builder()
                .seller(seller)
                .status(AuctionStatus.DRAFT)
                .title(req.title())
                .startingBid(req.startingBid())
                .reservePrice(req.reservePrice())
                .buyNowPrice(req.buyNowPrice())
                .durationHours(req.durationHours())
                .snipeProtect(req.snipeProtect())
                .snipeWindowMin(Boolean.TRUE.equals(req.snipeProtect()) ? req.snipeWindowMin() : null)
                .sellerDesc(req.sellerDesc())
                .tags(tags)
                .commissionRate(defaultCommissionRate)
                .agentFeeRate(BigDecimal.ZERO)
                .currentBid(0L)
                .bidCount(0)
                .listingFeePaid(false)
                // slParcelUuid will be set via setParcelSnapshot below
                .slParcelUuid(req.slParcelUuid())
                .build();

        AuctionParcelSnapshot snapshot = buildSnapshot(lookupResult);
        a.setParcelSnapshot(snapshot);

        a = auctionRepo.save(a);
        log.info("Auction created: id={}, sellerId={}, slParcelUuid={}",
                a.getId(), sellerId, a.getSlParcelUuid());

        parcelSnapshotPhotoService.refreshFor(a, lookupResult.response().snapshotUrl());
        return a;
    }

    @Transactional
    public Auction update(Long auctionId, Long sellerId, AuctionUpdateRequest req) {
        Auction a = loadForSeller(auctionId, sellerId);
        if (a.getStatus() != AuctionStatus.DRAFT && a.getStatus() != AuctionStatus.DRAFT_PAID) {
            throw new InvalidAuctionStateException(auctionId, a.getStatus(), "UPDATE");
        }

        // verificationMethod is intentionally not editable here — it is chosen
        // on PUT /auctions/{id}/verify so the group-land gate in
        // AuctionVerificationService.triggerVerification() is the single
        // enforcement point (sub-spec 2 §7.1/§7.2).
        if (req.title() != null) {
            // null = "don't touch", but an explicit blank must be rejected so
            // partial updates can't sneak past the @NotBlank rule on create.
            // Length check is duplicated here (and on AuctionUpdateRequest) so
            // direct service callers can't bypass the controller-boundary @Size
            // — keeps create/update validation symmetric.
            if (req.title().isBlank()) {
                throw new IllegalArgumentException("title must not be blank");
            }
            if (req.title().length() > 120) {
                throw new IllegalArgumentException("title must be at most 120 characters");
            }
            a.setTitle(req.title());
        }
        if (req.startingBid() != null) {
            a.setStartingBid(req.startingBid());
        }
        if (req.reservePrice() != null) {
            a.setReservePrice(req.reservePrice() < 0 ? null : req.reservePrice());
        }
        if (req.buyNowPrice() != null) {
            a.setBuyNowPrice(req.buyNowPrice() < 0 ? null : req.buyNowPrice());
        }
        validatePricing(a.getStartingBid(), a.getReservePrice(), a.getBuyNowPrice());
        if (req.durationHours() != null) {
            validateDuration(req.durationHours());
            a.setDurationHours(req.durationHours());
        }
        if (req.snipeProtect() != null) {
            a.setSnipeProtect(req.snipeProtect());
            if (!req.snipeProtect()) {
                a.setSnipeWindowMin(null);
            }
        }
        if (req.snipeWindowMin() != null) {
            a.setSnipeWindowMin(req.snipeWindowMin());
        }
        validateSnipe(a.getSnipeProtect(), a.getSnipeWindowMin());
        if (req.sellerDesc() != null) {
            a.setSellerDesc(req.sellerDesc());
        }
        if (req.tags() != null) {
            a.setTags(resolveTags(req.tags()));
        }

        // Re-lookup if a new (different) slParcelUuid was supplied
        if (req.slParcelUuid() != null && !req.slParcelUuid().equals(a.getSlParcelUuid())) {
            ParcelLookupResult lookupResult = parcelLookupService.lookup(req.slParcelUuid());
            AuctionParcelSnapshot existing = a.getParcelSnapshot();
            if (existing != null) {
                applyLookupToSnapshot(existing, lookupResult);
            } else {
                a.setParcelSnapshot(buildSnapshot(lookupResult));
            }
            a = auctionRepo.save(a);
            parcelSnapshotPhotoService.refreshFor(a, lookupResult.response().snapshotUrl());
            return a;
        }

        return auctionRepo.save(a);
    }

    @Transactional(readOnly = true)
    public Auction loadForSeller(Long auctionId, Long sellerId) {
        return auctionRepo.findByIdAndSellerId(auctionId, sellerId)
                .orElseThrow(() -> new AuctionNotFoundException(auctionId));
    }

    @Transactional(readOnly = true)
    public Auction load(Long auctionId) {
        return auctionRepo.findById(auctionId)
                .orElseThrow(() -> new AuctionNotFoundException(auctionId));
    }

    /**
     * Listing-detail load path used by {@code GET /api/v1/auctions/{id}}.
     * Differs from {@link #load(Long)} by hydrating {@code seller} and
     * {@code photos} (in addition to the standard {@code parcel} +
     * {@code tags}) so the public detail mapper can render the seller card
     * and photo carousel without fanning out to extra lazy fetches.
     */
    @Transactional(readOnly = true)
    public Auction loadForDetail(Long auctionId) {
        return auctionRepo.findByIdForDetail(auctionId)
                .orElseThrow(() -> new AuctionNotFoundException(auctionId));
    }

    @Transactional(readOnly = true)
    public List<Auction> loadOwnedBy(Long sellerId) {
        return auctionRepo.findBySellerIdOrderByCreatedAtDesc(sellerId);
    }

    /**
     * Paginated active listings for the public user profile (spec §14).
     * SUSPENDED and pre-ACTIVE statuses are excluded at the repository level
     * regardless of requester identity.
     *
     * <p>Implemented as a two-query pattern to avoid Hibernate's
     * {@code HHH90003004} in-memory pagination warning — see
     * {@link AuctionRepository#findActiveBySellerIdIds}. First query pages the
     * IDs at the DB; second query hydrates just those IDs with
     * {@code parcel} + {@code tags} eagerly fetched so the downstream mapper
     * runs outside the transaction boundary. The hydrated list is re-sequenced
     * against the ID page to preserve the page's {@code endsAt ASC} order and
     * wrapped in a {@link PageImpl} that carries the original total.
     */
    @Transactional(readOnly = true)
    public Page<Auction> loadActiveBySeller(Long sellerId, Pageable pageable) {
        Page<Long> idsPage = auctionRepo.findActiveBySellerIdIds(sellerId, pageable);
        List<Long> ids = idsPage.getContent();
        if (ids.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, idsPage.getTotalElements());
        }
        List<Auction> hydrated = auctionRepo.findAllByIdInWithParcelAndTags(ids);
        // Preserve page order — IN-clause results aren't order-preserving across
        // DBs even with ORDER BY a.endsAt on the hydration query, because ties
        // (equal endsAt) may arrive in any order. Re-sequence by the ID page.
        Map<Long, Auction> byId = new HashMap<>(hydrated.size());
        for (Auction a : hydrated) {
            byId.put(a.getId(), a);
        }
        List<Auction> ordered = new ArrayList<>(ids.size());
        for (Long id : ids) {
            Auction a = byId.get(id);
            if (a != null) {
                ordered.add(a);
            }
        }
        return new PageImpl<>(ordered, pageable, idsPage.getTotalElements());
    }

    /**
     * Builds a new {@link AuctionParcelSnapshot} from a lookup result.
     * The {@code auction} back-reference is intentionally NOT set here —
     * callers set it via {@link Auction#setParcelSnapshot(AuctionParcelSnapshot)}.
     */
    private AuctionParcelSnapshot buildSnapshot(ParcelLookupResult lookup) {
        ParcelResponse r = lookup.response();
        return AuctionParcelSnapshot.builder()
                .slParcelUuid(r.slParcelUuid())
                .ownerUuid(r.ownerUuid())
                .ownerType(r.ownerType())
                .ownerName(r.ownerName())
                .parcelName(r.parcelName())
                .region(lookup.region())
                .regionName(r.regionName())
                .regionMaturityRating(r.regionMaturityRating())
                .areaSqm(r.areaSqm())
                .description(r.description())
                .positionX(r.positionX())
                .positionY(r.positionY())
                .positionZ(r.positionZ())
                .slurl(r.slurl())
                .verifiedAt(r.verifiedAt())
                .lastChecked(r.lastChecked())
                .build();
    }

    /**
     * Refreshes an existing snapshot's fields in place from a new lookup result.
     * Used by {@link #update} when the seller supplies a new {@code slParcelUuid}.
     */
    private void applyLookupToSnapshot(AuctionParcelSnapshot snap, ParcelLookupResult lookup) {
        ParcelResponse r = lookup.response();
        snap.setSlParcelUuid(r.slParcelUuid());
        snap.setOwnerUuid(r.ownerUuid());
        snap.setOwnerType(r.ownerType());
        snap.setOwnerName(r.ownerName());
        snap.setParcelName(r.parcelName());
        snap.setRegion(lookup.region());
        snap.setRegionName(r.regionName());
        snap.setRegionMaturityRating(r.regionMaturityRating());
        snap.setAreaSqm(r.areaSqm());
        snap.setDescription(r.description());
        snap.setPositionX(r.positionX());
        snap.setPositionY(r.positionY());
        snap.setPositionZ(r.positionZ());
        snap.setSlurl(r.slurl());
        snap.setVerifiedAt(r.verifiedAt());
        snap.setLastChecked(r.lastChecked());
    }

    /**
     * Returns the most-restrictive {@link SuspensionReason} that bars the
     * caller from creating a new listing, or {@code null} if no condition
     * applies. Order matters: ban → timed → penalty. See Epic 08 sub-spec 2
     * §7.7.
     */
    private SuspensionReason checkCanCreateListing(User u) {
        if (Boolean.TRUE.equals(u.getBannedFromListing())) {
            return SuspensionReason.PERMANENT_BAN;
        }
        if (u.getListingSuspensionUntil() != null
                && OffsetDateTime.now(clock).isBefore(u.getListingSuspensionUntil())) {
            return SuspensionReason.TIMED_SUSPENSION;
        }
        if (u.getPenaltyBalanceOwed() != null && u.getPenaltyBalanceOwed() > 0L) {
            return SuspensionReason.PENALTY_OWED;
        }
        return null;
    }

    private Set<ParcelTag> resolveTags(Set<String> codes) {
        if (codes == null || codes.isEmpty()) return new HashSet<>();
        List<ParcelTag> found = tagRepo.findByCodeIn(codes);
        if (found.size() != codes.size()) {
            Set<String> foundCodes = found.stream().map(ParcelTag::getCode).collect(java.util.stream.Collectors.toSet());
            Set<String> missing = new HashSet<>(codes);
            missing.removeAll(foundCodes);
            throw new IllegalArgumentException("Unknown parcel tag codes: " + missing);
        }
        return new HashSet<>(found);
    }

    private void validateTitle(String title) {
        // JSR-380 @NotBlank/@Size on AuctionCreateRequest.title runs at the
        // controller boundary; this duplicate guard catches direct service
        // callers (tests, future internal flows) so the invariant holds
        // regardless of entry point.
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        if (title.length() > 120) {
            throw new IllegalArgumentException("title must be at most 120 characters");
        }
    }

    private void validatePricing(Long starting, Long reserve, Long buyNow) {
        if (starting == null || starting < 1) {
            throw new IllegalArgumentException("startingBid must be >= 1");
        }
        if (reserve != null && reserve < starting) {
            throw new IllegalArgumentException("reservePrice must be >= startingBid");
        }
        if (buyNow != null) {
            long floor = Math.max(starting, reserve == null ? 0L : reserve);
            if (buyNow < floor) {
                throw new IllegalArgumentException("buyNowPrice must be >= max(startingBid, reservePrice)");
            }
        }
    }

    private void validateDuration(Integer durationHours) {
        if (durationHours == null || !ALLOWED_DURATIONS.contains(durationHours)) {
            throw new IllegalArgumentException(
                    "durationHours must be one of " + ALLOWED_DURATIONS);
        }
    }

    private void validateSnipe(Boolean snipeProtect, Integer snipeWindowMin) {
        if (Boolean.TRUE.equals(snipeProtect)) {
            if (snipeWindowMin == null || !ALLOWED_SNIPE_WINDOWS.contains(snipeWindowMin)) {
                throw new IllegalArgumentException(
                        "snipeWindowMin must be one of " + ALLOWED_SNIPE_WINDOWS + " when snipeProtect is true");
            }
        } else if (snipeWindowMin != null) {
            throw new IllegalArgumentException("snipeWindowMin must be null when snipeProtect is false");
        }
    }
}
