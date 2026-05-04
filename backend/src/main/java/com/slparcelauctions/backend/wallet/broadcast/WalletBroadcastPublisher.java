package com.slparcelauctions.backend.wallet.broadcast;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.wallet.UserLedgerRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class WalletBroadcastPublisher {

    private final SimpMessagingTemplate messagingTemplate;
    private final UserLedgerRepository ledgerRepository;
    private final Clock clock;

    public void publish(User user, String reason, UUID ledgerEntryPublicId) {
        long queuedForWithdrawal = ledgerRepository.sumPendingWithdrawals(user.getId());
        WalletBalanceChangedEnvelope envelope = WalletBalanceChangedEnvelope.of(
                user, reason, ledgerEntryPublicId, queuedForWithdrawal, OffsetDateTime.now(clock));
        Long userId = user.getId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            doPublish(userId, envelope);
                        }
                    });
        } else {
            doPublish(userId, envelope);
        }
    }

    private void doPublish(Long userId, WalletBalanceChangedEnvelope envelope) {
        messagingTemplate.convertAndSendToUser(
                userId.toString(), "/queue/wallet", envelope);
        log.debug("WALLET_BALANCE_CHANGED published: userId={}, reason={}, ledgerEntryPublicId={}",
                userId, envelope.reason(), envelope.ledgerEntryPublicId());
    }
}
