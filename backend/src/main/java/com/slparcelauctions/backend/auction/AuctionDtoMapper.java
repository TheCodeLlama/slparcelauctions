package com.slparcelauctions.backend.auction;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.auction.dto.AuctionPhotoResponse;
import com.slparcelauctions.backend.auction.dto.GroupAttributionDto;
import com.slparcelauctions.backend.auction.dto.ListingAgentDto;
import com.slparcelauctions.backend.auction.dto.PublicAuctionResponse;
import com.slparcelauctions.backend.auction.dto.PublicAuctionResponse.SellerSummary;
import com.slparcelauctions.backend.auction.dto.PublicAuctionStatus;
import com.slparcelauctions.backend.auction.dto.SellerAuctionResponse;
import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.parcel.dto.ParcelResponse;
import com.slparcelauctions.backend.parceltag.dto.ParcelTagResponse;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.user.SellerCompletionRateMapper;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserAvatarUrl;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * Auction -> DTO conversion. The {@link #toPublicStatus(AuctionStatus)} method
 * is the linchpin of the privacy guarantee: the 4 terminal statuses collapse
 * to ENDED, and the 4 pre-ACTIVE statuses throw IllegalStateException because
 * the controller is responsible for returning 404 to non-sellers before this
 * mapper is called.
 *
 * <p>Photo / group / winner resolution: single-DTO entry points
 * ({@link #toPublicResponse(Auction)} / {@link #toSellerResponse(Auction)})
 * resolve each per row via {@code findById}. Batch callers should use
 * {@link #toBatchPublicResponses(List, Map)} / {@link #toBatchSellerResponses(List, Map)}
 * which build a {@link MapperBatchContext} once and pre-load groups + primary
 * photos + winner publicIds in three batch queries -- one per dimension regardless
 * of input cardinality. Sub-project G section 6.1.
 *
 * <p>Escrow enrichment: single-auction entry points resolve the optional
 * {@link Escrow} via {@link EscrowRepository#findByAuctionId(Long)} on demand.
 * Batch callers should pre-load escrows into a map and pass them through the
 * batch entry points (or the {@code (Auction, Escrow)} overloads) to avoid N+1
 * queries.
 */
@Component
@RequiredArgsConstructor
public class AuctionDtoMapper {

    private final AuctionPhotoRepository photoRepo;
    private final EscrowRepository escrowRepo;
    private final UserRepository userRepo;
    private final RealtyGroupRepository realtyGroupRepo;

    /**
     * Sub-project G section 6.1 -- single-pass batch resolution for the three
     * N+1s the batch overloads previously incurred (group attribution, primary
     * photo, winner public id). Built once per batch via
     * {@link #build(List, RealtyGroupRepository, AuctionPhotoRepository, UserRepository)}
     * and threaded into each per-row resolve.
     */
    public record MapperBatchContext(
            Map<Long, RealtyGroup> groupsById,
            Map<Long, AuctionPhoto> primaryPhotoByAuctionId,
            Map<Long, UUID> winnerPublicIdByAuctionId,
            Map<Long, String> winnerDisplayNameByAuctionId) {

        public static MapperBatchContext build(
                List<Auction> auctions,
                RealtyGroupRepository groupRepo,
                AuctionPhotoRepository photoRepo,
                UserRepository userRepo) {
            Set<Long> groupIds = auctions.stream()
                    .map(Auction::getRealtyGroupId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            Set<Long> auctionIds = auctions.stream()
                    .map(Auction::getId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            Set<Long> winnerIds = auctions.stream()
                    .map(Auction::getWinnerId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            Map<Long, RealtyGroup> groups = groupRepo.findAllById(groupIds).stream()
                    .collect(Collectors.toMap(
                            RealtyGroup::getId, Function.identity()));
            Map<Long, AuctionPhoto> primaryPhotos = photoRepo
                    .findPrimaryForAuctions(auctionIds).stream()
                    .collect(Collectors.toMap(
                            p -> p.getAuction().getId(), Function.identity()));
            Map<Long, UUID> winners = new HashMap<>(userRepo.findPublicIdsByIds(winnerIds));
            Map<Long, String> winnerNames = new HashMap<>(userRepo.findDisplayNamesByIds(winnerIds));
            return new MapperBatchContext(groups, primaryPhotos, winners, winnerNames);
        }
    }

    public PublicAuctionStatus toPublicStatus(AuctionStatus internal) {
        return switch (internal) {
            case ACTIVE -> PublicAuctionStatus.ACTIVE;
            case TRANSFER_PENDING, DISPUTED,
                 COMPLETED, EXPIRED, FROZEN, CANCELLED -> PublicAuctionStatus.ENDED;
            default -> throw new IllegalStateException(
                "Non-public status leaked to toPublicStatus: " + internal
                    + ". The controller should have 404'd before calling the mapper.");
        };
    }

    public PublicAuctionResponse toPublicResponse(Auction a) {
        return toPublicResponse(a, resolveEscrow(a), null);
    }

    /**
     * Batch-safe overload -- pass the already-loaded escrow (or null when the
     * auction is ACTIVE / pre-ENDED) to avoid the fallback fetch inside
     * {@link #toPublicResponse(Auction)}.
     */
    public PublicAuctionResponse toPublicResponse(Auction a, Escrow escrow) {
        return toPublicResponse(a, escrow, null);
    }

    /**
     * Batch-context overload -- callers that map many auctions in one pass build
     * a {@link MapperBatchContext} once and pass it here so the
     * group/photo/winner resolves become map lookups instead of per-row queries.
     * Sub-project G section 6.1.
     */
    public PublicAuctionResponse toPublicResponse(Auction a, Escrow escrow, MapperBatchContext ctx) {
        boolean hasReserve = a.getReservePrice() != null;
        boolean reserveMet = hasReserve && a.getCurrentBid() != null
                && a.getCurrentBid() >= a.getReservePrice();
        return new PublicAuctionResponse(
                a.getPublicId(),
                a.getSeller().getPublicId(),
                a.getTitle(),
                ParcelResponse.from(a.getParcelSnapshot()),
                toPublicStatus(a.getStatus()),
                a.getVerificationTier(),
                a.getStartingBid(),
                hasReserve,
                reserveMet,
                a.getBuyNowPrice(),
                a.getCurrentBid(),
                a.getBidCount(),
                currentHighBid(a),
                bidderCount(a),
                a.getDurationHours(),
                a.getSnipeProtect(),
                a.getSnipeWindowMin(),
                a.getStartsAt(),
                a.getEndsAt(),
                a.getOriginalEndsAt(),
                a.getSellerDesc(),
                tagList(a),
                photoList(a, ctx),
                sellerSummary(a.getSeller()),
                escrow == null ? null : escrow.getState(),
                escrow == null ? null : escrow.getTransferConfirmedAt(),
                a.getEndOutcome(),
                a.getFinalBidAmount(),
                resolveWinnerPublicId(a, ctx),
                resolveWinnerDisplayName(a, ctx),
                resolveGroupAttribution(a, ctx),
                resolveListingAgent(a));
    }

    public SellerAuctionResponse toSellerResponse(Auction a) {
        return toSellerResponse(a, resolveEscrow(a), null);
    }

    /**
     * Batch-safe overload -- pass the already-loaded escrow (or null) to avoid
     * the fallback fetch inside {@link #toSellerResponse(Auction)}.
     */
    public SellerAuctionResponse toSellerResponse(Auction a, Escrow escrow) {
        return toSellerResponse(a, escrow, null);
    }

    /**
     * Batch-context overload -- callers that map many auctions in one pass build
     * a {@link MapperBatchContext} once and pass it here so the
     * group/photo/winner resolves become map lookups instead of per-row queries.
     * Sub-project G section 6.1.
     */
    public SellerAuctionResponse toSellerResponse(
            Auction a, Escrow escrow, MapperBatchContext ctx) {
        return new SellerAuctionResponse(
                a.getPublicId(),
                a.getSeller().getPublicId(),
                sellerSummary(a.getSeller()),
                a.getTitle(),
                ParcelResponse.from(a.getParcelSnapshot()),
                a.getStatus(),
                a.getVerificationTier(),
                a.getVerificationNotes(),
                a.getStartingBid(),
                a.getReservePrice(),
                a.getBuyNowPrice(),
                a.getCurrentBid(),
                a.getBidCount(),
                currentHighBid(a),
                bidderCount(a),
                resolveWinnerPublicId(a, ctx),
                a.getDurationHours(),
                a.getSnipeProtect(),
                a.getSnipeWindowMin(),
                a.getStartsAt(),
                a.getEndsAt(),
                a.getOriginalEndsAt(),
                a.getSellerDesc(),
                tagList(a),
                photoList(a, ctx),
                a.getListingFeePaid(),
                a.getListingFeeAmt(),
                a.getListingFeeTxn(),
                a.getListingFeePaidAt(),
                a.getCommissionRate(),
                a.getCommissionAmt(),
                a.getCreatedAt(),
                a.getUpdatedAt(),
                escrow == null ? null : escrow.getState(),
                escrow == null ? null : escrow.getTransferConfirmedAt(),
                a.getEndOutcome(),
                a.getFinalBidAmount(),
                resolveWinnerDisplayName(a, ctx),
                resolveGroupAttribution(a, ctx),
                resolveListingAgent(a));
    }

    /**
     * Batch entry point for callers that map many auctions in one go (e.g.
     * {@code listMine}, {@code search}). Pre-loads the group + primary-photo +
     * winner-publicId relations into a {@link MapperBatchContext} so each row's
     * resolve becomes a map lookup. Sub-project G section 6.1.
     */
    public List<PublicAuctionResponse> toBatchPublicResponses(
            List<Auction> auctions, Map<Long, Escrow> escrowsByAuctionId) {
        MapperBatchContext ctx = MapperBatchContext.build(auctions, realtyGroupRepo, photoRepo, userRepo);
        return auctions.stream()
                .map(a -> toPublicResponse(
                        a,
                        escrowsByAuctionId == null ? null : escrowsByAuctionId.get(a.getId()),
                        ctx))
                .toList();
    }

    /**
     * Batch entry point for seller-scoped batch mappers. Pre-loads group +
     * primary photo + winner publicId in three batch queries. Sub-project G
     * section 6.1.
     */
    public List<SellerAuctionResponse> toBatchSellerResponses(
            List<Auction> auctions,
            Map<Long, Escrow> escrowsByAuctionId) {
        MapperBatchContext ctx = MapperBatchContext.build(auctions, realtyGroupRepo, photoRepo, userRepo);
        return auctions.stream()
                .map(a -> toSellerResponse(
                        a,
                        escrowsByAuctionId == null ? null : escrowsByAuctionId.get(a.getId()),
                        ctx))
                .toList();
    }

    /**
     * Returns the current high bid as a {@link BigDecimal}, or null when no
     * bids have been placed. Epic 04 will populate real values; until then
     * {@code currentBid} is the entity default of 0, which we project as null
     * so consumers can render a dash rather than "L$0".
     */
    private BigDecimal currentHighBid(Auction a) {
        Long cb = a.getCurrentBid();
        if (cb == null || cb == 0L) {
            return null;
        }
        return BigDecimal.valueOf(cb);
    }

    /**
     * Returns the bidder count as a {@link Long}, defaulting to 0 for
     * historical rows with a null {@code bidCount}.
     */
    private Long bidderCount(Auction a) {
        Integer bc = a.getBidCount();
        return bc == null ? 0L : bc.longValue();
    }

    private List<ParcelTagResponse> tagList(Auction a) {
        return a.getTags().stream()
                .sorted(Comparator.comparing(t -> t.getLabel() == null ? "" : t.getLabel()))
                .map(ParcelTagResponse::from)
                .toList();
    }

    /**
     * Per-row photo resolution. Context-aware: when {@code ctx} is non-null,
     * returns the pre-loaded primary photo (matches the prior shape of
     * {@code findByAuctionIdOrderBySortOrderAsc(...).get(0)}); otherwise falls
     * back to a per-row query for single-DTO entry points.
     */
    private List<AuctionPhotoResponse> photoList(Auction a, MapperBatchContext ctx) {
        if (a.getId() == null) {
            return List.of();
        }
        if (ctx == null) {
            return photoRepo.findByAuctionIdOrderBySortOrderAsc(a.getId()).stream()
                    .map(AuctionPhotoResponse::from)
                    .toList();
        }
        AuctionPhoto primary = ctx.primaryPhotoByAuctionId().get(a.getId());
        return primary == null ? List.of() : List.of(AuctionPhotoResponse.from(primary));
    }

    /**
     * Builds the {@link SellerSummary} block for {@link PublicAuctionResponse}.
     * Returns {@code null} only when the seller association is unset (defensive
     * -- every persisted auction has a non-null seller). Avatar URL points at
     * the existing {@code GET /api/v1/users/{id}/avatar/256} endpoint, which
     * already serves cached + placeholder avatars. {@code completionRate} is
     * delegated to {@link SellerCompletionRateMapper#compute(int, int, int)} so
     * the rounding + zero-denominator policy lives in one place and the private
     * {@code cancelledWithBids} + {@code escrowExpiredUnfulfilled} counters
     * never reach the wire. See Epic 08 sub-spec 1 section 3.5 for the 3-arg widening.
     */
    private SellerSummary sellerSummary(User s) {
        if (s == null) {
            return null;
        }
        Integer completed = s.getCompletedSales();
        Integer cancelled = s.getCancelledWithBids();
        Integer expiredUnfulfilled = s.getEscrowExpiredUnfulfilled();
        int completedInt = completed == null ? 0 : completed;
        int cancelledInt = cancelled == null ? 0 : cancelled;
        int expiredUnfulfilledInt = expiredUnfulfilled == null ? 0 : expiredUnfulfilled;
        return new SellerSummary(
                s.getPublicId(),
                s.getDisplayName(),
                UserAvatarUrl.forUserOrNull(s.getPublicId()),
                s.getAvgSellerRating(),
                s.getTotalSellerReviews(),
                completed,
                SellerCompletionRateMapper.compute(completedInt, cancelledInt, expiredUnfulfilledInt),
                s.getCreatedAt() == null ? null : s.getCreatedAt().toLocalDate());
    }

    /**
     * Resolves the auction's soft-FK {@code winnerId} column (internal Long)
     * to the winner's {@code publicId} (UUID) for outbound DTO emission.
     * Returns null when the auction has no winner yet (pre-ENDED states).
     *
     * <p>Context-aware: batch callers pass a non-null {@link MapperBatchContext}
     * whose pre-loaded {@code winnerPublicIdByAuctionId} map collapses N+1
     * lookups into one batched query. Single-DTO entry points pass {@code null}
     * and fall back to a per-row {@code findById}.
     */
    private UUID resolveWinnerPublicId(Auction auction, MapperBatchContext ctx) {
        if (auction.getWinnerId() == null) {
            return null;
        }
        if (ctx == null) {
            return userRepo.findById(auction.getWinnerId())
                    .map(User::getPublicId)
                    .orElse(null);
        }
        return ctx.winnerPublicIdByAuctionId().get(auction.getWinnerId());
    }

    /**
     * Resolves the winner's display name (with username fallback) for
     * outbound DTO emission. Returns null when the auction has no winner.
     * Mirrors {@link #resolveWinnerPublicId}: batch callers pass a non-null
     * context to collapse N+1 queries; single-DTO callers pass null.
     */
    private String resolveWinnerDisplayName(Auction auction, MapperBatchContext ctx) {
        if (auction.getWinnerId() == null) {
            return null;
        }
        if (ctx == null) {
            return userRepo.findById(auction.getWinnerId())
                    .map(u -> {
                        String dn = u.getDisplayName();
                        return (dn == null || dn.isBlank()) ? u.getUsername() : dn;
                    })
                    .orElse(null);
        }
        return ctx.winnerDisplayNameByAuctionId().get(auction.getWinnerId());
    }

    /**
     * Resolves the group attribution block for group-listed auctions.
     * Returns {@code null} for individual (non-group) listings.
     *
     * <p>Context-aware: batch callers pass a non-null {@link MapperBatchContext}
     * whose pre-loaded {@code groupsById} map collapses N+1 group lookups into
     * one batched query. Single-DTO entry points pass {@code null} and fall
     * back to a per-row {@code findById}.
     */
    private GroupAttributionDto resolveGroupAttribution(Auction a, MapperBatchContext ctx) {
        if (a.getRealtyGroupId() == null) {
            return null;
        }
        RealtyGroup g = (ctx == null)
                ? realtyGroupRepo.findById(a.getRealtyGroupId()).orElse(null)
                : ctx.groupsById().get(a.getRealtyGroupId());
        if (g == null) {
            return null;
        }
        String logoUrl = g.getLogoObjectKey() == null
                ? null
                : "/api/v1/realty-groups/" + g.getPublicId() + "/logo/image";
        return new GroupAttributionDto(
                g.getPublicId(), g.getName(), g.getSlug(), logoUrl, g.getDissolvedAt() != null);
    }

    /**
     * Resolves the listing-agent attribution block for group-listed auctions.
     * Returns {@code null} when no agent is assigned (individual listings).
     * Avatar URL matches the existing {@code SellerSummary} convention:
     * the {@code GET /api/v1/users/{publicId}/avatar/256} endpoint always serves
     * bytes (real upload or placeholder), so the URL is emitted whenever the user
     * has a non-null {@code publicId}.
     */
    private ListingAgentDto resolveListingAgent(Auction a) {
        User u = a.getListingAgent();
        if (u == null) {
            return null;
        }
        String avatarUrl = UserAvatarUrl.forUserOrNull(u.getPublicId());
        return new ListingAgentDto(u.getPublicId(), u.getDisplayName(), avatarUrl);
    }

    /**
     * On-demand escrow lookup for single-auction mapper entry points. Returns
     * null for unpersisted auctions (tests) and for auctions whose status
     * cannot possibly carry an escrow row (ACTIVE and pre-ACTIVE states, plus
     * SUSPENDED where no sale occurred). Batch callers should pre-load escrows
     * and use the {@code (Auction, Escrow)} overloads to avoid one query per
     * row.
     */
    private Escrow resolveEscrow(Auction a) {
        if (a.getId() == null) {
            return null;
        }
        if (!hasEscrowBearingStatus(a.getStatus())) {
            return null;
        }
        return escrowRepo.findByAuctionId(a.getId()).orElse(null);
    }

    /**
     * Whether an auction in the given status might have an escrow row. Escrow
     * rows are created on SOLD/BOUGHT_NOW close (auction flips
     * ACTIVE -> TRANSFER_PENDING) and persist through the
     * TRANSFER_PENDING -> COMPLETED / EXPIRED / FROZEN / DISPUTED lifecycle.
     * CANCELLED can also carry an escrow row when an admin cancels from
     * TRANSFER_PENDING (see {@code CancellationService.cancelByAdminFromEscrow}).
     * ACTIVE and pre-ACTIVE statuses, plus EXPIRED-from-NO_BIDS and SUSPENDED
     * (where no sale occurred), never carry an escrow row -- but we can't tell
     * EXPIRED-from-NO_BIDS from EXPIRED-from-transfer-timeout by status alone,
     * so EXPIRED is treated as escrow-bearing and the repo lookup returns
     * empty for the NO_BIDS subset.
     */
    private static boolean hasEscrowBearingStatus(AuctionStatus s) {
        return switch (s) {
            case DRAFT, DRAFT_PAID, VERIFICATION_PENDING, VERIFICATION_FAILED,
                    ACTIVE, SUSPENDED -> false;
            case TRANSFER_PENDING, DISPUTED, COMPLETED,
                    EXPIRED, FROZEN, CANCELLED -> true;
        };
    }
}
