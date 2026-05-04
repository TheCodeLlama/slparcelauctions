package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
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

import com.slparcelauctions.backend.admin.ban.BanCheckService;
import com.slparcelauctions.backend.auction.broadcast.AuctionBroadcastPublisher;
import com.slparcelauctions.backend.escrow.EscrowService;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Unit coverage for snipe-protection extension inside
 * {@link BidService#placeBid}. Mockito-driven with a fixed {@link Clock} so
 * the {@code Duration.between(bid.createdAt, auction.endsAt)} comparison is
 * deterministic. Each test prepares an auction with specific
 * {@code endsAt} / {@code snipeProtect} / {@code snipeWindowMin} values and
 * drives a single placement, then asserts whether the auction's
 * {@code endsAt} advanced and whether the bid row was stamped with
 * {@code snipeExtensionMinutes} / {@code newEndsAt}.
 *
 * <p>Stacking across multiple placements lives in
 * {@code SnipeAndBuyNowIntegrationTest} — Task 3 emits only one bid per
 * transaction (no proxy counter yet) so in-transaction stacking is only
 * exercisable via the integration-level multi-placement flow.
 */
@ExtendWith(MockitoExtension.class)
class BidServiceSnipeTest {

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
        service = new BidService(auctionRepo, bidRepo, proxyBidRepo, userRepo, clock, publisher, escrowService, mock(com.slparcelauctions.backend.notification.NotificationPublisher.class), mock(BanCheckService.class), mock(com.slparcelauctions.backend.wallet.WalletService.class), mock(com.slparcelauctions.backend.wallet.BidReservationRepository.class));

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

        // Stamp the bid with a createdAt of NOW — matches what
        // @CreationTimestamp would set in production and what the service
        // reads for its Duration.between comparison. Lenient because some
        // tests exit before save (e.g. validation-branch coverage — not in
        // this slice, but the pattern matches BidServiceTest).
        lenient().when(bidRepo.save(any(Bid.class))).thenAnswer(inv -> {
            Bid b = inv.getArgument(0);
            setBaseEntityField(b, "id", 9000L);
            setBaseEntityField(b, "createdAt", NOW);
            return b;
        });
        lenient().when(auctionRepo.save(any(Auction.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void doesNotExtend_whenSnipeProtectFalse() {
        // Bid is well inside a hypothetical 15min window, but snipeProtect
        // is off — the service must not touch endsAt or stamp the bid row.
        auction.setSnipeProtect(false);
        auction.setSnipeWindowMin(15);
        auction.setEndsAt(NOW.plusMinutes(5));
        OffsetDateTime originalEndsAt = auction.getEndsAt();

        when(auctionRepo.findByIdForUpdate(500L)).thenReturn(Optional.of(auction));
        when(userRepo.findById(20L)).thenReturn(Optional.of(bidder));

        runInTx(() -> service.placeBid(500L, 20L, 1000L, "1.2.3.4"));

        assertThat(auction.getEndsAt()).isEqualTo(originalEndsAt);
        Bid saved = captureSavedBid();
        assertThat(saved.getSnipeExtensionMinutes()).isNull();
        assertThat(saved.getNewEndsAt()).isNull();
    }

    @Test
    void doesNotExtend_whenBidOutsideWindow() {
        // Bid at T-20min on a 15min window → 20min > 15min → outside.
        auction.setSnipeProtect(true);
        auction.setSnipeWindowMin(15);
        auction.setEndsAt(NOW.plusMinutes(20));
        OffsetDateTime originalEndsAt = auction.getEndsAt();

        when(auctionRepo.findByIdForUpdate(500L)).thenReturn(Optional.of(auction));
        when(userRepo.findById(20L)).thenReturn(Optional.of(bidder));

        runInTx(() -> service.placeBid(500L, 20L, 1000L, "1.2.3.4"));

        assertThat(auction.getEndsAt()).isEqualTo(originalEndsAt);
        Bid saved = captureSavedBid();
        assertThat(saved.getSnipeExtensionMinutes()).isNull();
        assertThat(saved.getNewEndsAt()).isNull();
    }

    @Test
    void extends_whenBidInWindow() {
        // Bid at T-1min on a 15min window → 1min <= 15min → extend.
        auction.setSnipeProtect(true);
        auction.setSnipeWindowMin(15);
        auction.setEndsAt(NOW.plusMinutes(1));

        when(auctionRepo.findByIdForUpdate(500L)).thenReturn(Optional.of(auction));
        when(userRepo.findById(20L)).thenReturn(Optional.of(bidder));

        runInTx(() -> service.placeBid(500L, 20L, 1000L, "1.2.3.4"));

        // Extension is bid.createdAt + 15min = NOW + 15min.
        OffsetDateTime expected = NOW.plusMinutes(15);
        assertThat(auction.getEndsAt()).isEqualTo(expected);
        Bid saved = captureSavedBid();
        assertThat(saved.getSnipeExtensionMinutes()).isEqualTo(15);
        assertThat(saved.getNewEndsAt()).isEqualTo(expected);
    }

    @Test
    void extends_whenBidAtExactlyWindowBoundary() {
        // Bid at T-15min, window=15min → remaining == window → <= 0 is true
        // via compareTo. Boundary is inclusive.
        auction.setSnipeProtect(true);
        auction.setSnipeWindowMin(15);
        auction.setEndsAt(NOW.plusMinutes(15));

        when(auctionRepo.findByIdForUpdate(500L)).thenReturn(Optional.of(auction));
        when(userRepo.findById(20L)).thenReturn(Optional.of(bidder));

        runInTx(() -> service.placeBid(500L, 20L, 1000L, "1.2.3.4"));

        OffsetDateTime expected = NOW.plusMinutes(15);
        assertThat(auction.getEndsAt()).isEqualTo(expected);
        Bid saved = captureSavedBid();
        assertThat(saved.getSnipeExtensionMinutes()).isEqualTo(15);
        assertThat(saved.getNewEndsAt()).isEqualTo(expected);
    }

    @Test
    void extendsFromBidTimestampNotOriginalEndsAt() {
        // Regression against a naive "endsAt = endsAt + window" bug.
        // Bid at T-1min on 15min window: correct new endsAt = bid.createdAt
        // + 15min = NOW + 15min. Naive implementation would produce
        // auction.endsAt + 15min = (NOW + 1min) + 15min = NOW + 16min.
        auction.setSnipeProtect(true);
        auction.setSnipeWindowMin(15);
        auction.setEndsAt(NOW.plusMinutes(1));

        when(auctionRepo.findByIdForUpdate(500L)).thenReturn(Optional.of(auction));
        when(userRepo.findById(20L)).thenReturn(Optional.of(bidder));

        runInTx(() -> service.placeBid(500L, 20L, 1000L, "1.2.3.4"));

        OffsetDateTime correct = NOW.plusMinutes(15);
        OffsetDateTime naive = NOW.plusMinutes(16);
        assertThat(auction.getEndsAt())
                .isEqualTo(correct)
                .isNotEqualTo(naive);
    }

    // ------------------------------------------------------------------

    private Bid captureSavedBid() {
        ArgumentCaptor<Bid> cap = ArgumentCaptor.forClass(Bid.class);
        org.mockito.Mockito.verify(bidRepo).save(cap.capture());
        return cap.getValue();
    }

    private void runInTx(Runnable body) {
        TransactionTemplate tt = new TransactionTemplate(new FakeTxManager());
        tt.execute(status -> {
            body.run();
            return null;
        });
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
