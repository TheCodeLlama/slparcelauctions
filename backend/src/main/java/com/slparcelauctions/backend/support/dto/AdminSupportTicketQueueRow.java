package com.slparcelauctions.backend.support.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.support.SupportTicketAuthorRole;
import com.slparcelauctions.backend.support.SupportTicketCategory;
import com.slparcelauctions.backend.support.SupportTicketStatus;

public record AdminSupportTicketQueueRow(
        UUID publicId, String subject, SupportTicketCategory category,
        SupportTicketStatus status, UUID submitterPublicId, String submitterDisplayName,
        UUID assignedAdminPublicId, String assignedAdminDisplayName,
        SupportTicketAuthorRole lastMessageAuthor, OffsetDateTime lastMessageAt) {}
