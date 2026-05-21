package com.slparcelauctions.backend.support;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link SupportTicketMessage}. Messages are
 * normally accessed through the parent {@link SupportTicket}'s
 * {@code messages} collection; this repository exists for direct CRUD
 * by services that need to persist a single message row without
 * mutating the parent (e.g. cascade-only paths).
 */
public interface SupportTicketMessageRepository
        extends JpaRepository<SupportTicketMessage, Long> {
}
