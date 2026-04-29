package com.slparcelauctions.backend.review.broadcast;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.slparcelauctions.backend.review.ReviewRevealedEnvelope;

import lombok.extern.slf4j.Slf4j;

/**
 * Fallback {@link ReviewBroadcastPublisher} that logs the envelope and
 * drops it. Kept on the classpath as a safety net for unit-test slices or
 * degenerate configurations that omit {@link StompReviewBroadcastPublisher}.
 * In normal dev/prod runs the Stomp bean is present, this
 * {@code @ConditionalOnMissingBean} default steps aside automatically, and
 * the real publisher fans the envelope out to {@code /topic/auction/{id}}.
 *
 * <p>Wired through a dedicated {@code @Configuration} class (not a
 * {@code @Component}) so {@code @ConditionalOnMissingBean} is evaluated
 * against other beans of the publisher type. Mirrors
 * {@link com.slparcelauctions.backend.escrow.broadcast.NoOpEscrowBroadcastPublisher}
 * and {@link com.slparcelauctions.backend.auction.broadcast.NoOpAuctionBroadcastPublisher}.
 */
@Configuration
@Slf4j
public class NoOpReviewBroadcastPublisher {

    @Bean
    @ConditionalOnMissingBean(ReviewBroadcastPublisher.class)
    public ReviewBroadcastPublisher defaultReviewBroadcastPublisher() {
        return new ReviewBroadcastPublisher() {
            @Override
            public void publishReviewRevealed(ReviewRevealedEnvelope envelope) {
                log.debug("no-op publishReviewRevealed: auctionId={}, reviewId={}, revealedAt={}",
                        envelope.auctionId(), envelope.reviewId(), envelope.revealedAt());
            }
        };
    }
}
