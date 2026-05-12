package com.slparcelauctions.backend.realty.wallet.broadcast;

import java.util.UUID;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Publishes {@link GroupWalletBalanceChangedEnvelope} to the group's STOMP topic whenever
 * a wallet balance changes. Spec §11.3.
 *
 * <p>Subscribers: any client with {@code VIEW_GROUP_TRANSACTIONS} that has subscribed to
 * {@code /topic/realty/groups/{publicId}}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GroupWalletBroadcastPublisher {

    private final SimpMessagingTemplate template;

    /**
     * Publishes a {@code GROUP_WALLET_BALANCE_CHANGED} envelope.
     *
     * @param groupPublicId    the group's public UUID (used in the topic path)
     * @param balance          current balance_lindens after the write
     * @param reserved         current reserved_lindens after the write
     * @param available        balance - reserved
     * @param latestEntryType  the entry type of the ledger row that triggered this update
     * @param latestEntryPublicId the public UUID of that ledger row
     */
    public void publish(UUID groupPublicId, long balance, long reserved, long available,
            String latestEntryType, UUID latestEntryPublicId) {
        GroupWalletBalanceChangedEnvelope env = GroupWalletBalanceChangedEnvelope.of(
            groupPublicId, balance, reserved, available, latestEntryType, latestEntryPublicId);
        template.convertAndSend("/topic/realty/groups/" + groupPublicId, env);
        log.debug("GROUP_WALLET_BALANCE_CHANGED sent to /topic/realty/groups/{}: balance={}, reserved={}",
            groupPublicId, balance, reserved);
    }
}
