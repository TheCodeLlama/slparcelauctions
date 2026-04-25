package com.slparcelauctions.backend.escrow;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.common.EnumCheckConstraintSync;

import lombok.RequiredArgsConstructor;

/**
 * Refreshes the {@code escrow_transactions_type_check} constraint on
 * startup so new values added to {@link EscrowTransactionType} do not
 * require a manual DDL edit. Added when Epic 08 sub-spec 2 introduced
 * {@link EscrowTransactionType#LISTING_PENALTY_PAYMENT} — without this
 * initializer, the original CHECK constraint (created by Hibernate at
 * first boot before {@code LISTING_PENALTY_PAYMENT} existed) rejects
 * the new value with {@code violates check constraint
 * "escrow_transactions_type_check"}. See {@link EnumCheckConstraintSync}
 * and FOOTGUNS §F.51 for the rationale.
 */
@Component
@RequiredArgsConstructor
public class EscrowTransactionTypeCheckConstraintInitializer {

    private final JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class)
    public void refresh() {
        new EnumCheckConstraintSync(jdbc)
                .sync("escrow_transactions", "type", EscrowTransactionType.class);
    }
}
