package com.slparcelauctions.backend.realty.service;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.realty.InvitationStatus;
import com.slparcelauctions.backend.realty.RealtyGroupInvitation;
import com.slparcelauctions.backend.realty.RealtyGroupInvitationRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Scheduled job that flips {@code PENDING} {@link RealtyGroupInvitation} rows to
 * {@link InvitationStatus#EXPIRED} once {@code expires_at} is in the past.
 *
 * <p>Runs on a 1-minute fixed delay per spec §8.1. Each expired row triggers a
 * {@link NotificationPublisher#realtyGroupInvitationExpired} call so the leader
 * and {@code INVITE_AGENTS} delegates learn the invite went stale.
 *
 * <p>Gated by {@code slpa.realty.invitation-expiry.enabled} (default {@code true});
 * integration tests flip this to {@code false} to avoid races against test seed data,
 * consistent with the codebase-wide scheduler-disable pattern.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "slpa.realty.invitation-expiry.enabled", havingValue = "true", matchIfMissing = true)
public class RealtyGroupExpiryJob {

    private final RealtyGroupInvitationRepository invitations;
    private final NotificationPublisher notifications;

    @Scheduled(fixedDelayString = "PT1M")
    @Transactional
    public void expirePendingInvitations() {
        List<RealtyGroupInvitation> overdue = invitations.findOverdueInvitations();
        if (overdue.isEmpty()) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        for (RealtyGroupInvitation inv : overdue) {
            inv.setStatus(InvitationStatus.EXPIRED);
            inv.setRespondedAt(now);
            invitations.save(inv);
            notifications.realtyGroupInvitationExpired(inv);
        }
        log.info("Realty group invitation expiry: marked {} invitations EXPIRED", overdue.size());
    }
}
