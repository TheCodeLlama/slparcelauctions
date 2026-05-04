package com.slparcelauctions.backend.auction.mybids;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionEndOutcome;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.BidRepository;
import com.slparcelauctions.backend.auction.ProxyBid;
import com.slparcelauctions.backend.auction.ProxyBidRepository;
import com.slparcelauctions.backend.auction.ProxyBidStatus;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.user.User;

/**
 * Service-layer unit tests for {@link MyBidsService}. Covers:
 *
 * <ul>
 *   <li>SQL status-filter mapping for each of {@code active}, {@code won},
 *       {@code lost}, {@code all}, {@code null}.</li>
 *   <li>Post-filter application for {@code won} (keeps WON, drops LOST) and
 *       {@code lost} (keeps LOST/RESERVE_NOT_MET/CANCELLED/SUSPENDED, drops
 *       WON).</li>
 *   <li>{@code myProxyMaxAmount} hydration from ACTIVE proxy rows; null when
 *       the caller has no proxy.</li>
 *   <li>{@code myHighestBidAmount} + {@code myHighestBidAt} from the
 *       aggregate query.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class MyBidsServiceTest {

    private static final Long USER_ID = 42L;
    private static final Long OTHER_USER = 99L;

    @Mock BidRepository bidRepo;
    @Mock AuctionRepository auctionRepo;
    @Mock ProxyBidRepository proxyBidRepo;
    @Mock EscrowRepository escrowRepo;

    private MyBidsService service;

    @BeforeEach
    void setUp() {
        service = new MyBidsService(bidRepo, auctionRepo, proxyBidRepo, escrowRepo);
        // Default to no escrow rows for the page — most cases target ACTIVE
        // auctions with no escrow row yet. Marked lenient because many tests
        // short-circuit before ids are hydrated (empty page, filter mismatch).
        lenient().when(escrowRepo.findByAuctionIdIn(any())).thenReturn(List.of());
    }

    @Test
    void filterActive_passesActiveOnly_noPostFilter() {
        Auction a = activeAuction(1L, USER_ID);
        stubPage(List.of(1L), List.of(a), aggregates(1L, 1500L));
        when(proxyBidRepo.findByBidderIdAndAuctionIdInAndStatus(
                eq(USER_ID), any(), eq(ProxyBidStatus.ACTIVE)))
                .thenReturn(List.of());

        Page<MyBidSummary> page = service.getMyBids(USER_ID, "active", PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().getFirst().myBidStatus()).isEqualTo(MyBidStatus.WINNING);
        assertSqlStatuses(Set.of(AuctionStatus.ACTIVE));
    }

    @Test
    void filterWon_passesEndedOnly_postFiltersWon() {
        // Two ENDED auctions: one WON, one LOST. Post-filter should drop LOST.
        Auction wonAuction = endedAuction(10L, AuctionEndOutcome.SOLD, USER_ID, USER_ID);
        Auction lostAuction = endedAuction(11L, AuctionEndOutcome.SOLD, OTHER_USER, OTHER_USER);
        stubPage(
                List.of(10L, 11L),
                List.of(wonAuction, lostAuction),
                aggregates(10L, 2000L, 11L, 1000L));
        when(proxyBidRepo.findByBidderIdAndAuctionIdInAndStatus(
                eq(USER_ID), any(), eq(ProxyBidStatus.ACTIVE)))
                .thenReturn(List.of());

        Page<MyBidSummary> page = service.getMyBids(USER_ID, "won", PageRequest.of(0, 20));

        assertThat(page.getContent())
                .extracting(MyBidSummary::myBidStatus)
                .containsExactly(MyBidStatus.WON);
        assertSqlStatuses(Set.of(AuctionStatus.ENDED));
    }

    @Test
    void filterLost_passesEndedCancelledSuspended_postFiltersLostBucket() {
        Auction wonAuction = endedAuction(20L, AuctionEndOutcome.SOLD, USER_ID, USER_ID);
        Auction lostAuction = endedAuction(21L, AuctionEndOutcome.SOLD, OTHER_USER, OTHER_USER);
        Auction reserveAuction = endedAuction(
                22L, AuctionEndOutcome.RESERVE_NOT_MET, USER_ID, null);
        Auction cancelledAuction = simpleAuction(23L, AuctionStatus.CANCELLED);
        Auction suspendedAuction = simpleAuction(24L, AuctionStatus.SUSPENDED);
        stubPage(
                List.of(20L, 21L, 22L, 23L, 24L),
                List.of(wonAuction, lostAuction, reserveAuction, cancelledAuction, suspendedAuction),
                aggregates(20L, 500L, 21L, 500L, 22L, 500L, 23L, 500L, 24L, 500L));
        when(proxyBidRepo.findByBidderIdAndAuctionIdInAndStatus(
                eq(USER_ID), any(), eq(ProxyBidStatus.ACTIVE)))
                .thenReturn(List.of());

        Page<MyBidSummary> page = service.getMyBids(USER_ID, "lost", PageRequest.of(0, 20));

        assertThat(page.getContent())
                .extracting(MyBidSummary::myBidStatus)
                .containsExactly(
                        MyBidStatus.LOST,
                        MyBidStatus.RESERVE_NOT_MET,
                        MyBidStatus.CANCELLED,
                        MyBidStatus.SUSPENDED);
        assertSqlStatuses(EnumSet.of(
                AuctionStatus.ENDED, AuctionStatus.CANCELLED, AuctionStatus.SUSPENDED));
    }

    @Test
    void filterAll_noSqlFilter_noPostFilter() {
        Auction activeAuction = activeAuction(30L, USER_ID);
        Auction endedAuction = endedAuction(31L, AuctionEndOutcome.SOLD, USER_ID, USER_ID);
        stubPage(
                List.of(30L, 31L),
                List.of(activeAuction, endedAuction),
                aggregates(30L, 500L, 31L, 1000L));
        when(proxyBidRepo.findByBidderIdAndAuctionIdInAndStatus(
                eq(USER_ID), any(), eq(ProxyBidStatus.ACTIVE)))
                .thenReturn(List.of());

        Page<MyBidSummary> page = service.getMyBids(USER_ID, "all", PageRequest.of(0, 20));

        assertThat(page.getContent())
                .extracting(MyBidSummary::myBidStatus)
                .containsExactly(MyBidStatus.WINNING, MyBidStatus.WON);
        assertNoSqlStatusFilter();
    }

    @Test
    void filterNull_treatedAsAll() {
        Auction a = activeAuction(40L, USER_ID);
        stubPage(List.of(40L), List.of(a), aggregates(40L, 500L));
        when(proxyBidRepo.findByBidderIdAndAuctionIdInAndStatus(
                eq(USER_ID), any(), eq(ProxyBidStatus.ACTIVE)))
                .thenReturn(List.of());

        Page<MyBidSummary> page = service.getMyBids(USER_ID, null, PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(1);
        assertNoSqlStatusFilter();
    }

    @Test
    void myProxyMax_populatedFromActiveProxy() {
        Auction a = activeAuction(50L, USER_ID);
        stubPage(List.of(50L), List.of(a), aggregates(50L, 2000L));

        ProxyBid proxy = new ProxyBid();
        proxy.setAuction(a);
        proxy.setMaxAmount(5000L);
        proxy.setStatus(ProxyBidStatus.ACTIVE);
        when(proxyBidRepo.findByBidderIdAndAuctionIdInAndStatus(
                eq(USER_ID), any(), eq(ProxyBidStatus.ACTIVE)))
                .thenReturn(List.of(proxy));

        Page<MyBidSummary> page = service.getMyBids(USER_ID, "active", PageRequest.of(0, 20));

        MyBidSummary row = page.getContent().getFirst();
        assertThat(row.myProxyMaxAmount()).isEqualTo(5000L);
        assertThat(row.myHighestBidAmount()).isEqualTo(2000L);
    }

    @Test
    void myProxyMax_nullWhenNoActiveProxy() {
        Auction a = activeAuction(60L, USER_ID);
        stubPage(List.of(60L), List.of(a), aggregates(60L, 1500L));
        when(proxyBidRepo.findByBidderIdAndAuctionIdInAndStatus(
                eq(USER_ID), any(), eq(ProxyBidStatus.ACTIVE)))
                .thenReturn(List.of());

        Page<MyBidSummary> page = service.getMyBids(USER_ID, "active", PageRequest.of(0, 20));

        MyBidSummary row = page.getContent().getFirst();
        assertThat(row.myProxyMaxAmount()).isNull();
        assertThat(row.myHighestBidAmount()).isEqualTo(1500L);
    }

    // --------------------------------------------------------------------
    // Helpers
    // --------------------------------------------------------------------

    private void stubPage(
            List<Long> auctionIds,
            List<Auction> auctions,
            List<Object[]> aggregates) {
        PageImpl<Long> idsPage = new PageImpl<>(
                auctionIds, PageRequest.of(0, 20), auctionIds.size());
        org.mockito.Mockito.lenient().when(
                bidRepo.findMyBidAuctionIds(eq(USER_ID), any(), any(Pageable.class)))
                .thenReturn(idsPage);
        org.mockito.Mockito.lenient().when(
                bidRepo.findMyBidAuctionIdsUnfiltered(eq(USER_ID), any(Pageable.class)))
                .thenReturn(idsPage);
        // Bulk-load via parcel+seller graph replaces the previous per-id
        // findById loop. Service zips back against original ids list to
        // preserve page order, so the order returned here is irrelevant.
        when(auctionRepo.findAllByIdWithParcelAndSeller(any())).thenReturn(auctions);
        when(bidRepo.findMyBidAggregatesForAuctions(eq(USER_ID), any()))
                .thenReturn(aggregates);
    }

    private List<Object[]> aggregates(Object... pairs) {
        // pairs = {auctionId, amount, auctionId, amount, ...}
        List<Object[]> out = new java.util.ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.add(new Object[] {pairs[i], pairs[i + 1], OffsetDateTime.now()});
        }
        return out;
    }

    private static Auction baseAuction(Long id) {
        User seller = User.builder().id(OTHER_USER).displayName("Seller").build();
        Auction a = new Auction();
        a.setId(id);
        a.setSlParcelUuid(UUID.fromString(
                String.format("44444444-4444-4444-4444-%012d", id)));
        a.setSeller(seller);
        a.setCurrentBid(1000L);
        a.setBidCount(3);
        return a;
    }

    private static Auction activeAuction(Long id, Long currentBidderId) {
        Auction a = baseAuction(id);
        a.setStatus(AuctionStatus.ACTIVE);
        a.setCurrentBidderId(currentBidderId);
        a.setEndsAt(OffsetDateTime.now().plusDays(1));
        return a;
    }

    private static Auction endedAuction(
            Long id, AuctionEndOutcome outcome, Long currentBidderId, Long winnerId) {
        Auction a = baseAuction(id);
        a.setStatus(AuctionStatus.ENDED);
        a.setEndOutcome(outcome);
        a.setCurrentBidderId(currentBidderId);
        a.setWinnerUserId(winnerId);
        a.setEndedAt(OffsetDateTime.now().minusHours(1));
        return a;
    }

    private static Auction simpleAuction(Long id, AuctionStatus status) {
        Auction a = baseAuction(id);
        a.setStatus(status);
        a.setEndedAt(OffsetDateTime.now().minusHours(2));
        return a;
    }

    private void assertSqlStatuses(Set<AuctionStatus> expected) {
        ArgumentCaptor<Collection<AuctionStatus>> captor = statusesCaptor();
        org.mockito.Mockito.verify(bidRepo).findMyBidAuctionIds(
                eq(USER_ID), captor.capture(), any(Pageable.class));
        assertThat(captor.getValue()).containsExactlyInAnyOrderElementsOf(expected);
        // The unfiltered variant must not be called when a SQL filter applies.
        org.mockito.Mockito.verify(bidRepo, org.mockito.Mockito.never())
                .findMyBidAuctionIdsUnfiltered(eq(USER_ID), any(Pageable.class));
    }

    private void assertNoSqlStatusFilter() {
        // status=all / null → unfiltered query variant is used.
        org.mockito.Mockito.verify(bidRepo).findMyBidAuctionIdsUnfiltered(
                eq(USER_ID), any(Pageable.class));
        org.mockito.Mockito.verify(bidRepo, org.mockito.Mockito.never())
                .findMyBidAuctionIds(eq(USER_ID), any(), any(Pageable.class));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Collection<AuctionStatus>> statusesCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Collection.class);
    }
}
