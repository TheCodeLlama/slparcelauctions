package com.slparcelauctions.backend.review.broadcast;

import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.review.ReviewRevealedEnvelope;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Production {@link ReviewBroadcastPublisher}. Forwards envelopes to the
 * STOMP broker under {@code /topic/auction/{auctionId}} using Spring's
 * {@link SimpMessagingTemplate}. Always invoked from
 * {@code TransactionSynchronization.afterCommit} callbacks registered by
 * {@code ReviewService.reveal}, so subscribers never observe an
 * uncommitted reveal.
 *
 * <p>{@code convertAndSend} failures are caught + logged at WARN so broker
 * issues don't surface back through the afterCommit callback — Spring's
 * {@code TransactionSynchronizationUtils} would otherwise re-log at ERROR
 * and drown operators in alarm noise. The review row is already durable
 * in the DB by the time we publish, so a dropped envelope is best-effort
 * degradation (clients re-fetch on reconnect), not data loss.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StompReviewBroadcastPublisher implements ReviewBroadcastPublisher {

    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public void publishReviewRevealed(ReviewRevealedEnvelope envelope) {
        String destination = "/topic/auction/" + envelope.auctionPublicId();
        log.debug("Publishing REVIEW_REVEALED to {}: reviewPublicId={}",
                destination, envelope.reviewPublicId());
        try {
            messagingTemplate.convertAndSend(destination, envelope);
        } catch (MessagingException e) {
            log.warn("Failed to publish REVIEW_REVEALED for auction {}: {}",
                    envelope.auctionPublicId(), e.getMessage(), e);
        }
    }
}
