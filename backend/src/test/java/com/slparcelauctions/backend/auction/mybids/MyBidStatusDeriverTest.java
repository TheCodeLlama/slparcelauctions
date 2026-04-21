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
                Arguments.of(
                        "ENDED + SOLD + caller is winner -> WON",
                        ended(AuctionEndOutcome.SOLD, OTHER, CALLER),
                        MyBidStatus.WON),
                Arguments.of(
                        "ENDED + SOLD + someone else is winner -> LOST",
                        ended(AuctionEndOutcome.SOLD, CALLER, OTHER),
                        MyBidStatus.LOST),
                Arguments.of(
                        "ENDED + BOUGHT_NOW + caller is winner -> WON",
                        ended(AuctionEndOutcome.BOUGHT_NOW, OTHER, CALLER),
                        MyBidStatus.WON),
                Arguments.of(
                        "ENDED + BOUGHT_NOW + someone else is winner -> LOST",
                        ended(AuctionEndOutcome.BOUGHT_NOW, CALLER, OTHER),
                        MyBidStatus.LOST),
                Arguments.of(
                        "ENDED + RESERVE_NOT_MET + caller is high bidder -> RESERVE_NOT_MET",
                        ended(AuctionEndOutcome.RESERVE_NOT_MET, CALLER, null),
                        MyBidStatus.RESERVE_NOT_MET),
                Arguments.of(
                        "ENDED + RESERVE_NOT_MET + someone else is high bidder -> LOST",
                        ended(AuctionEndOutcome.RESERVE_NOT_MET, OTHER, null),
                        MyBidStatus.LOST),
                Arguments.of(
                        "ENDED + NO_BIDS -> LOST (defensive; unreachable in practice)",
                        ended(AuctionEndOutcome.NO_BIDS, null, null),
                        MyBidStatus.LOST),
                Arguments.of(
                        "ENDED + null endOutcome -> LOST (defensive)",
                        ended(null, CALLER, CALLER),
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
                        MyBidStatus.LOST),
                Arguments.of(
                        "COMPLETED (downstream terminal) -> LOST (defensive)",
                        status(AuctionStatus.COMPLETED),
                        MyBidStatus.LOST));
    }

    private static Auction active(Long currentBidderId) {
        Auction a = new Auction();
        a.setStatus(AuctionStatus.ACTIVE);
        a.setCurrentBidderId(currentBidderId);
        return a;
    }

    private static Auction ended(AuctionEndOutcome outcome, Long currentBidderId, Long winnerId) {
        Auction a = new Auction();
        a.setStatus(AuctionStatus.ENDED);
        a.setEndOutcome(outcome);
        a.setCurrentBidderId(currentBidderId);
        a.setWinnerUserId(winnerId);
        return a;
    }

    private static Auction status(AuctionStatus s) {
        Auction a = new Auction();
        a.setStatus(s);
        return a;
    }
}
