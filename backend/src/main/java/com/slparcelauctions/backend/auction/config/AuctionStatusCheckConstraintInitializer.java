package com.slparcelauctions.backend.auction.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.common.EnumCheckConstraintSync;

import lombok.RequiredArgsConstructor;

/**
 * Refreshes the {@code auctions_status_check} constraint on startup so new
 * values added to {@link AuctionStatus} do not require manual DDL edits.
 * See {@link EnumCheckConstraintSync} for the rationale.
 */
@Component
@RequiredArgsConstructor
public class AuctionStatusCheckConstraintInitializer {

    private final JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class)
    public void refresh() {
        new EnumCheckConstraintSync(jdbc).sync("auctions", "status", AuctionStatus.class);
    }
}
