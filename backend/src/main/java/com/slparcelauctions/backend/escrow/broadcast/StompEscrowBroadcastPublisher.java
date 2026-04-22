package com.slparcelauctions.backend.escrow.broadcast;

import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Production {@link EscrowBroadcastPublisher}. Forwards envelopes to the
 * STOMP broker under {@code /topic/auction/{auctionId}} using Spring's
 * {@link SimpMessagingTemplate}. Always invoked from
 * {@code TransactionSynchronization.afterCommit} callbacks, so subscribers
 * never observe uncommitted state.
 *
 * <p>Declared as a {@code @Component} so Spring picks it up on every profile.
 * The {@link NoOpEscrowBroadcastPublisher} fallback uses
 * {@code @ConditionalOnMissingBean(EscrowBroadcastPublisher.class)} so it
 * steps aside the moment this class is on the classpath.
 *
 * <p>{@code convertAndSend} failures are caught + logged at WARN so broker
 * issues don't surface back through the afterCommit callback — Spring's
 * {@code TransactionSynchronizationUtils} would otherwise re-log at ERROR
 * and drown operators in alarm noise. The escrow row is already durable in
 * the DB by the time we publish, so a dropped envelope is best-effort
 * degradation (clients re-fetch on reconnect), not data loss.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StompEscrowBroadcastPublisher implements EscrowBroadcastPublisher {

    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public void publishCreated(EscrowCreatedEnvelope envelope) {
        publish(envelope, envelope.auctionId(), "ESCROW_CREATED");
    }

    @Override
    public void publishDisputed(EscrowDisputedEnvelope envelope) {
        publish(envelope, envelope.auctionId(), "ESCROW_DISPUTED");
    }

    @Override
    public void publishFunded(EscrowFundedEnvelope envelope) {
        publish(envelope, envelope.auctionId(), "ESCROW_FUNDED");
    }

    @Override
    public void publishTransferConfirmed(EscrowTransferConfirmedEnvelope envelope) {
        publish(envelope, envelope.auctionId(), "ESCROW_TRANSFER_CONFIRMED");
    }

    @Override
    public void publishFrozen(EscrowFrozenEnvelope envelope) {
        publish(envelope, envelope.auctionId(), "ESCROW_FROZEN");
    }

    @Override
    public void publishCompleted(EscrowCompletedEnvelope envelope) {
        publish(envelope, envelope.auctionId(), "ESCROW_COMPLETED");
    }

    @Override
    public void publishRefundCompleted(EscrowRefundCompletedEnvelope envelope) {
        publish(envelope, envelope.auctionId(), "ESCROW_REFUND_COMPLETED");
    }

    @Override
    public void publishPayoutStalled(EscrowPayoutStalledEnvelope envelope) {
        publish(envelope, envelope.auctionId(), "ESCROW_PAYOUT_STALLED");
    }

    @Override
    public void publishExpired(EscrowExpiredEnvelope envelope) {
        publish(envelope, envelope.auctionId(), "ESCROW_EXPIRED");
    }

    void publish(EscrowEnvelope envelope, Long auctionId, String logLabel) {
        String destination = "/topic/auction/" + auctionId;
        log.info("Publishing {} to {}: escrowId={}", logLabel, destination, envelope.escrowId());
        try {
            messagingTemplate.convertAndSend(destination, envelope);
        } catch (MessagingException e) {
            log.warn("Failed to publish {} for auction {}: {}",
                    logLabel, auctionId, e.getMessage(), e);
        }
    }
}
