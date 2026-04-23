package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.slparcelauctions.backend.auction.broadcast.AuctionBroadcastPublisher;
import com.slparcelauctions.backend.auction.broadcast.AuctionEndedEnvelope;
import com.slparcelauctions.backend.auction.dto.BidResponse;
import com.slparcelauctions.backend.escrow.EscrowService;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Unit coverage for inline buy-it-now inside {@link BidService#placeBid}.
 * Mockito-driven with a fixed {@link Clock}. Each test prepares an auction
 * with a specific {@code buyNowPrice} and bid amount combination and
 * asserts the resulting auction state (status, endOutcome, winnerUserId,
 * finalBidAmount, endedAt), the publisher envelope variant, and the
 * {@code buyNowTriggered} flag on the response.
 */
@ExtendWith(MockitoExtension.class)
class BidServiceBuyNowTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 4, 20, 12, 0, 0, 0, ZoneOffset.UTC);

    @Mock AuctionRepository auctionRepo;
    @Mock BidRepository bidRepo;
    @Mock ProxyBidRepository proxyBidRepo;
    @Mock UserRepository userRepo;
    @Mock AuctionBroadcastPublisher publisher;
    @Mock EscrowService escrowService;

    BidService service;
    User seller;
    User bidder;
    Auction auction;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);
        service = new BidService(auctionRepo, bidRepo, proxyBidRepo, userRepo, clock, publisher, escrowService);

        seller = User.builder().id(10L).email("seller@example.com")
                .displayName("Seller").verified(true).build();
        bidder = User.builder().id(20L).email("bidder@example.com")
                .displayName("Bidder").verified(true).build();

        auction = Auction.builder()
                .title("Test listing")
                .id(500L)
                .seller(seller)
                .status(AuctionStatus.ACTIVE)
                .startingBid(1000L)
                .currentBid(0L)
                .bidCount(0)
                .originalEndsAt(NOW.plusHours(1))
                .endsAt(NOW.plusHours(1))
                .snipeProtect(false)
                .build();

        lenient().when(bidRepo.save(any(Bid.class))).thenAnswer(inv -> {
            Bid b = inv.getArgument(0);
            if (b.getId() == null) b.setId(9000L);
            if (b.getCreatedAt() == null) b.setCreatedAt(NOW);
            return b;
        });
        lenient().when(auctionRepo.save(any(Auction.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void doesNotEnd_whenBuyNowPriceNull() {
        // No buyNowPrice set → auction stays ACTIVE regardless of bid size.
        auction.setBuyNowPrice(null);
        when(auctionRepo.findByIdForUpdate(500L)).thenReturn(Optional.of(auction));
        when(userRepo.findById(20L)).thenReturn(Optional.of(bidder));

        BidResponse resp = runInTx(() -> service.placeBid(500L, 20L, 999_999L, "1.2.3.4"));

        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.ACTIVE);
        assertThat(auction.getEndOutcome()).isNull();
        assertThat(auction.getEndedAt()).isNull();
        assertThat(resp.buyNowTriggered()).isFalse();
        verify(proxyBidRepo, never()).exhaustAllActiveByAuctionId(any());
        // No close → no escrow creation.
        verify(escrowService, never()).createForEndedAuction(any(), any());
    }

    @Test
    void doesNotEnd_whenBidBelowBuyNow() {
        // buyNowPrice=10000 but bid=5000 → no trigger. Increment tier on
        // currentBid=0 is starting-bid rule, so 5000 clears the 1000 start.
        auction.setBuyNowPrice(10_000L);
        when(auctionRepo.findByIdForUpdate(500L)).thenReturn(Optional.of(auction));
        when(userRepo.findById(20L)).thenReturn(Optional.of(bidder));

        BidResponse resp = runInTx(() -> service.placeBid(500L, 20L, 5_000L, "1.2.3.4"));

        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.ACTIVE);
        assertThat(auction.getEndOutcome()).isNull();
        assertThat(resp.buyNowTriggered()).isFalse();
        verify(proxyBidRepo, never()).exhaustAllActiveByAuctionId(any());
        // No close → no escrow creation.
        verify(escrowService, never()).createForEndedAuction(any(), any());
    }

    @Test
    void endsWithBOUGHT_NOW_whenBidEqualsBuyNow() {
        // Exact match on buyNowPrice triggers close.
        auction.setBuyNowPrice(10_000L);
        when(auctionRepo.findByIdForUpdate(500L)).thenReturn(Optional.of(auction));
        when(userRepo.findById(20L)).thenReturn(Optional.of(bidder));

        BidResponse resp = runInTx(() -> service.placeBid(500L, 20L, 10_000L, "1.2.3.4"));

        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.ENDED);
        assertThat(auction.getEndOutcome()).isEqualTo(AuctionEndOutcome.BOUGHT_NOW);
        assertThat(auction.getWinnerUserId()).isEqualTo(20L);
        assertThat(auction.getFinalBidAmount()).isEqualTo(10_000L);
        assertThat(auction.getEndedAt()).isEqualTo(NOW);
        assertThat(resp.buyNowTriggered()).isTrue();
    }

    @Test
    void endsWithBOUGHT_NOW_whenBidExceedsBuyNow() {
        // Overshoot still ends the auction — finalBidAmount reflects the
        // actual emitted bid, not the buyNowPrice threshold.
        auction.setBuyNowPrice(10_000L);
        when(auctionRepo.findByIdForUpdate(500L)).thenReturn(Optional.of(auction));
        when(userRepo.findById(20L)).thenReturn(Optional.of(bidder));

        BidResponse resp = runInTx(() -> service.placeBid(500L, 20L, 15_000L, "1.2.3.4"));

        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.ENDED);
        assertThat(auction.getEndOutcome()).isEqualTo(AuctionEndOutcome.BOUGHT_NOW);
        assertThat(auction.getWinnerUserId()).isEqualTo(20L);
        assertThat(auction.getFinalBidAmount()).isEqualTo(15_000L);
        assertThat(resp.buyNowTriggered()).isTrue();
    }

    @Test
    void publishesEndedEnvelope_notSettlement_onBuyNow() {
        // Buy-now closes should publish AuctionEndedEnvelope exactly once,
        // never a BidSettlementEnvelope. Branch captured at registration.
        auction.setBuyNowPrice(10_000L);
        when(auctionRepo.findByIdForUpdate(500L)).thenReturn(Optional.of(auction));
        when(userRepo.findById(20L)).thenReturn(Optional.of(bidder));

        runInTx(() -> service.placeBid(500L, 20L, 10_000L, "1.2.3.4"));

        ArgumentCaptor<AuctionEndedEnvelope> cap = ArgumentCaptor.forClass(AuctionEndedEnvelope.class);
        verify(publisher, times(1)).publishEnded(cap.capture());
        verify(publisher, never()).publishSettlement(any());

        AuctionEndedEnvelope env = cap.getValue();
        assertThat(env.type()).isEqualTo("AUCTION_ENDED");
        assertThat(env.auctionId()).isEqualTo(500L);
        assertThat(env.endOutcome()).isEqualTo(AuctionEndOutcome.BOUGHT_NOW);
        assertThat(env.finalBid()).isEqualTo(10_000L);
        assertThat(env.winnerUserId()).isEqualTo(20L);
        assertThat(env.winnerDisplayName()).isEqualTo("Bidder");
        assertThat(env.bidCount()).isEqualTo(1);
    }

    @Test
    void createsEscrow_onBuyNowTrigger() {
        // Buy-now close must delegate escrow row creation to EscrowService in
        // the same transaction as the status flip. The `now` passed is the
        // same one used for the auction's aggregate mutations (line 110 of
        // placeBid), so the 48h payment deadline anchors consistently.
        auction.setBuyNowPrice(10_000L);
        when(auctionRepo.findByIdForUpdate(500L)).thenReturn(Optional.of(auction));
        when(userRepo.findById(20L)).thenReturn(Optional.of(bidder));

        runInTx(() -> service.placeBid(500L, 20L, 10_000L, "1.2.3.4"));

        verify(escrowService, times(1)).createForEndedAuction(auction, NOW);
    }

    @Test
    void exhaustsActiveProxiesOnBuyNow() {
        // Verify the ProxyBidRepository.exhaustAllActiveByAuctionId sweep
        // fires exactly once with the correct auction id. The sweep clears
        // the partial unique index so no zombie ACTIVE proxies linger on
        // the closed auction.
        auction.setBuyNowPrice(10_000L);
        when(auctionRepo.findByIdForUpdate(500L)).thenReturn(Optional.of(auction));
        when(userRepo.findById(20L)).thenReturn(Optional.of(bidder));
        when(proxyBidRepo.exhaustAllActiveByAuctionId(500L)).thenReturn(1);

        runInTx(() -> service.placeBid(500L, 20L, 10_000L, "1.2.3.4"));

        verify(proxyBidRepo, times(1)).exhaustAllActiveByAuctionId(eq(500L));
    }

    // ------------------------------------------------------------------

    private <T> T runInTx(java.util.function.Supplier<T> body) {
        TransactionTemplate tt = new TransactionTemplate(new FakeTxManager());
        return tt.execute(status -> body.get());
    }

    private static final class FakeTxManager implements PlatformTransactionManager {
        @Override
        public org.springframework.transaction.TransactionStatus getTransaction(
                TransactionDefinition def) {
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            return new SimpleTransactionStatus(true);
        }

        @Override
        public void commit(org.springframework.transaction.TransactionStatus status) {
            try {
                TransactionSynchronizationManager.getSynchronizations().forEach(s -> {
                    try { s.afterCommit(); } catch (Exception ignored) { }
                });
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
                TransactionSynchronizationManager.setActualTransactionActive(false);
            }
        }

        @Override
        public void rollback(org.springframework.transaction.TransactionStatus status) {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }
}
