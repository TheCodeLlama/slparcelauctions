package com.slparcelauctions.backend.support;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Spring Data repository for {@link SupportTicket}.
 *
 * <p>{@link #findByPublicId(UUID)} carries an {@code @EntityGraph} that
 * eagerly fetches the thread's messages and their attachments so any
 * mapper that runs outside an open Hibernate session (e.g. a
 * non-{@code @Transactional} controller test) can still read the
 * collections without tripping {@code LazyInitializationException}.
 *
 * <p>{@link #countByUserIdAndCreatedAtAfter(long, OffsetDateTime)} powers
 * the per-user 5/hour rate limiter on new-ticket creation. Replies are
 * NOT counted toward this cap.
 */
public interface SupportTicketRepository
        extends JpaRepository<SupportTicket, Long>, JpaSpecificationExecutor<SupportTicket> {

    @EntityGraph(attributePaths = {"messages", "messages.attachments"})
    Optional<SupportTicket> findByPublicId(UUID publicId);

    long countByUserIdAndCreatedAtAfter(long userId, OffsetDateTime threshold);
}
