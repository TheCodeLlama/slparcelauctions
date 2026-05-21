package com.slparcelauctions.backend.support;

import java.time.OffsetDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Per-user rate limiter on new support-ticket creation.
 *
 * <p>Counts only NEW tickets opened by a given user in the trailing
 * 60 minutes. Replies to existing open tickets do NOT count toward
 * the cap - a user stuck in a back-and-forth thread can keep
 * responding indefinitely without tripping this guard.
 *
 * <p>The cap is configurable via
 * {@code slpa.support.rate-limit.tickets-per-hour} and defaults to 5.
 */
@Component
@RequiredArgsConstructor
public class SupportTicketRateLimiter {

    private final SupportTicketRepository ticketRepository;

    @Value("${slpa.support.rate-limit.tickets-per-hour:5}")
    private int ticketsPerHour;

    public void assertCanOpenNewTicket(long userId) {
        OffsetDateTime cutoff = OffsetDateTime.now().minusHours(1);
        long created = ticketRepository.countByUserIdAndCreatedAtAfter(userId, cutoff);
        if (created >= ticketsPerHour) {
            throw new SupportTicketException(
                    SupportTicketError.RATE_LIMITED,
                    "Too many new tickets - wait an hour or reply to an existing open ticket");
        }
    }
}
