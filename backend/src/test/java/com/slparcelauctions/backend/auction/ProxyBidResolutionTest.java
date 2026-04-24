package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.auction.broadcast.AuctionBroadcastPublisher;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Focused pin tests for {@link ProxyBidService#resolveProxyResolution}. Each
 * test drives one of the four branches called out in spec §7:
 *
 * <ol>
 *   <li>No competing proxy — places the opening bid at startingBid.</li>
 *   <li>{@code newProxy.max > existing.max} — existing is exhausted; new
 *       proxy wins at {@code min(existing.max + increment, newProxy.max)}.</li>
 *   <li>{@code newProxy.max < existing.max} — new proxy is exhausted; emits
 *       a flush at its own max plus a counter at
 *       {@code min(newProxy.max + increment, existing.max)} for existing.</li>
 *   <li>Equal max — earliest {@code createdAt} wins (tiebreak).</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class ProxyBidResolutionTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 4, 20, 12, 0, 0, 0, ZoneOffset.UTC);

    @Mock AuctionRepository auctionRepo;
    @Mock ProxyBidRepository proxyBidRepo;
    @Mock BidRepository bidRepo;
    @Mock UserRepository userRepo;
    @Mock AuctionBroadcastPublisher publisher;

    ProxyBidService service;

    User userA;
    User userB;
    Auction auction;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);
        service = new ProxyBidService(auctionRepo, proxyBidRepo, bidRepo, userRepo, clock, publisher);

        userA = User.builder().id(100L).displayName("Alice").verified(true).build();
        userB = User.builder().id(200L).displayName("Bob").verified(true).build();
        auction = Auction.builder()
                .title("Test listing")
                .id(500L)
                .seller(User.builder().id(1L).verified(true).build())
                .status(AuctionStatus.ACTIVE)
                .startingBid(1000L)
                .currentBid(0L)
                .bidCount(0)
                .endsAt(NOW.plusHours(1))
                .originalEndsAt(NOW.plusHours(1))
                .snipeProtect(false)
                .build();

        lenient().when(bidRepo.save(any(Bid.class))).thenAnswer(inv -> {
            Bid b = inv.getArgument(0);
            if (b.getId() == null) b.setId(9000L);
            if (b.getCreatedAt() == null) b.setCreatedAt(NOW);
            return b;
        });
        lenient().when(proxyBidRepo.save(any(ProxyBid.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // -------------------------------------------------------------------------
    // Branch 1 — no competing proxy
    // -------------------------------------------------------------------------

    @Test
    void noCompetitor_placesOpeningBidAtStartingBid() {
        ProxyBid aProxy = proxy(1L, userA, 5000L, NOW);
        when(proxyBidRepo.findFirstByAuctionIdAndStatusAndBidderIdNot(
                eq(500L), eq(ProxyBidStatus.ACTIVE), eq(userA.getId())))
                .thenReturn(Optional.empty());

        List<Bid> emitted = service.resolveProxyResolution(auction, aProxy);

        assertThat(emitted).hasSize(1);
        Bid opening = emitted.get(0);
        assertThat(opening.getAmount()).isEqualTo(1000L);  // startingBid
        assertThat(opening.getBidType()).isEqualTo(BidType.PROXY_AUTO);
        assertThat(opening.getIpAddress()).isNull();
        assertThat(opening.getBidder().getId()).isEqualTo(userA.getId());
    }

    @Test
    void noCompetitor_withPreExistingCurrentBid_opensAtCurrentPlusIncrement() {
        // Auction already has a bid landed (perhaps by a cancelled proxy's
        // lingering effect on currentBid). Opening should clear
        // currentBid + minIncrement.
        auction.setCurrentBid(2000L);
        ProxyBid aProxy = proxy(1L, userA, 5000L, NOW);
        when(proxyBidRepo.findFirstByAuctionIdAndStatusAndBidderIdNot(
                eq(500L), eq(ProxyBidStatus.ACTIVE), eq(userA.getId())))
                .thenReturn(Optional.empty());

        List<Bid> emitted = service.resolveProxyResolution(auction, aProxy);

        // 2000 + 100 = 2100 (tier L$1000-9999 → L$100 increment)
        assertThat(emitted.get(0).getAmount()).isEqualTo(2100L);
    }

    // -------------------------------------------------------------------------
    // Branch 2 — new proxy max > existing max
    // -------------------------------------------------------------------------

    @Test
    void newProxyMaxGreater_existingExhaustedAndSettles() {
        // Existing: userA max=2000 (ACTIVE). New: userB max=5000.
        // B wins at min(2000 + 100, 5000) = 2100.
        ProxyBid aExisting = proxy(1L, userA, 2000L, NOW.minusMinutes(5));
        ProxyBid bNew = proxy(2L, userB, 5000L, NOW);
        when(proxyBidRepo.findFirstByAuctionIdAndStatusAndBidderIdNot(
                eq(500L), eq(ProxyBidStatus.ACTIVE), eq(userB.getId())))
                .thenReturn(Optional.of(aExisting));

        List<Bid> emitted = service.resolveProxyResolution(auction, bNew);

        assertThat(emitted).hasSize(1);
        assertThat(emitted.get(0).getAmount()).isEqualTo(2100L);
        assertThat(emitted.get(0).getBidder().getId()).isEqualTo(userB.getId());
        assertThat(emitted.get(0).getProxyBidId()).isEqualTo(bNew.getId());
        assertThat(aExisting.getStatus()).isEqualTo(ProxyBidStatus.EXHAUSTED);
        verify(proxyBidRepo).save(aExisting);
    }

    @Test
    void newProxyMaxGreater_butBelowIncrement_cappedAtNewProxyMax() {
        // Existing max=2000; new proxy max=2050. Increment tier for 2000 is
        // L$100, so settle = min(2000+100, 2050) = 2050 (capped at new.max).
        ProxyBid aExisting = proxy(1L, userA, 2000L, NOW.minusMinutes(5));
        ProxyBid bNew = proxy(2L, userB, 2050L, NOW);
        when(proxyBidRepo.findFirstByAuctionIdAndStatusAndBidderIdNot(
                eq(500L), eq(ProxyBidStatus.ACTIVE), eq(userB.getId())))
                .thenReturn(Optional.of(aExisting));

        List<Bid> emitted = service.resolveProxyResolution(auction, bNew);

        assertThat(emitted).hasSize(1);
        assertThat(emitted.get(0).getAmount()).isEqualTo(2050L);
    }

    // -------------------------------------------------------------------------
    // Branch 3 — new proxy max < existing max
    // -------------------------------------------------------------------------

    @Test
    void newProxyMaxLess_existingWinsNewProxyExhausted() {
        // Existing: userA max=5000 (ACTIVE). New: userB max=2000.
        // New flushes at 2000; existing counters at min(2000+100, 5000) = 2100.
        ProxyBid aExisting = proxy(1L, userA, 5000L, NOW.minusMinutes(5));
        ProxyBid bNew = proxy(2L, userB, 2000L, NOW);
        when(proxyBidRepo.findFirstByAuctionIdAndStatusAndBidderIdNot(
                eq(500L), eq(ProxyBidStatus.ACTIVE), eq(userB.getId())))
                .thenReturn(Optional.of(aExisting));

        List<Bid> emitted = service.resolveProxyResolution(auction, bNew);

        assertThat(emitted).hasSize(2);
        // First: new proxy flush at its own max.
        assertThat(emitted.get(0).getBidder().getId()).isEqualTo(userB.getId());
        assertThat(emitted.get(0).getAmount()).isEqualTo(2000L);
        assertThat(emitted.get(0).getProxyBidId()).isEqualTo(bNew.getId());
        // Second: existing counter; the LAST emitted is the post-resolution top.
        assertThat(emitted.get(1).getBidder().getId()).isEqualTo(userA.getId());
        assertThat(emitted.get(1).getAmount()).isEqualTo(2100L);
        assertThat(emitted.get(1).getProxyBidId()).isEqualTo(aExisting.getId());

        assertThat(bNew.getStatus()).isEqualTo(ProxyBidStatus.EXHAUSTED);
        assertThat(aExisting.getStatus()).isEqualTo(ProxyBidStatus.ACTIVE);
    }

    // -------------------------------------------------------------------------
    // Branch 4 — equal max, earliest createdAt wins
    // -------------------------------------------------------------------------

    @Test
    void equalMax_earliestCreatedAtWins_existingIsEarlier() {
        // Both max=3000. Existing created earlier, so existing wins.
        ProxyBid aExisting = proxy(1L, userA, 3000L, NOW.minusMinutes(5));
        ProxyBid bNew = proxy(2L, userB, 3000L, NOW);
        when(proxyBidRepo.findFirstByAuctionIdAndStatusAndBidderIdNot(
                eq(500L), eq(ProxyBidStatus.ACTIVE), eq(userB.getId())))
                .thenReturn(Optional.of(aExisting));

        List<Bid> emitted = service.resolveProxyResolution(auction, bNew);

        // Two rows — new flush at new.max, existing counter at existing.max.
        assertThat(emitted).hasSize(2);
        assertThat(emitted.get(0).getBidder().getId()).isEqualTo(userB.getId());
        assertThat(emitted.get(0).getAmount()).isEqualTo(3000L);
        assertThat(emitted.get(1).getBidder().getId()).isEqualTo(userA.getId());
        assertThat(emitted.get(1).getAmount()).isEqualTo(3000L);
        // Existing keeps the auction — emitted.last is the top.
        assertThat(bNew.getStatus()).isEqualTo(ProxyBidStatus.EXHAUSTED);
        assertThat(aExisting.getStatus()).isEqualTo(ProxyBidStatus.ACTIVE);
    }

    @Test
    void equalMax_resurrectionBranch_newProxyIsEarlier_newProxyWins() {
        // Edge case from spec: resurrection of an EXHAUSTED row whose
        // createdAt predates the current ACTIVE competitor. Since the
        // resurrection flips status=ACTIVE in-place, createdAt is preserved.
        ProxyBid aExistingActive = proxy(1L, userA, 3000L, NOW);  // later
        ProxyBid bResurrected = proxy(2L, userB, 3000L, NOW.minusMinutes(10));  // earlier
        when(proxyBidRepo.findFirstByAuctionIdAndStatusAndBidderIdNot(
                eq(500L), eq(ProxyBidStatus.ACTIVE), eq(userB.getId())))
                .thenReturn(Optional.of(aExistingActive));

        List<Bid> emitted = service.resolveProxyResolution(auction, bResurrected);

        // Resurrected is earlier — it wins. existing flips to EXHAUSTED.
        assertThat(emitted).hasSize(1);
        assertThat(emitted.get(0).getBidder().getId()).isEqualTo(userB.getId());
        assertThat(emitted.get(0).getAmount()).isEqualTo(3000L);
        assertThat(aExistingActive.getStatus()).isEqualTo(ProxyBidStatus.EXHAUSTED);
        verify(proxyBidRepo).save(aExistingActive);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ProxyBid proxy(Long id, User owner, long maxAmount, OffsetDateTime createdAt) {
        ProxyBid p = ProxyBid.builder()
                .id(id)
                .auction(auction)
                .bidder(owner)
                .maxAmount(maxAmount)
                .status(ProxyBidStatus.ACTIVE)
                .build();
        p.setCreatedAt(createdAt);
        p.setUpdatedAt(createdAt);
        return p;
    }
}
