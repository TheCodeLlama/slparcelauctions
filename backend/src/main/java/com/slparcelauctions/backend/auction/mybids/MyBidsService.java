package com.slparcelauctions.backend.auction.mybids;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.BidRepository;
import com.slparcelauctions.backend.auction.ProxyBid;
import com.slparcelauctions.backend.auction.ProxyBidRepository;
import com.slparcelauctions.backend.auction.ProxyBidStatus;
import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Read-only projection service for the bidder dashboard. Implements the
 * {@code GET /api/v1/users/me/bids} contract defined in spec §10:
 *
 * <ol>
 *   <li>Page the set of {@link Auction}s the caller has bid on (SQL filter by
 *       parent status when {@code status=active|won|lost}).</li>
 *   <li>For each, load the full {@code Auction} aggregate (parcel + tags
 *       eager-fetched via {@code @EntityGraph}; seller is lazy and fetched
 *       per-row when the summary reads its display name) and the caller's
 *       max bid amount + timestamp.</li>
 *   <li>Look up any {@code ACTIVE} proxy the caller owns on the page's
 *       auctions, so the dashboard shows the cap the user set.</li>
 *   <li>Derive {@link MyBidStatus} via {@link MyBidStatusDeriver}.</li>
 *   <li>For {@code status=won|lost} filters, post-filter the derived status
 *       (SOLD-but-LOST, SOLD-but-WON, etc. only separate after derivation).
 *       Pagination is slightly imprecise under post-filter — a page may
 *       return fewer rows than requested when some entries derive to an
 *       excluded bucket. Spec §10 acknowledges this is acceptable at
 *       Phase 1 volumes (tens of bid rows per user, not thousands). If the
 *       dashboard becomes hot later, introduce a materialized
 *       {@code my_bid_status} column on {@code bids}.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MyBidsService {

    /** Default page size mirrors spec §4 ({@code size=20}). */
    private static final int DEFAULT_PAGE_SIZE = 20;

    private static final Set<MyBidStatus> LOST_BUCKET_STATUSES = EnumSet.of(
            MyBidStatus.LOST,
            MyBidStatus.RESERVE_NOT_MET,
            MyBidStatus.CANCELLED,
            MyBidStatus.SUSPENDED);

    private final BidRepository bidRepo;
    private final AuctionRepository auctionRepo;
    private final ProxyBidRepository proxyBidRepo;
    private final EscrowRepository escrowRepo;

    /**
     * Returns the caller's paginated My Bids page. {@code statusParam} accepts
     * {@code active}, {@code won}, {@code lost}, {@code all}, or {@code null}
     * (treated as {@code all}). Any other string is coerced to {@code all} —
     * the controller does not surface a 400 here to keep the endpoint
     * forgiving, matching the existing {@link AuctionStatus} behavior
     * elsewhere.
     */
    @Transactional(readOnly = true)
    public Page<MyBidSummary> getMyBids(Long userId, String statusParam, Pageable pageable) {
        Pageable effective = withDefaults(pageable);
        MyBidStatusFilter filter = MyBidStatusFilter.from(statusParam);

        Page<Long> auctionIdsPage = filter.sqlStatuses() == null
                ? bidRepo.findMyBidAuctionIdsUnfiltered(userId, effective)
                : bidRepo.findMyBidAuctionIds(userId, filter.sqlStatuses(), effective);

        List<Long> ids = auctionIdsPage.getContent();
        if (ids.isEmpty()) {
            return new PageImpl<>(List.of(), effective, auctionIdsPage.getTotalElements());
        }

        // Hydrate auctions via findById to trip the @EntityGraph on parcel+tags.
        // Note: auction.seller is FetchType.LAZY and is NOT in the EntityGraph,
        // so each summary touches seller.getDisplayName() causing one extra
        // SELECT per auction. At page size 20 this is bounded (~43 queries per
        // page). See DEFERRED_WORK.md ("N+1 on My Bids auction loading") for
        // the followup to join seller into the graph.
        Map<Long, Auction> byId = new HashMap<>();
        for (Long id : ids) {
            auctionRepo.findById(id).ifPresent(a -> byId.put(id, a));
        }

        // Caller's bid aggregates per auction.
        Map<Long, BidAggregate> aggregates = new HashMap<>();
        for (Object[] row : bidRepo.findMyBidAggregatesForAuctions(userId, ids)) {
            Long auctionId = (Long) row[0];
            Long maxAmount = (Long) row[1];
            OffsetDateTime maxCreatedAt = (OffsetDateTime) row[2];
            aggregates.put(auctionId, new BidAggregate(maxAmount, maxCreatedAt));
        }

        // Caller's ACTIVE proxy rows keyed by auction id.
        Map<Long, Long> activeProxyMaxByAuction = new HashMap<>();
        List<ProxyBid> activeProxies = proxyBidRepo.findByBidderIdAndAuctionIdInAndStatus(
                userId, ids, ProxyBidStatus.ACTIVE);
        for (ProxyBid p : activeProxies) {
            activeProxyMaxByAuction.put(p.getAuction().getId(), p.getMaxAmount());
        }

        // Batch-load escrows for the page's auctions. ACTIVE auctions have no
        // escrow row; ENDED and post-ENDED rows do. One query per page replaces
        // what would otherwise be N separate findByAuctionId calls inside the
        // summary builder.
        Map<Long, Escrow> escrowsByAuctionId = new HashMap<>();
        for (Escrow e : escrowRepo.findByAuctionIdIn(ids)) {
            Long aId = e.getAuction() == null ? null : e.getAuction().getId();
            if (aId != null) {
                escrowsByAuctionId.put(aId, e);
            }
        }

        List<MyBidSummary> summaries = new ArrayList<>(ids.size());
        for (Long id : ids) {
            Auction a = byId.get(id);
            if (a == null) {
                // Race: auction deleted between the ID page query and the
                // entity load. Skip — the next page refresh will drop it.
                continue;
            }
            BidAggregate agg = aggregates.get(id);
            Long myMaxAmount = agg == null ? null : agg.maxAmount();
            OffsetDateTime myMaxAt = agg == null ? null : agg.maxCreatedAt();
            Long myProxyMax = activeProxyMaxByAuction.get(id);
            MyBidStatus derived = MyBidStatusDeriver.derive(userId, a);
            Escrow escrow = escrowsByAuctionId.get(id);
            summaries.add(new MyBidSummary(
                    AuctionSummaryForMyBids.from(a, escrow),
                    myMaxAmount,
                    myMaxAt,
                    myProxyMax,
                    derived));
        }

        if (filter.postFilter() != null) {
            summaries = summaries.stream()
                    .filter(s -> filter.postFilter().contains(s.myBidStatus()))
                    .toList();
        }

        // The `ids` list is already in DB order (ACTIVE first; within,
        // endsAt DESC; then ended auctions by endedAt DESC) and we walked it
        // in order when building `summaries`, so no post-sort is needed.
        return new PageImpl<>(
                summaries,
                effective,
                auctionIdsPage.getTotalElements());
    }

    private static Pageable withDefaults(Pageable pageable) {
        if (pageable == null || pageable.isUnpaged()) {
            return Pageable.ofSize(DEFAULT_PAGE_SIZE);
        }
        return pageable;
    }

    private record BidAggregate(Long maxAmount, OffsetDateTime maxCreatedAt) {}

    /**
     * Parsed {@code ?status=...} filter: the SQL-level {@link AuctionStatus}
     * filter (nullable — "no filter") and the optional post-derivation
     * filter (nullable when no post-filter is needed).
     */
    private record MyBidStatusFilter(
            Set<AuctionStatus> sqlStatuses,
            Set<MyBidStatus> postFilter) {

        static MyBidStatusFilter from(String raw) {
            if (raw == null) {
                return new MyBidStatusFilter(null, null);
            }
            return switch (raw.toLowerCase()) {
                case "active" -> new MyBidStatusFilter(
                        EnumSet.of(AuctionStatus.ACTIVE), null);
                case "won" -> new MyBidStatusFilter(
                        EnumSet.of(AuctionStatus.ENDED),
                        EnumSet.of(MyBidStatus.WON));
                case "lost" -> new MyBidStatusFilter(
                        EnumSet.of(
                                AuctionStatus.ENDED,
                                AuctionStatus.CANCELLED,
                                AuctionStatus.SUSPENDED),
                        LOST_BUCKET_STATUSES);
                case "all" -> new MyBidStatusFilter(null, null);
                default -> new MyBidStatusFilter(null, null);
            };
        }
    }
}
