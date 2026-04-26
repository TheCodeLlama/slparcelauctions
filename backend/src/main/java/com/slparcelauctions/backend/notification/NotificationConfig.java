package com.slparcelauctions.backend.notification;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Notification-specific Spring beans.
 *
 * <p>Defines the {@code requiresNewTxTemplate} bean used by
 * {@link NotificationPublisherImpl} for the fan-out afterCommit batch. Each
 * per-recipient write runs in its own independent transaction so a single
 * FK violation does not abort delivery to the remaining recipients.
 */
@Configuration
public class NotificationConfig {

    @Bean
    TransactionTemplate requiresNewTxTemplate(PlatformTransactionManager txm) {
        TransactionTemplate t = new TransactionTemplate(txm);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return t;
    }
}
