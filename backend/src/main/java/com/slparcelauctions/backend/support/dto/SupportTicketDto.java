package com.slparcelauctions.backend.support.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.slparcelauctions.backend.support.SupportTicketAuthorRole;
import com.slparcelauctions.backend.support.SupportTicketCategory;
import com.slparcelauctions.backend.support.SupportTicketStatus;

public record SupportTicketDto(
        UUID publicId, UUID submitterPublicId, String submitterDisplayName,
        String subject, SupportTicketCategory category, SupportTicketStatus status,
        UUID assignedAdminPublicId, String assignedAdminDisplayName,
        OffsetDateTime lastMessageAt, SupportTicketAuthorRole lastMessageAuthor,
        OffsetDateTime resolvedAt, OffsetDateTime createdAt, OffsetDateTime updatedAt,
        List<SupportTicketMessageDto> messages) {}
