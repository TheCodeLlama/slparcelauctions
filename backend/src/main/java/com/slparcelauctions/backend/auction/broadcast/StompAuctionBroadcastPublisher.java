package com.slparcelauctions.backend.auction.broadcast;

import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.auction.dto.AuctionCancelledEnvelope;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Production {@link AuctionBroadcastPublisher} — forwards envelopes to the
 * STOMP broker under {@code /topic/auction/{auctionId}} using Spring's
 * {@link SimpMessagingTemplate}. Invoked exclusively from
 * {@code TransactionSynchronization.afterCommit} callbacks registered by
 * {@code BidService} and {@code ProxyBidService} so subscribers never observe
 * an uncommitted auction state.
 *
 * <p>Declared as a {@code @Component} so Spring picks it up on every profile.
 * The Task 2 fallback {@link NoOpAuctionBroadcastPublisher} uses
 * {@code @ConditionalOnMissingBean(AuctionBroadcastPublisher.class)}, so it
 * steps aside the moment this class is on the classpath. Tests that want a
 * capturing fake swap this bean via {@code @MockitoBean} or
 * {@code @TestConfiguration + @Primary}.
 *
 * <p>Per sub-spec §4, {@code /topic/auction/**} is public — subscribers do
 * not need to authenticate. The publish path here does not enforce auth
 * because it is invoked in-process from the bid-placement service, not by
 * any client. Subscription authorization is enforced in
 * {@code JwtChannelInterceptor}.
 *
 * <p>{@link SimpMessagingTemplate#convertAndSend} tolerates the no-subscriber
 * case silently — tests running without an active STOMP client do not fail
 * here; they just never see the envelope, which is the desired behaviour.
 *
 * <p><strong>Error handling:</strong> {@code convertAndSend} can throw
 * {@link MessagingException} if the broker channel is down or payload
 * serialization fails. These publishes run inside a
 * {@code TransactionSynchronization.afterCommit} callback — the auction
 * state is already durable in the DB by the time we get here, and clients
 * on reconnect re-fetch current state. A dropped publish is therefore
 * best-effort degradation, not data loss. We catch and log at WARN with
 * full context so the exception does not escape back into the
 * afterCommit callback, where Spring's
 * {@code TransactionSynchronizationUtils} would re-log it at ERROR and
 * drown operators in alarm noise.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StompAuctionBroadcastPublisher implements AuctionBroadcastPublisher {

    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public void publishSettlement(BidSettlementEnvelope envelope) {
        String destination = "/topic/auction/" + envelope.auctionPublicId();
        log.debug("Publishing BID_SETTLEMENT to {}: currentBid={}, bidCount={}, newBids={}",
                destination, envelope.currentBid(), envelope.bidCount(),
                envelope.newBids() == null ? 0 : envelope.newBids().size());
        try {
            messagingTemplate.convertAndSend(destination, envelope);
        } catch (MessagingException e) {
            log.warn("Failed to publish BID_SETTLEMENT for auction {}: {}",
                    envelope.auctionPublicId(), e.getMessage(), e);
        }
    }

    @Override
    public void publishEnded(AuctionEndedEnvelope envelope) {
        String destination = "/topic/auction/" + envelope.auctionPublicId();
        log.info("Publishing AUCTION_ENDED to {}: outcome={}, finalBid={}, winnerPublicId={}",
                destination, envelope.endOutcome(), envelope.finalBid(), envelope.winnerPublicId());
        try {
            messagingTemplate.convertAndSend(destination, envelope);
        } catch (MessagingException e) {
            log.warn("Failed to publish AUCTION_ENDED for auction {}: {}",
                    envelope.auctionPublicId(), e.getMessage(), e);
        }
    }

    @Override
    public void publishCancelled(AuctionCancelledEnvelope envelope) {
        String destination = "/topic/auction/" + envelope.auctionPublicId();
        log.info("Publishing AUCTION_CANCELLED to {}: hadBids={}, cancelledAt={}",
                destination, envelope.hadBids(), envelope.cancelledAt());
        try {
            messagingTemplate.convertAndSend(destination, envelope);
        } catch (MessagingException e) {
            log.warn("Failed to publish AUCTION_CANCELLED for auction {}: {}",
                    envelope.auctionPublicId(), e.getMessage(), e);
        }
    }
}
