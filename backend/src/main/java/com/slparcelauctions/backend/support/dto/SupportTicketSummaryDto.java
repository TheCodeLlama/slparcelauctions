package com.slparcelauctions.backend.support.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.support.SupportTicketAuthorRole;
import com.slparcelauctions.backend.support.SupportTicketCategory;
import com.slparcelauctions.backend.support.SupportTicketStatus;

public record SupportTicketSummaryDto(
        UUID publicId, String subject, SupportTicketCategory category,
        SupportTicketStatus status, SupportTicketAuthorRole lastMessageAuthor,
        OffsetDateTime lastMessageAt) {}
