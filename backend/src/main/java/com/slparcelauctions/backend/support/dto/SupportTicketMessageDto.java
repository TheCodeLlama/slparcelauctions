package com.slparcelauctions.backend.support.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.slparcelauctions.backend.support.SupportTicketAuthorRole;

public record SupportTicketMessageDto(
        UUID publicId, UUID authorPublicId, String authorDisplayName,
        SupportTicketAuthorRole authorRole, String body, boolean visibleToUser,
        OffsetDateTime createdAt, List<SupportTicketAttachmentDto> attachments) {}
