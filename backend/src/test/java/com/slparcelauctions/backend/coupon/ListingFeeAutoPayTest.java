package com.slparcelauctions.backend.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.escrow.EscrowTransaction;
import com.slparcelauctions.backend.escrow.EscrowTransactionRepository;
import com.slparcelauctions.backend.escrow.EscrowTransactionStatus;
import com.slparcelauctions.backend.escrow.EscrowTransactionType;
import com.slparcelauctions.backend.escrow.payment.ListingFeePaymentService;
import com.slparcelauctions.backend.escrow.terminal.TerminalRepository;
import com.slparcelauctions.backend.escrow.terminal.TerminalService;
import com.slparcelauctions.backend.user.User;

/**
 * Plan Task 10 coverage for the coupon-driven L$0 listing-fee auto-pay
 * path on {@link ListingFeePaymentService#autoPayIfFreeAfterCreation(Auction)}.
 * Asserts the DRAFT to DRAFT_PAID transition, the ledger row shape, and
 * the no-op guards that keep the call safe on the non-coupon path.
 */
@ExtendWith(MockitoExtension.class)
class ListingFeeAutoPayTest {

    @Mock AuctionRepository auctionRepo;
    @Mock TerminalRepository terminalRepo;
    @Mock TerminalService terminalService;
    @Mock EscrowTransactionRepository ledgerRepo;
    @Spy Clock clock = Clock.fixed(Instant.parse("2026-05-20T10:00:00Z"), ZoneOffset.UTC);

    @InjectMocks ListingFeePaymentService service;

    private User seller;

    @BeforeEach
    void setUp() {
        seller = User.builder()
                .id(7L)
                .email("s@example.com")
                .username("s")
                .verified(true)
                .slAvatarUuid(UUID.randomUUID())
                .build();
    }

    /**
     * Happy path: listingFeeAmt=0, DRAFT, not paid yet. The service must
     * flip the auction to DRAFT_PAID, stamp listingFeePaid + paidAt, and
     * write a zero-amount LISTING_FEE_PAYMENT ledger row with a
     * coupon-waiver synthetic key.
     */
    @Test
    void zeroFee_flipsToDraftPaidAndWritesLedger() {
        Auction a = draftAuctionWithFee(0L, 42L);
        when(auctionRepo.save(any(Auction.class))).thenAnswer(inv -> inv.getArgument(0));

        service.autoPayIfFreeAfterCreation(a);

        assertThat(a.getStatus()).isEqualTo(AuctionStatus.DRAFT_PAID);
        assertThat(a.getListingFeePaid()).isTrue();
        assertThat(a.getListingFeePaidAt()).isEqualTo(OffsetDateTime.now(clock));
        assertThat(a.getListingFeeTxn()).isEqualTo("coupon-waiver-100");

        ArgumentCaptor<EscrowTransaction> captor = ArgumentCaptor.forClass(EscrowTransaction.class);
        verify(ledgerRepo).save(captor.capture());
        EscrowTransaction tx = captor.getValue();
        assertThat(tx.getAuction()).isEqualTo(a);
        assertThat(tx.getType()).isEqualTo(EscrowTransactionType.LISTING_FEE_PAYMENT);
        assertThat(tx.getStatus()).isEqualTo(EscrowTransactionStatus.COMPLETED);
        assertThat(tx.getAmount()).isZero();
        assertThat(tx.getPayer()).isEqualTo(seller);
        assertThat(tx.getSlTransactionId()).isEqualTo("coupon-waiver-100");
        assertThat(tx.getCompletedAt()).isEqualTo(OffsetDateTime.now(clock));
    }

    /**
     * Partial discount (listingFeeAmt > 0) is the terminal-flow path, so
     * auto-pay must be a no-op, leaving the seller to walk to a
     * listing-fee terminal.
     */
    @Test
    void partialDiscount_noOp() {
        Auction a = draftAuctionWithFee(50L, 42L);

        service.autoPayIfFreeAfterCreation(a);

        assertThat(a.getStatus()).isEqualTo(AuctionStatus.DRAFT);
        assertThat(a.getListingFeePaid()).isFalse();
        verify(ledgerRepo, never()).save(any(EscrowTransaction.class));
        verify(auctionRepo, never()).save(any(Auction.class));
    }

    /**
     * No coupon applies (listingFeeAmt is the default 100): the seller
     * still walks to a terminal. Auto-pay is a no-op.
     */
    @Test
    void defaultFee_noOp() {
        Auction a = draftAuctionWithFee(100L, null);

        service.autoPayIfFreeAfterCreation(a);

        assertThat(a.getStatus()).isEqualTo(AuctionStatus.DRAFT);
        assertThat(a.getListingFeePaid()).isFalse();
        verify(ledgerRepo, never()).save(any(EscrowTransaction.class));
    }

    /**
     * Idempotency guard: an already-paid auction must not double-write
     * a ledger row even if the listingFeeAmt is still 0. Defense against
     * accidental re-invocation by upstream callers.
     */
    @Test
    void alreadyPaid_noOp() {
        Auction a = draftAuctionWithFee(0L, 42L);
        a.setListingFeePaid(true);
        a.setStatus(AuctionStatus.DRAFT_PAID);

        service.autoPayIfFreeAfterCreation(a);

        verify(ledgerRepo, never()).save(any(EscrowTransaction.class));
        verify(auctionRepo, never()).save(any(Auction.class));
    }

    /**
     * State guard: if the auction has already moved past DRAFT (e.g.
     * paid via terminal and now in VERIFICATION_PENDING), the auto-pay
     * path must not flip it back or rewrite the ledger.
     */
    @Test
    void notDraft_noOp() {
        Auction a = draftAuctionWithFee(0L, 42L);
        a.setStatus(AuctionStatus.VERIFICATION_PENDING);

        service.autoPayIfFreeAfterCreation(a);

        assertThat(a.getStatus()).isEqualTo(AuctionStatus.VERIFICATION_PENDING);
        verify(ledgerRepo, never()).save(any(EscrowTransaction.class));
    }

    /**
     * Null listingFeeAmt (pre-Task-10 entry into this method via a stale
     * code path or an entity loaded before the column was populated) is
     * treated like a non-zero fee: skip auto-pay rather than risk flipping
     * an unintended free transition.
     */
    @Test
    void nullFee_noOp() {
        Auction a = draftAuctionWithFee(null, null);

        service.autoPayIfFreeAfterCreation(a);

        assertThat(a.getStatus()).isEqualTo(AuctionStatus.DRAFT);
        verify(ledgerRepo, never()).save(any(EscrowTransaction.class));
    }

    private Auction draftAuctionWithFee(Long fee, Long listingFeeCouponGrantId) {
        Auction a = Auction.builder()
                .seller(seller)
                .status(AuctionStatus.DRAFT)
                .listingFeePaid(false)
                .listingFeeAmt(fee)
                .listingFeeCouponGrantId(listingFeeCouponGrantId)
                .title("L$0 listing")
                .startingBid(1000L)
                .durationHours(168)
                .snipeProtect(false)
                .currentBid(0L)
                .bidCount(0)
                .commissionRate(new BigDecimal("0.05"))
                .slParcelUuid(UUID.randomUUID())
                .build();
        setBaseEntityField(a, "id", 100L);
        return a;
    }

    private static void setBaseEntityField(Object entity, String fieldName, Object value) {
        try {
            java.lang.reflect.Field f =
                    com.slparcelauctions.backend.common.BaseEntity.class
                            .getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(entity, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
