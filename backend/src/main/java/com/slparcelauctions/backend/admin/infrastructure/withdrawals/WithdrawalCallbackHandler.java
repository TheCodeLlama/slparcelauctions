package com.slparcelauctions.backend.admin.infrastructure.withdrawals;

import com.slparcelauctions.backend.notification.NotificationPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class WithdrawalCallbackHandler {

    private final WithdrawalRepository withdrawalRepo;
    private final NotificationPublisher publisher;
    private final Clock clock;

    @Transactional
    public void onSuccess(Long terminalCommandId) {
        Withdrawal w = withdrawalRepo.findByTerminalCommandId(terminalCommandId)
                .orElse(null);
        if (w == null) return;
        w.setStatus(WithdrawalStatus.COMPLETED);
        w.setCompletedAt(OffsetDateTime.now(clock));
        withdrawalRepo.save(w);
        publisher.withdrawalCompleted(w.getAdminUserId(), w.getAmount(), w.getRecipientUuid());
        log.info("Withdrawal {} COMPLETED", w.getId());
    }

    @Transactional
    public void onFailure(Long terminalCommandId, String reason) {
        Withdrawal w = withdrawalRepo.findByTerminalCommandId(terminalCommandId)
                .orElse(null);
        if (w == null) return;
        w.setStatus(WithdrawalStatus.FAILED);
        w.setFailureReason(reason);
        w.setCompletedAt(OffsetDateTime.now(clock));
        withdrawalRepo.save(w);
        publisher.withdrawalFailed(w.getAdminUserId(), w.getAmount(),
                w.getRecipientUuid(), reason);
        log.info("Withdrawal {} FAILED: {}", w.getId(), reason);
    }
}
