package com.slparcelauctions.backend.wallet.exception;

/**
 * Defensive — thrown by {@code WalletService.autoFundEscrow} when a winner's
 * active reservation does not equal the auction's final-bid amount. Should
 * be impossible: hard reservation is locked to the bid amount at bid time,
 * and the bid amount is what becomes {@code finalBidAmount} at close. If
 * this fires, there's a system-integrity bug — the calling auction-end
 * task catches and freezes the escrow as {@code FROZEN} for forensics.
 */
public class BidReservationAmountMismatchException extends RuntimeException {
    private final long reservationAmount;
    private final long finalBidAmount;

    public BidReservationAmountMismatchException(long reservationAmount, long finalBidAmount) {
        super("BID-RESERVATION-AMOUNT-MISMATCH: reservation=" + reservationAmount
                + ", finalBid=" + finalBidAmount);
        this.reservationAmount = reservationAmount;
        this.finalBidAmount = finalBidAmount;
    }

    public long getReservationAmount() {
        return reservationAmount;
    }

    public long getFinalBidAmount() {
        return finalBidAmount;
    }
}
