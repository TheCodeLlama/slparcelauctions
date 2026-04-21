package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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
import com.slparcelauctions.backend.auction.dto.ProxyBidResponse;
import com.slparcelauctions.backend.auction.exception.AuctionAlreadyEndedException;
import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.auction.exception.BidTooLowException;
import com.slparcelauctions.backend.auction.exception.CannotCancelWinningProxyException;
import com.slparcelauctions.backend.auction.exception.InvalidAuctionStateException;
import com.slparcelauctions.backend.auction.exception.InvalidProxyMaxException;
import com.slparcelauctions.backend.auction.exception.InvalidProxyStateException;
import com.slparcelauctions.backend.auction.exception.NotVerifiedException;
import com.slparcelauctions.backend.auction.exception.ProxyBidAlreadyExistsException;
import com.slparcelauctions.backend.auction.exception.ProxyBidNotFoundException;
import com.slparcelauctions.backend.auction.exception.SellerCannotBidException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Unit coverage for {@link ProxyBidService}. Mockito-driven with a fixed
 * {@link Clock}. Pins every validation branch in spec §7 plus the reachable
 * sub-branches of updateProxyMax (ACTIVE-winning silent, EXHAUSTED
 * resurrection, CANCELLED reject) and the cancel / get happy paths. The
 * ACTIVE-not-winning branch is unreachable under the pessimistic lock and
 * now throws {@link IllegalStateException} as a fail-fast guard, so no test
 * exercises it.
 *
 * <p>Resolution-branch coverage (4 branches of
 * {@link ProxyBidService#resolveProxyResolution}) lives in
 * {@link ProxyBidResolutionTest}.
 */
@ExtendWith(MockitoExtension.class)
class ProxyBidServiceTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 4, 20, 12, 0, 0, 0, ZoneOffset.UTC);

    @Mock AuctionRepository auctionRepo;
    @Mock ProxyBidRepository proxyBidRepo;
    @Mock BidRepository bidRepo;
    @Mock UserRepository userRepo;
    @Mock AuctionBroadcastPublisher publisher;

    ProxyBidService service;

    User seller;
    User bidder;
    Auction activeAuction;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);
        service = new ProxyBidService(auctionRepo, proxyBidRepo, bidRepo, userRepo, clock, publisher);

        seller = User.builder().id(10L).email("seller@example.com")
                .displayName("Seller").verified(true).build();
        bidder = User.builder().id(20L).email("bidder@example.com")
                .displayName("Bidder").verified(true).build();

        activeAuction = Auction.builder()
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

        lenient().when(bidRepo.save(any(Bid.class))).thenAnswer(inv -> {
            Bid b = inv.getArgument(0);
            if (b.getId() == null) b.setId(9000L);
            if (b.getCreatedAt() == null) b.setCreatedAt(NOW);
            return b;
        });
        lenient().when(proxyBidRepo.save(any(ProxyBid.class))).thenAnswer(inv -> {
            ProxyBid p = inv.getArgument(0);
            if (p.getId() == null) p.setId(7000L);
            if (p.getCreatedAt() == null) p.setCreatedAt(NOW);
            if (p.getUpdatedAt() == null) p.setUpdatedAt(NOW);
            return p;
        });
        lenient().when(auctionRepo.save(any(Auction.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // -------------------------------------------------------------------------
    // createProxy — validation paths
    // -------------------------------------------------------------------------

    @Test
    void createProxy_throwsAuctionNotFound_whenAuctionMissing() {
        when(auctionRepo.findByIdForUpdate(500L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createProxy(500L, 20L, 1500L))
                .isInstanceOf(AuctionNotFoundException.class);
        verify(bidRepo, never()).save(any());
    }

    @Test
    void createProxy_throwsInvalidAuctionState_whenNotActive() {
        activeAuction.setStatus(AuctionStatus.ENDED);
        when(auctionRepo.findByIdForUpdate(500L)).thenReturn(Optional.of(activeAuction));

        assertThatThrownBy(() -> service.createProxy(500L, 20L, 1500L))
                .isInstanceOfSatisfying(InvalidAuctionStateException.class, e -> {
                    assertThat(e.getAttemptedAction()).isEqualTo("PROXY_BID");
                    assertThat(e.getCurrentState()).isEqualTo(AuctionStatus.ENDED);
                });
    }

    @Test
    void createProxy_throwsAuctionAlreadyEnded_whenEndsAtPast() {
        activeAuction.setEndsAt(NOW.minusSeconds(1));
        when(auctionRepo.findByIdForUpdate(500L)).thenReturn(Optional.of(activeAuction));

        assertThatThrownBy(() -> service.createProxy(500L, 20L, 1500L))
                .isInstanceOf(AuctionAlreadyEndedException.class);
    }

    @Test
    void createProxy_throwsNotVerified_whenBidderUnverified() {
        bidder.setVerified(false);
        when(auctionRepo.findByIdForUpdate(500L)).thenReturn(Optional.of(activeAuction));
        when(userRepo.findById(20L)).thenReturn(Optional.of(bidder));

        assertThatThrownBy(() -> service.createProxy(500L, 20L, 1500L))
                .isInstanceOf(NotVerifiedException.class);
    }

    @Test
    void createProxy_throwsSellerCannotBid_whenBidderIsSeller() {
        when(auctionRepo.findByIdForUpdate(500L)).thenReturn(Optional.of(activeAuction));
        when(userRepo.findById(10L)).thenReturn(Optional.of(seller));

        assertThatThrownBy(() -> service.createProxy(500L, 10L, 1500L))
                .isInstanceOf(SellerCannotBidException.class);
    }

    @Test
    void createProxy_throwsProxyBidAlreadyExists_whenCallerHasActiveProxy() {
        when(auctionRepo.findByIdForUpdate(500L)).thenReturn(Optional.of(activeAuction));
        when(userRepo.findById(20L)).thenReturn(Optional.of(bidder));
        when(proxyBidRepo.existsByAuctionIdAndBidderIdAndStatus(500L, 20L, ProxyBidStatus.ACTIVE))
                .thenReturn(true);

        assertThatThrownBy(() -> service.createProxy(500L, 20L, 1500L))
                .isInstanceOf(ProxyBidAlreadyExistsException.class);
        verify(proxyBidRepo, never()).save(any());
    }

    @Test
    void createProxy_throwsBidTooLow_whenMaxBelowStartingBid() {
        when(auctionRepo.findByIdForUpdate(500L)).thenReturn(Optional.of(activeAuction));
        when(userRepo.findById(20L)).thenReturn(Optional.of(bidder));

        assertThatThrownBy(() -> service.createProxy(500L, 20L, 500L))
                .isInstanceOfSatisfying(BidTooLowException.class, e ->
                        assertThat(e.getMinRequired()).isEqualTo(1000L));
    }

    // -------------------------------------------------------------------------
    // createProxy — happy path (no competitor)
    // -------------------------------------------------------------------------

    @Test
    void createProxy_noCompetitor_savesProxyAndOpensAtStartingBid() {
        when(auctionRepo.findByIdForUpdate(500L)).thenReturn(Optional.of(activeAuction));
        when(userRepo.findById(20L)).thenReturn(Optional.of(bidder));
        when(proxyBidRepo.findFirstByAuctionIdAndStatusAndBidderIdNot(
                eq(500L), eq(ProxyBidStatus.ACTIVE), eq(20L)))
                .thenReturn(Optional.empty());

        ProxyBidResponse resp = runInTransaction(() ->
                service.createProxy(500L, 20L, 5000L));

        // Proxy persisted
        ArgumentCaptor<ProxyBid> proxyCap = ArgumentCaptor.forClass(ProxyBid.class);
        verify(proxyBidRepo).save(proxyCap.capture());
        ProxyBid saved = proxyCap.getValue();
        assertThat(saved.getMaxAmount()).isEqualTo(5000L);
        assertThat(saved.getStatus()).isEqualTo(ProxyBidStatus.ACTIVE);
        assertThat(saved.getBidder().getId()).isEqualTo(20L);

        // One PROXY_AUTO bid at starting bid (currentBid=0, so floor=startingBid=1000)
        ArgumentCaptor<Bid> bidCap = ArgumentCaptor.forClass(Bid.class);
        verify(bidRepo).save(bidCap.capture());
        Bid bid = bidCap.getValue();
        assertThat(bid.getBidType()).isEqualTo(BidType.PROXY_AUTO);
        assertThat(bid.getAmount()).isEqualTo(1000L);
        assertThat(bid.getIpAddress()).isNull();

        // Auction aggregate updated
        assertThat(activeAuction.getCurrentBid()).isEqualTo(1000L);
        assertThat(activeAuction.getCurrentBidderId()).isEqualTo(20L);
        assertThat(activeAuction.getBidCount()).isEqualTo(1);

        // Response
        assertThat(resp.maxAmount()).isEqualTo(5000L);
        assertThat(resp.status()).isEqualTo(ProxyBidStatus.ACTIVE);
    }

    // -------------------------------------------------------------------------
    // updateProxyMax — sub-branches
    // -------------------------------------------------------------------------

    @Test
    void updateProxyMax_throwsProxyBidNotFound_whenNoProxy() {
        when(auctionRepo.findByIdForUpdate(500L)).thenReturn(Optional.of(activeAuction));
        when(userRepo.findById(20L)).thenReturn(Optional.of(bidder));
        when(proxyBidRepo.findFirstByAuctionIdAndBidderIdOrderByCreatedAtDesc(500L, 20L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateProxyMax(500L, 20L, 2000L))
                .isInstanceOf(ProxyBidNotFoundException.class);
    }

    @Test
    void updateProxyMax_throwsInvalidProxyState_onCancelled() {
        ProxyBid proxy = proxyRow(100L, bidder, 1500L, ProxyBidStatus.CANCELLED);
        when(auctionRepo.findByIdForUpdate(500L)).thenReturn(Optional.of(activeAuction));
        when(userRepo.findById(20L)).thenReturn(Optional.of(bidder));
        when(proxyBidRepo.findFirstByAuctionIdAndBidderIdOrderByCreatedAtDesc(500L, 20L))
                .thenReturn(Optional.of(proxy));

        assertThatThrownBy(() -> service.updateProxyMax(500L, 20L, 2000L))
                .isInstanceOfSatisfying(InvalidProxyStateException.class, e ->
                        assertThat(e.getReason()).contains("Cancelled proxy"));
    }

    @Test
    void updateProxyMax_activeWinning_silentlyRaisesMax_noBidsEmitted() {
        // Caller is currently winning at L$2000 with proxy max=3000.
        activeAuction.setCurrentBid(2000L);
        activeAuction.setCurrentBidderId(20L);
        ProxyBid proxy = proxyRow(100L, bidder, 3000L, ProxyBidStatus.ACTIVE);
        when(auctionRepo.findByIdForUpdate(500L)).thenReturn(Optional.of(activeAuction));
        when(userRepo.findById(20L)).thenReturn(Optional.of(bidder));
        when(proxyBidRepo.findFirstByAuctionIdAndBidderIdOrderByCreatedAtDesc(500L, 20L))
                .thenReturn(Optional.of(proxy));

        ProxyBidResponse resp = runInTransaction(() ->
                service.updateProxyMax(500L, 20L, 5000L));

        assertThat(proxy.getMaxAmount()).isEqualTo(5000L);
        assertThat(resp.maxAmount()).isEqualTo(5000L);
        verify(proxyBidRepo).save(proxy);
        verify(bidRepo, never()).save(any());
        // No resolution call — defensive check on repo
        verify(proxyBidRepo, never()).findFirstByAuctionIdAndStatusAndBidderIdNot(
                anyLong(), any(), anyLong());
        verify(publisher, never()).publishSettlement(any());
    }

    @Test
    void updateProxyMax_activeWinning_throwsInvalidProxyMax_whenNotAboveCurrentBid() {
        activeAuction.setCurrentBid(2000L);
        activeAuction.setCurrentBidderId(20L);
        ProxyBid proxy = proxyRow(100L, bidder, 3000L, ProxyBidStatus.ACTIVE);
        when(auctionRepo.findByIdForUpdate(500L)).thenReturn(Optional.of(activeAuction));
        when(userRepo.findById(20L)).thenReturn(Optional.of(bidder));
        when(proxyBidRepo.findFirstByAuctionIdAndBidderIdOrderByCreatedAtDesc(500L, 20L))
                .thenReturn(Optional.of(proxy));

        assertThatThrownBy(() -> service.updateProxyMax(500L, 20L, 2000L))
                .isInstanceOfSatisfying(InvalidProxyMaxException.class, e ->
                        assertThat(e.getReason()).contains("exceed current winning bid"));
    }

    @Test
    void updateProxyMax_exhaustedResurrection_onlyIncreasesAllowed() {
        activeAuction.setCurrentBid(1500L);
        activeAuction.setCurrentBidderId(99L); // some other bidder
        ProxyBid proxy = proxyRow(100L, bidder, 1200L, ProxyBidStatus.EXHAUSTED);
        when(auctionRepo.findByIdForUpdate(500L)).thenReturn(Optional.of(activeAuction));
        when(userRepo.findById(20L)).thenReturn(Optional.of(bidder));
        when(proxyBidRepo.findFirstByAuctionIdAndBidderIdOrderByCreatedAtDesc(500L, 20L))
                .thenReturn(Optional.of(proxy));

        assertThatThrownBy(() -> service.updateProxyMax(500L, 20L, 1200L))
                .isInstanceOfSatisfying(InvalidProxyMaxException.class, e ->
                        assertThat(e.getReason()).contains("Increase only"));
    }

    @Test
    void updateProxyMax_exhaustedResurrection_throwsBidTooLow_whenBelowMinRequired() {
        activeAuction.setCurrentBid(1500L);
        activeAuction.setCurrentBidderId(99L);
        ProxyBid proxy = proxyRow(100L, bidder, 1200L, ProxyBidStatus.EXHAUSTED);
        when(auctionRepo.findByIdForUpdate(500L)).thenReturn(Optional.of(activeAuction));
        when(userRepo.findById(20L)).thenReturn(Optional.of(bidder));
        when(proxyBidRepo.findFirstByAuctionIdAndBidderIdOrderByCreatedAtDesc(500L, 20L))
                .thenReturn(Optional.of(proxy));

        // minRequired = 1500 + 100 = 1600. newMax = 1550 passes increase-only
        // (> proxy.maxAmount=1200) but fails min-required.
        assertThatThrownBy(() -> service.updateProxyMax(500L, 20L, 1550L))
                .isInstanceOfSatisfying(BidTooLowException.class, e ->
                        assertThat(e.getMinRequired()).isEqualTo(1600L));
    }

    @Test
    void updateProxyMax_exhaustedResurrection_flipsToActiveAndResolves() {
        activeAuction.setCurrentBid(1500L);
        activeAuction.setCurrentBidderId(99L);
        ProxyBid proxy = proxyRow(100L, bidder, 1200L, ProxyBidStatus.EXHAUSTED);
        when(auctionRepo.findByIdForUpdate(500L)).thenReturn(Optional.of(activeAuction));
        when(userRepo.findById(20L)).thenReturn(Optional.of(bidder));
        when(proxyBidRepo.findFirstByAuctionIdAndBidderIdOrderByCreatedAtDesc(500L, 20L))
                .thenReturn(Optional.of(proxy));
        // No competing ACTIVE proxy
        when(proxyBidRepo.findFirstByAuctionIdAndStatusAndBidderIdNot(500L, ProxyBidStatus.ACTIVE, 20L))
                .thenReturn(Optional.empty());

        runInTransaction(() -> service.updateProxyMax(500L, 20L, 3000L));

        assertThat(proxy.getStatus()).isEqualTo(ProxyBidStatus.ACTIVE);
        assertThat(proxy.getMaxAmount()).isEqualTo(3000L);
        // No-competitor branch emits one PROXY_AUTO at currentBid+increment
        // (1500 + 100 = 1600).
        ArgumentCaptor<Bid> bidCap = ArgumentCaptor.forClass(Bid.class);
        verify(bidRepo).save(bidCap.capture());
        assertThat(bidCap.getValue().getAmount()).isEqualTo(1600L);
        assertThat(bidCap.getValue().getBidType()).isEqualTo(BidType.PROXY_AUTO);
    }

    // -------------------------------------------------------------------------
    // cancelProxy
    // -------------------------------------------------------------------------

    @Test
    void cancelProxy_throwsProxyBidNotFound_whenNoProxy() {
        when(auctionRepo.findByIdForUpdate(500L)).thenReturn(Optional.of(activeAuction));
        when(proxyBidRepo.findFirstByAuctionIdAndBidderIdOrderByCreatedAtDesc(500L, 20L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelProxy(500L, 20L))
                .isInstanceOf(ProxyBidNotFoundException.class);
    }

    @Test
    void cancelProxy_throwsProxyBidNotFound_whenProxyAlreadyExhausted() {
        // EXHAUSTED is not cancellable — treated as not-found for this endpoint.
        ProxyBid proxy = proxyRow(100L, bidder, 1500L, ProxyBidStatus.EXHAUSTED);
        proxy.setAuction(activeAuction);
        when(auctionRepo.findByIdForUpdate(500L)).thenReturn(Optional.of(activeAuction));
        when(proxyBidRepo.findFirstByAuctionIdAndBidderIdOrderByCreatedAtDesc(500L, 20L))
                .thenReturn(Optional.of(proxy));

        assertThatThrownBy(() -> service.cancelProxy(500L, 20L))
                .isInstanceOf(ProxyBidNotFoundException.class);
    }

    @Test
    void cancelProxy_throwsCannotCancelWinning_whenCallerIsCurrentBidder() {
        activeAuction.setCurrentBidderId(20L);
        ProxyBid proxy = proxyRow(100L, bidder, 1500L, ProxyBidStatus.ACTIVE);
        proxy.setAuction(activeAuction);
        when(auctionRepo.findByIdForUpdate(500L)).thenReturn(Optional.of(activeAuction));
        when(proxyBidRepo.findFirstByAuctionIdAndBidderIdOrderByCreatedAtDesc(500L, 20L))
                .thenReturn(Optional.of(proxy));

        assertThatThrownBy(() -> service.cancelProxy(500L, 20L))
                .isInstanceOf(CannotCancelWinningProxyException.class);
        assertThat(proxy.getStatus()).isEqualTo(ProxyBidStatus.ACTIVE);
    }

    @Test
    void cancelProxy_happyPath_flipsToCancelledAndDoesNotPublish() {
        activeAuction.setCurrentBidderId(99L);  // someone else winning
        ProxyBid proxy = proxyRow(100L, bidder, 1500L, ProxyBidStatus.ACTIVE);
        proxy.setAuction(activeAuction);
        when(auctionRepo.findByIdForUpdate(500L)).thenReturn(Optional.of(activeAuction));
        when(proxyBidRepo.findFirstByAuctionIdAndBidderIdOrderByCreatedAtDesc(500L, 20L))
                .thenReturn(Optional.of(proxy));

        runInTransaction(() -> {
            service.cancelProxy(500L, 20L);
            return null;
        });

        assertThat(proxy.getStatus()).isEqualTo(ProxyBidStatus.CANCELLED);
        verify(proxyBidRepo).save(proxy);
        verify(bidRepo, never()).save(any());
        verify(publisher, never()).publishSettlement(any());
    }

    // -------------------------------------------------------------------------
    // getMyProxy
    // -------------------------------------------------------------------------

    @Test
    void getMyProxy_returnsEmpty_whenNoRow() {
        when(proxyBidRepo.findFirstByAuctionIdAndBidderIdOrderByCreatedAtDesc(500L, 20L))
                .thenReturn(Optional.empty());

        assertThat(service.getMyProxy(500L, 20L)).isEmpty();
    }

    @Test
    void getMyProxy_returnsAnyStatus() {
        // GET returns latest row regardless of status (per spec §7).
        ProxyBid proxy = proxyRow(100L, bidder, 1500L, ProxyBidStatus.EXHAUSTED);
        proxy.setAuction(activeAuction);
        when(proxyBidRepo.findFirstByAuctionIdAndBidderIdOrderByCreatedAtDesc(500L, 20L))
                .thenReturn(Optional.of(proxy));

        Optional<ProxyBidResponse> resp = service.getMyProxy(500L, 20L);
        assertThat(resp).isPresent();
        assertThat(resp.get().status()).isEqualTo(ProxyBidStatus.EXHAUSTED);
        assertThat(resp.get().maxAmount()).isEqualTo(1500L);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ProxyBid proxyRow(Long id, User owner, long maxAmount, ProxyBidStatus status) {
        ProxyBid p = ProxyBid.builder()
                .id(id)
                .auction(activeAuction)
                .bidder(owner)
                .maxAmount(maxAmount)
                .status(status)
                .build();
        p.setCreatedAt(NOW);
        p.setUpdatedAt(NOW);
        return p;
    }

    private <T> T runInTransaction(java.util.function.Supplier<T> body) {
        TransactionTemplate tt = new TransactionTemplate(new FakeTxManager());
        return tt.execute(status -> body.get());
    }

    private static final class FakeTxManager implements PlatformTransactionManager {
        @Override
        public org.springframework.transaction.TransactionStatus getTransaction(TransactionDefinition def) {
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            return new SimpleTransactionStatus(true);
        }

        @Override
        public void commit(org.springframework.transaction.TransactionStatus status) {
            try {
                if (!status.isRollbackOnly()) {
                    TransactionSynchronizationManager.getSynchronizations().forEach(s -> {
                        try { s.beforeCommit(false); } catch (Exception ignored) {}
                    });
                    TransactionSynchronizationManager.getSynchronizations().forEach(s -> {
                        try { s.beforeCompletion(); } catch (Exception ignored) {}
                    });
                    TransactionSynchronizationManager.getSynchronizations().forEach(s -> {
                        try { s.afterCommit(); } catch (Exception ignored) {}
                    });
                }
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
