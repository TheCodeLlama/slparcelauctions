package com.slparcelauctions.backend.auction.mybids;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionEndOutcome;
import com.slparcelauctions.backend.auction.AuctionStatus;

/**
 * Table-driven coverage of every branch in {@link MyBidStatusDeriver}. Each
 * row sets only the fields the deriver actually reads
 * ({@code status}, {@code endOutcome}, {@code currentBidderId},
 * {@code winnerUserId}) so changes elsewhere on {@link Auction} don't flake
 * this test.
 *
 * <p>Updated for the 2026-05-17 auction-status rewire: the legacy ENDED status
 * fanned out across {@code TRANSFER_PENDING}, {@code DISPUTED},
 * {@code COMPLETED}, {@code EXPIRED}, {@code FROZEN}, so the deriver branches
 * on those concrete statuses now. Winners on FROZEN or EXPIRED-after-SOLD
 * collapse to {@code LOST} because {@link MyBidStatus} has no dedicated
 * "won-the-bid-but-lost-the-parcel" value.
 */
class MyBidStatusDeriverTest {

    private static final Long CALLER = 42L;
    private static final Long OTHER = 99L;

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void derive(String scenario, Auction auction, MyBidStatus expected) {
        assertThat(MyBidStatusDeriver.derive(CALLER, auction)).isEqualTo(expected);
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of(
                        "ACTIVE + caller is current bidder -> WINNING",
                        active(CALLER),
                        MyBidStatus.WINNING),
                Arguments.of(
                        "ACTIVE + someone else is current bidder -> OUTBID",
                        active(OTHER),
                        MyBidStatus.OUTBID),
                Arguments.of(
                        "ACTIVE + no current bidder -> OUTBID (caller bid but someone else led)",
                        active(null),
                        MyBidStatus.OUTBID),
                // TRANSFER_PENDING — post-close mid-flight escrow row.
                Arguments.of(
                        "TRANSFER_PENDING + caller is winner -> WON",
                        statusWithWinner(AuctionStatus.TRANSFER_PENDING, CALLER),
                        MyBidStatus.WON),
                Arguments.of(
                        "TRANSFER_PENDING + someone else is winner -> LOST",
                        statusWithWinner(AuctionStatus.TRANSFER_PENDING, OTHER),
                        MyBidStatus.LOST),
                // DISPUTED — escrow row in dispute; auction status mirrors.
                Arguments.of(
                        "DISPUTED + caller is winner -> WON",
                        statusWithWinner(AuctionStatus.DISPUTED, CALLER),
                        MyBidStatus.WON),
                Arguments.of(
                        "DISPUTED + someone else is winner -> LOST",
                        statusWithWinner(AuctionStatus.DISPUTED, OTHER),
                        MyBidStatus.LOST),
                // COMPLETED — happy-path terminal.
                Arguments.of(
                        "COMPLETED + caller is winner -> WON",
                        statusWithWinner(AuctionStatus.COMPLETED, CALLER),
                        MyBidStatus.WON),
                Arguments.of(
                        "COMPLETED + someone else is winner -> LOST",
                        statusWithWinner(AuctionStatus.COMPLETED, OTHER),
                        MyBidStatus.LOST),
                // EXPIRED with endOutcome=SOLD (transfer-timeout) — winner
                // won the bid but lost the parcel; collapses to LOST.
                Arguments.of(
                        "EXPIRED + SOLD + caller was winner -> LOST",
                        expiredWithOutcome(AuctionEndOutcome.SOLD, OTHER, CALLER),
                        MyBidStatus.LOST),
                Arguments.of(
                        "EXPIRED + BOUGHT_NOW + caller was winner -> LOST",
                        expiredWithOutcome(AuctionEndOutcome.BOUGHT_NOW, OTHER, CALLER),
                        MyBidStatus.LOST),
                Arguments.of(
                        "EXPIRED + RESERVE_NOT_MET + caller is high bidder -> RESERVE_NOT_MET",
                        expiredWithOutcome(AuctionEndOutcome.RESERVE_NOT_MET, CALLER, null),
                        MyBidStatus.RESERVE_NOT_MET),
                Arguments.of(
                        "EXPIRED + RESERVE_NOT_MET + someone else is high bidder -> LOST",
                        expiredWithOutcome(AuctionEndOutcome.RESERVE_NOT_MET, OTHER, null),
                        MyBidStatus.LOST),
                Arguments.of(
                        "EXPIRED + NO_BIDS -> LOST (defensive; unreachable in practice)",
                        expiredWithOutcome(AuctionEndOutcome.NO_BIDS, null, null),
                        MyBidStatus.LOST),
                Arguments.of(
                        "EXPIRED + null endOutcome -> LOST (defensive)",
                        expiredWithOutcome(null, CALLER, CALLER),
                        MyBidStatus.LOST),
                // FROZEN — escrow frozen for fraud; winner loses the parcel.
                Arguments.of(
                        "FROZEN + caller was winner -> LOST",
                        statusWithWinner(AuctionStatus.FROZEN, CALLER),
                        MyBidStatus.LOST),
                Arguments.of(
                        "FROZEN + someone else was winner -> LOST",
                        statusWithWinner(AuctionStatus.FROZEN, OTHER),
                        MyBidStatus.LOST),
                Arguments.of(
                        "CANCELLED -> CANCELLED",
                        status(AuctionStatus.CANCELLED),
                        MyBidStatus.CANCELLED),
                Arguments.of(
                        "SUSPENDED -> SUSPENDED",
                        status(AuctionStatus.SUSPENDED),
                        MyBidStatus.SUSPENDED),
                Arguments.of(
                        "DRAFT (unexpected transitional) -> LOST (defensive)",
                        status(AuctionStatus.DRAFT),
                        MyBidStatus.LOST));
    }

    private static Auction active(Long currentBidderId) {
        Auction a = new Auction();
        a.setStatus(AuctionStatus.ACTIVE);
        a.setCurrentBidderId(currentBidderId);
        return a;
    }

    private static Auction expiredWithOutcome(
            AuctionEndOutcome outcome, Long currentBidderId, Long winnerId) {
        Auction a = new Auction();
        a.setStatus(AuctionStatus.EXPIRED);
        a.setEndOutcome(outcome);
        a.setCurrentBidderId(currentBidderId);
        a.setWinnerUserId(winnerId);
        return a;
    }

    private static Auction statusWithWinner(AuctionStatus s, Long winnerId) {
        Auction a = new Auction();
        a.setStatus(s);
        a.setWinnerUserId(winnerId);
        return a;
    }

    private static Auction status(AuctionStatus s) {
        Auction a = new Auction();
        a.setStatus(s);
        return a;
    }
}
