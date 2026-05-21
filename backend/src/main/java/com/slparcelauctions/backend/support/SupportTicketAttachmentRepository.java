package com.slparcelauctions.backend.support;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link SupportTicketAttachment}.
 *
 * <p>{@link #findByPublicId(UUID)} powers the signed-URL fetch endpoint
 * {@code GET /api/v1/support-tickets/attachments/{publicId}}.
 */
public interface SupportTicketAttachmentRepository
        extends JpaRepository<SupportTicketAttachment, Long> {

    Optional<SupportTicketAttachment> findByPublicId(UUID publicId);
}
