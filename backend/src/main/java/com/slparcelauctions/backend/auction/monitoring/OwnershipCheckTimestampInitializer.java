package com.slparcelauctions.backend.auction.monitoring;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.monitoring.config.OwnershipMonitorProperties;

import lombok.RequiredArgsConstructor;

/**
 * Stamps {@link Auction#getLastOwnershipCheckAt()} on the initial transition
 * to {@link com.slparcelauctions.backend.auction.AuctionStatus#ACTIVE}. The
 * timestamp is deliberately offset into the past by a random number of minutes
 * up to {@link OwnershipMonitorProperties#getJitterMaxMinutes()}, so a burst
 * of activations (e.g. multiple sellers verifying around the same time) does
 * not cause all their first checks to fall in the same scheduler sweep.
 *
 * <p>Called by Method A ({@link com.slparcelauctions.backend.auction.AuctionVerificationService}),
 * Method B ({@link com.slparcelauctions.backend.sl.SlParcelVerifyService}),
 * and Method C ({@link com.slparcelauctions.backend.bot.BotTaskService}) just
 * before the save that persists the ACTIVE status. See spec §8.2.
 *
 * <p>Uses {@link ThreadLocalRandom} because jitter is non-security; an
 * attacker gains nothing from predicting when a particular listing will
 * first be checked.
 */
@Component
@RequiredArgsConstructor
public class OwnershipCheckTimestampInitializer {

    private final OwnershipMonitorProperties props;
    private final Clock clock;

    public void onActivated(Auction auction) {
        int jitterMax = props.getJitterMaxMinutes();
        long offsetMinutes = jitterMax > 0
                // nextLong(bound) returns [0, bound), so we pass jitterMax+1 so
                // the full inclusive range [0, jitterMax] is reachable.
                ? ThreadLocalRandom.current().nextLong(jitterMax + 1L)
                : 0L;
        auction.setLastOwnershipCheckAt(OffsetDateTime.now(clock).minusMinutes(offsetMinutes));
    }
}
