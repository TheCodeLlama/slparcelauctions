package com.slparcelauctions.backend.support.dto;

import java.util.UUID;

public record SupportTicketAttachmentDto(
        UUID publicId, String mimeType, int sizeBytes, Integer width, Integer height) {}
