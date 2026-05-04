package com.slparcelauctions.backend.auction.broadcast;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.slparcelauctions.backend.auction.dto.AuctionCancelledEnvelope;

import lombok.extern.slf4j.Slf4j;

/**
 * Fallback {@link AuctionBroadcastPublisher} that logs the envelope and drops
 * it. Kept on the classpath as a safety net for unit-test slices or
 * degenerate configurations that omit
 * {@link StompAuctionBroadcastPublisher}. In normal dev/prod runs the Stomp
 * bean is present, this {@code @ConditionalOnMissingBean} default steps
 * aside automatically, and the real publisher fans the envelope out to
 * {@code /topic/auction/{id}}.
 *
 * <p>Wired through a dedicated {@code @Configuration} class (not a
 * {@code @Component}) so {@code @ConditionalOnMissingBean} is evaluated
 * against other beans of the publisher type — a class-level annotation
 * would only check for other definitions of this same class.
 */
@Configuration
@Slf4j
public class NoOpAuctionBroadcastPublisher {

    @Bean
    @ConditionalOnMissingBean(AuctionBroadcastPublisher.class)
    public AuctionBroadcastPublisher defaultAuctionBroadcastPublisher() {
        return new AuctionBroadcastPublisher() {
            @Override
            public void publishSettlement(BidSettlementEnvelope envelope) {
                log.debug("no-op publishSettlement: auctionId={}, currentBid={}, bidCount={}",
                        envelope.auctionPublicId(), envelope.currentBid(), envelope.bidCount());
            }

            @Override
            public void publishEnded(AuctionEndedEnvelope envelope) {
                log.debug("no-op publishEnded: auctionId={}, outcome={}",
                        envelope.auctionPublicId(), envelope.endOutcome());
            }

            @Override
            public void publishCancelled(AuctionCancelledEnvelope envelope) {
                log.debug("no-op publishCancelled: auctionId={}, hadBids={}",
                        envelope.auctionPublicId(), envelope.hadBids());
            }
        };
    }
}
