package com.slparcelauctions.backend.escrow.broadcast;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.extern.slf4j.Slf4j;

/**
 * Fallback {@link EscrowBroadcastPublisher} that logs the envelope and drops
 * it. Kept on the classpath as a safety net for unit-test slices or
 * degenerate configurations that omit {@link StompEscrowBroadcastPublisher}.
 * In normal dev/prod runs the Stomp bean is present, this
 * {@code @ConditionalOnMissingBean} default steps aside automatically, and
 * the real publisher fans the envelope out to {@code /topic/auction/{id}}.
 *
 * <p>Wired through a dedicated {@code @Configuration} class (not a
 * {@code @Component}) so {@code @ConditionalOnMissingBean} is evaluated
 * against other beans of the publisher type — a class-level annotation
 * would only check for other definitions of this same class. Mirrors the
 * existing {@code NoOpAuctionBroadcastPublisher} pattern.
 */
@Configuration
@Slf4j
public class NoOpEscrowBroadcastPublisher {

    @Bean
    @ConditionalOnMissingBean(EscrowBroadcastPublisher.class)
    public EscrowBroadcastPublisher defaultEscrowBroadcastPublisher() {
        return new EscrowBroadcastPublisher() {
            @Override
            public void publishCreated(EscrowCreatedEnvelope envelope) {
                log.debug("no-op publishCreated: auctionId={}, escrowId={}, state={}",
                        envelope.auctionId(), envelope.escrowId(), envelope.state());
            }
        };
    }
}
