package com.slparcelauctions.backend.auction.broadcast;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.extern.slf4j.Slf4j;

/**
 * Default {@link AuctionBroadcastPublisher} configuration for Task 2 — logs
 * the envelope type and drops it. Task 5 registers a STOMP-backed
 * implementation; because the production bean is declared elsewhere, this
 * {@code @ConditionalOnMissingBean} default steps aside automatically once
 * the real publisher exists. This lets the bid-placement path compile, run
 * tests, and publish envelopes in dev/test profiles without standing up a
 * WebSocket broker.
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
                        envelope.auctionId(), envelope.currentBid(), envelope.bidCount());
            }

            @Override
            public void publishEnded(AuctionEndedEnvelope envelope) {
                log.debug("no-op publishEnded: auctionId={}, outcome={}",
                        envelope.auctionId(), envelope.endOutcome());
            }
        };
    }
}
