package com.slparcelauctions.backend.auction;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.dto.AuctionCreateRequest;
import com.slparcelauctions.backend.auction.dto.AuctionUpdateRequest;
import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.auction.exception.InvalidAuctionStateException;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
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
    private final ParcelRepository parcelRepo;
    private final UserRepository userRepo;
    private final ParcelTagRepository tagRepo;

    @Value("${slpa.commission.default-rate:0.05}")
    private BigDecimal defaultCommissionRate;

    @Transactional
    public Auction create(Long sellerId, AuctionCreateRequest req) {
        validatePricing(req.startingBid(), req.reservePrice(), req.buyNowPrice());
        validateDuration(req.durationHours());
        validateSnipe(req.snipeProtect(), req.snipeWindowMin());

        User seller = userRepo.findById(sellerId)
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found: " + sellerId));
        Parcel parcel = parcelRepo.findById(req.parcelId())
                .orElseThrow(() -> new IllegalArgumentException("Parcel not found: " + req.parcelId()));

        Set<ParcelTag> tags = resolveTags(req.tags());

        // Per sub-spec 2 §7.1, verificationMethod is NOT set at create time — it
        // is chosen by the seller on PUT /auctions/{id}/verify and persisted by
        // AuctionVerificationService.triggerVerification(...). The entity column
        // is nullable until that point.
        Auction a = Auction.builder()
                .parcel(parcel)
                .seller(seller)
                .status(AuctionStatus.DRAFT)
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
                .build();
        a = auctionRepo.save(a);
        log.info("Auction created: id={}, sellerId={}, parcelId={}",
                a.getId(), sellerId, parcel.getId());
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

    @Transactional(readOnly = true)
    public List<Auction> loadOwnedBy(Long sellerId) {
        return auctionRepo.findBySellerIdOrderByCreatedAtDesc(sellerId);
    }

    /**
     * Paginated active listings for the public user profile (spec §14).
     * SUSPENDED and pre-ACTIVE statuses are excluded at the repository level
     * regardless of requester identity.
     */
    @Transactional(readOnly = true)
    public Page<Auction> loadActiveBySeller(Long sellerId, Pageable pageable) {
        return auctionRepo.findActiveBySellerId(sellerId, pageable);
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
