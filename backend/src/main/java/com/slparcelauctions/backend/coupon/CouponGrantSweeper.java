package com.slparcelauctions.backend.coupon;

import java.time.OffsetDateTime;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Hourly job that transitions {@link CouponGrantState#ACTIVE} grants whose
 * {@code expiresAt} is in the past to {@link CouponGrantState#EXPIRED}.
 *
 * <p>The resolver ({@link CouponDiscountResolver}) already filters expired
 * grants from its candidate set defensively, so sweeper lag never produces
 * an incorrect discount snapshot. The persisted state still matters for
 * admin views, audit history, and the wallet-history surface.
 *
 * <p>Idempotent: re-running mutates zero additional rows once every
 * eligible grant has been flipped.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CouponGrantSweeper {

    private final CouponGrantRepository repository;

    @Scheduled(cron = "${slpa.coupons.sweeper-cron:0 0 * * * *}", zone = "UTC")
    @Transactional
    public void sweep() {
        int count = repository.markExpired(OffsetDateTime.now());
        if (count > 0) {
            log.info("CouponGrantSweeper transitioned {} grants to EXPIRED", count);
        }
    }
}
