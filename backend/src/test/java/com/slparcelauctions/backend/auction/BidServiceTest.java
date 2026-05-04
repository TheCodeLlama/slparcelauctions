package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
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
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;

import com.slparcelauctions.backend.auction.broadcast.AuctionBroadcastPublisher;
import com.slparcelauctions.backend.auction.broadcast.BidSettlementEnvelope;
import com.slparcelauctions.backend.auction.dto.BidResponse;
import com.slparcelauctions.backend.auction.exception.AuctionAlreadyEndedException;
import com.slparcelauctions.backend.admin.ban.BanCheckService;
import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.auction.exception.BidTooLowException;
import com.slparcelauctions.backend.auction.exception.InvalidAuctionStateException;
import com.slparcelauctions.backend.auction.exception.NotVerifiedException;
import com.slparcelauctions.backend.auction.exception.SellerCannotBidException;
import com.slparcelauctions.backend.escrow.EscrowService;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserNotFoundException;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Unit coverage for {@link BidService#placeBid}. Mockito-driven, with a
 * fixed {@link Clock} so the {@code endsAt <= now} edge case is
 * deterministic. Every validation path in spec §6 steps 1-5 is asserted,
 * plus the happy-path persistence and envelope-publish shape. The
 * afterCommit-publish-hygiene test drives the service through a real
 * {@link TransactionTemplate} over a fake transaction manager so we can
 * assert the publisher is invoked exactly once, after the commit barrier.
 */
@ExtendWith(MockitoExtension.class)
class BidServiceTest {

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
    Auction activeAuction;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);
        service = new BidService(auctionRepo, bidRepo, proxyBidRepo, userRepo, clock, publisher, escrowService, mock(com.slparcelauctions.backend.notification.NotificationPublisher.class), mock(BanCheckService.class), mock(com.slparcelauctions.backend.wallet.WalletService.class), mock(com.slparcelauctions.backend.wallet.BidReservationRepository.class));

        seller = User.builder().id(10L).email("seller@example.com")
                .displayName("Seller").verified(true).build();
        bidder = User.builder().id(20L).email("bidder@example.com")
                .displayName("Bidder").verified(true).build();

        activeAuction = Auction.builder()
                .title("Test listing")
                .id(500L)
                .seller(seller)
                .status(AuctionStatus.ACTIVE)
                .startingBid(1000L)
                .currentBid(0L)
                .bidCount(0)
                .endsAt(NOW.plusHours(1))
                .originalEndsAt(NOW.plusHours(1))
                .snipeProtect(false)
                .build();

        // save-bid returns the passed row with id/createdAt stamped via reflection
        // (id and createdAt are @Setter(NONE) in BaseEntity, assigned by DB normally).
        // Marked lenient because early-validation tests never reach save.
        lenient().when(bidRepo.save(any(Bid.class))).thenAnswer(inv -> {
            Bid b = inv.getArgument(0);
            setBaseEntityField(b, "id", 9000L);
            setBaseEntityField(b, "createdAt", NOW);
            return b;
        });
        lenient().when(auctionRepo.save(any(Auction.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // -------------------------------------------------------------------------
    // Validation paths — each spec §6 step gets a dedicated test.
    // -------------------------------------------------------------------------

    @Test
    void throwsAuctionNotFoundException_whenAuctionMissing() {
        when(auctionRepo.findByIdForUpdate(500L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.placeBid(500L, 20L, 1000L, "1.2.3.4"))
                .isInstanceOf(AuctionNotFoundException.class);
        verify(bidRepo, never()).save(any());
        verify(publisher, never()).publishSettlement(any());
    }

    @Test
    void throwsInvalidAuctionStateException_whenStatusNotActive() {
        activeAuction.setStatus(AuctionStatus.ENDED);
        when(auctionRepo.findByIdForUpdate(500L)).thenReturn(Optional.of(activeAuction));

        assertThatThrownBy(() -> service.placeBid(500L, 20L, 1000L, "1.2.3.4"))
                .isInstanceOfSatisfying(InvalidAuctionStateException.class, e -> {
                    assertThat(e.getCurrentState()).isEqualTo(AuctionStatus.ENDED);
                    assertThat(e.getAttemptedAction()).isEqualTo("BID");
                });
        verify(bidRepo, never()).save(any());
    }

    @Test
    void throwsAuctionAlreadyEndedException_whenEndsAtInPast() {
        activeAuction.setEndsAt(NOW.minusSeconds(1));
        when(auctionRepo.findByIdForUpdate(500L)).thenReturn(Optional.of(activeAuction));

        assertThatThrownBy(() -> service.placeBid(500L, 20L, 1000L, "1.2.3.4"))
                .isInstanceOf(AuctionAlreadyEndedException.class);
        verify(bidRepo, never()).save(any());
    }

    @Test
    void throwsAuctionAlreadyEndedException_whenEndsAtEqualsNow() {
        // Boundary check: strict "after" comparison — a bid at exactly the
        // end-time is rejected. Matches spec §6 step 3 wording (endsAt <= now).
        activeAuction.setEndsAt(NOW);
        when(auctionRepo.findByIdForUpdate(500L)).thenReturn(Optional.of(activeAuction));

        assertThatThrownBy(() -> service.placeBid(500L, 20L, 1000L, "1.2.3.4"))
                .isInstanceOf(AuctionAlreadyEndedException.class);
    }

    @Test
    void throwsUserNotFoundException_whenBidderIdMissing() {
        when(auctionRepo.findByIdForUpdate(500L)).thenReturn(Optional.of(activeAuction));
        when(userRepo.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.placeBid(500L, 999L, 1000L, "1.2.3.4"))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void throwsNotVerifiedException_whenBidderUnverified() {
        bidder.setVerified(false);
        when(auctionRepo.findByIdForUpdate(500L)).thenReturn(Optional.of(activeAuction));
        when(userRepo.findById(20L)).thenReturn(Optional.of(bidder));

        assertThatThrownBy(() -> service.placeBid(500L, 20L, 1000L, "1.2.3.4"))
                .isInstanceOf(NotVerifiedException.class);
        verify(bidRepo, never()).save(any());
    }

    @Test
    void throwsSellerCannotBidException_whenBidderIsSeller() {
        when(auctionRepo.findByIdForUpdate(500L)).thenReturn(Optional.of(activeAuction));
        // Bidder id == seller id (both 10).
        when(userRepo.findById(10L)).thenReturn(Optional.of(seller));

        assertThatThrownBy(() -> service.placeBid(500L, 10L, 1000L, "1.2.3.4"))
                .isInstanceOf(SellerCannotBidException.class);
        verify(bidRepo, never()).save(any());
    }

    @Test
    void throwsBidTooLowException_belowStartingBidOnFirstBid() {
        when(auctionRepo.findByIdForUpdate(500L)).thenReturn(Optional.of(activeAuction));
        when(userRepo.findById(20L)).thenReturn(Optional.of(bidder));

        assertThatThrownBy(() -> service.placeBid(500L, 20L, 999L, "1.2.3.4"))
                .isInstanceOfSatisfying(BidTooLowException.class, e ->
                        assertThat(e.getMinRequired()).isEqualTo(1000L));
        verify(bidRepo, never()).save(any());
    }

    @Test
    void throwsBidTooLowException_belowCurrentBidPlusIncrement() {
        activeAuction.setCurrentBid(1000L);
        activeAuction.setBidCount(1);
        when(auctionRepo.findByIdForUpdate(500L)).thenReturn(Optional.of(activeAuction));
        when(userRepo.findById(20L)).thenReturn(Optional.of(bidder));

        // Tier for currentBid=1000 is L$100, so minRequired = 1100.
        assertThatThrownBy(() -> service.placeBid(500L, 20L, 1099L, "1.2.3.4"))
                .isInstanceOfSatisfying(BidTooLowException.class, e ->
                        assertThat(e.getMinRequired()).isEqualTo(1100L));
    }

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    void placesBidSuccessfully_updatesAuctionAndPersistsBidWithManualType() {
        when(auctionRepo.findByIdForUpdate(500L)).thenReturn(Optional.of(activeAuction));
        when(userRepo.findById(20L)).thenReturn(Optional.of(bidder));

        BidResponse resp = runInTransaction(() ->
                service.placeBid(500L, 20L, 1500L, "1.2.3.4"));

        ArgumentCaptor<Bid> bidCap = ArgumentCaptor.forClass(Bid.class);
        verify(bidRepo).save(bidCap.capture());
        Bid saved = bidCap.getValue();
        assertThat(saved.getAmount()).isEqualTo(1500L);
        assertThat(saved.getBidType()).isEqualTo(BidType.MANUAL);
        assertThat(saved.getProxyBidId()).isNull();
        assertThat(saved.getSnipeExtensionMinutes()).isNull();
        assertThat(saved.getNewEndsAt()).isNull();
        assertThat(saved.getBidder().getId()).isEqualTo(20L);
        assertThat(saved.getAuction().getId()).isEqualTo(500L);

        // Auction state updated
        assertThat(activeAuction.getCurrentBid()).isEqualTo(1500L);
        assertThat(activeAuction.getCurrentBidderId()).isEqualTo(20L);
        assertThat(activeAuction.getBidCount()).isEqualTo(1);
        verify(auctionRepo).save(activeAuction);

        // Response shape
        assertThat(resp.amount()).isEqualTo(1500L);
        assertThat(resp.bidType()).isEqualTo(BidType.MANUAL);
        assertThat(resp.bidCount()).isEqualTo(1);
        assertThat(resp.buyNowTriggered()).isFalse();
        assertThat(resp.snipeExtensionMinutes()).isNull();
    }

    @Test
    void persistsIpAddressOnManualBidRow() {
        when(auctionRepo.findByIdForUpdate(500L)).thenReturn(Optional.of(activeAuction));
        when(userRepo.findById(20L)).thenReturn(Optional.of(bidder));

        runInTransaction(() -> service.placeBid(500L, 20L, 1500L, "203.0.113.17"));

        ArgumentCaptor<Bid> bidCap = ArgumentCaptor.forClass(Bid.class);
        verify(bidRepo).save(bidCap.capture());
        assertThat(bidCap.getValue().getIpAddress()).isEqualTo("203.0.113.17");
    }

    @Test
    void acceptsNullIpAddress_forBrokenProxyChainCase() {
        // Per spec §6 IP capture: when X-Forwarded-For is present but from
        // an untrusted proxy, the controller stores null rather than a
        // bogus internal IP. BidService must accept null without erroring.
        when(auctionRepo.findByIdForUpdate(500L)).thenReturn(Optional.of(activeAuction));
        when(userRepo.findById(20L)).thenReturn(Optional.of(bidder));

        runInTransaction(() -> service.placeBid(500L, 20L, 1500L, null));

        ArgumentCaptor<Bid> bidCap = ArgumentCaptor.forClass(Bid.class);
        verify(bidRepo).save(bidCap.capture());
        assertThat(bidCap.getValue().getIpAddress()).isNull();
    }

    @Test
    void incrementsBidCountOnSubsequentBid() {
        activeAuction.setCurrentBid(1000L);
        activeAuction.setCurrentBidderId(30L);
        activeAuction.setBidCount(1);
        when(auctionRepo.findByIdForUpdate(500L)).thenReturn(Optional.of(activeAuction));
        when(userRepo.findById(20L)).thenReturn(Optional.of(bidder));

        runInTransaction(() -> service.placeBid(500L, 20L, 1100L, "1.2.3.4"));

        assertThat(activeAuction.getBidCount()).isEqualTo(2);
        assertThat(activeAuction.getCurrentBid()).isEqualTo(1100L);
        assertThat(activeAuction.getCurrentBidderId()).isEqualTo(20L);
    }

    // -------------------------------------------------------------------------
    // afterCommit publish hygiene
    // -------------------------------------------------------------------------

    @Test
    void publishesEnvelope_onlyAfterCommit() {
        when(auctionRepo.findByIdForUpdate(500L)).thenReturn(Optional.of(activeAuction));
        when(userRepo.findById(20L)).thenReturn(Optional.of(bidder));

        // Drive through a real TransactionTemplate so the
        // TransactionSynchronization registered inside BidService has a
        // manager to bind to and actually fires its afterCommit callback.
        runInTransaction(() -> service.placeBid(500L, 20L, 1500L, "1.2.3.4"));

        ArgumentCaptor<BidSettlementEnvelope> envCap =
                ArgumentCaptor.forClass(BidSettlementEnvelope.class);
        verify(publisher, times(1)).publishSettlement(envCap.capture());
        BidSettlementEnvelope env = envCap.getValue();
        assertThat(env.auctionId()).isEqualTo(500L);
        assertThat(env.currentBid()).isEqualTo(1500L);
        assertThat(env.currentBidderId()).isEqualTo(20L);
        assertThat(env.currentBidderDisplayName()).isEqualTo("Bidder");
        assertThat(env.bidCount()).isEqualTo(1);
        assertThat(env.newBids()).hasSize(1);
        assertThat(env.newBids().getFirst().amount()).isEqualTo(1500L);
        assertThat(env.newBids().getFirst().bidType()).isEqualTo(BidType.MANUAL);
    }

    @Test
    void doesNotPublishEnvelope_whenTransactionRollsBack() {
        when(auctionRepo.findByIdForUpdate(500L)).thenReturn(Optional.of(activeAuction));
        when(userRepo.findById(20L)).thenReturn(Optional.of(bidder));

        TransactionTemplate tt = new TransactionTemplate(new FakeTxManager());
        tt.execute(status -> {
            service.placeBid(500L, 20L, 1500L, "1.2.3.4");
            status.setRollbackOnly();
            return null;
        });

        verify(publisher, never()).publishSettlement(any());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private <T> T runInTransaction(java.util.function.Supplier<T> body) {
        TransactionTemplate tt = new TransactionTemplate(new FakeTxManager());
        return tt.execute(status -> body.get());
    }

    /** Reflectively sets a field declared on BaseEntity (id, createdAt) which has no public setter. */
    private static void setBaseEntityField(Object entity, String fieldName, Object value) {
        try {
            java.lang.reflect.Field f =
                    com.slparcelauctions.backend.common.BaseEntity.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(entity, value);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /**
     * Minimal {@link PlatformTransactionManager} that binds a
     * {@code TransactionSynchronization} registry for the duration of a
     * {@link TransactionTemplate#execute} call and fires the
     * {@code afterCommit} callbacks when the tx completes successfully.
     * Rollback skips the callbacks, matching Spring's production semantics.
     */
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
                if (status.isRollbackOnly()) {
                    fireAfterCompletion(false);
                } else {
                    TransactionSynchronizationManager.getSynchronizations().forEach(s -> {
                        try { s.beforeCommit(false); } catch (Exception ignored) {}
                    });
                    TransactionSynchronizationManager.getSynchronizations().forEach(s -> {
                        try { s.beforeCompletion(); } catch (Exception ignored) {}
                    });
                    TransactionSynchronizationManager.getSynchronizations().forEach(s -> {
                        try { s.afterCommit(); } catch (Exception ignored) {}
                    });
                    fireAfterCompletion(true);
                }
            } finally {
                cleanup();
            }
        }

        @Override
        public void rollback(org.springframework.transaction.TransactionStatus status) {
            try {
                fireAfterCompletion(false);
            } finally {
                cleanup();
            }
        }

        private void fireAfterCompletion(boolean committed) {
            int status = committed
                    ? org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED
                    : org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK;
            TransactionSynchronizationManager.getSynchronizations().forEach(s -> {
                try { s.afterCompletion(status); } catch (Exception ignored) {}
            });
        }

        private void cleanup() {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }

        // Unused helper to document the signature — kept package-private for
        // the Instant import to stay live without a wildcard dependency.
        @SuppressWarnings("unused")
        private Instant now() {
            return Instant.EPOCH;
        }
    }
}
