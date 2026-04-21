package com.slparcelauctions.backend.auction.broadcast;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

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
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StompAuctionBroadcastPublisher implements AuctionBroadcastPublisher {

    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public void publishSettlement(BidSettlementEnvelope envelope) {
        String destination = "/topic/auction/" + envelope.auctionId();
        log.debug("Publishing BID_SETTLEMENT to {}: currentBid={}, bidCount={}, newBids={}",
                destination, envelope.currentBid(), envelope.bidCount(),
                envelope.newBids() == null ? 0 : envelope.newBids().size());
        messagingTemplate.convertAndSend(destination, envelope);
    }

    @Override
    public void publishEnded(AuctionEndedEnvelope envelope) {
        String destination = "/topic/auction/" + envelope.auctionId();
        log.info("Publishing AUCTION_ENDED to {}: outcome={}, finalBid={}, winnerUserId={}",
                destination, envelope.endOutcome(), envelope.finalBid(), envelope.winnerUserId());
        messagingTemplate.convertAndSend(destination, envelope);
    }
}
