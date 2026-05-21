package com.slparcelauctions.backend.support.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.slparcelauctions.backend.support.SupportTicketCategory;

public record CreateSupportTicketRequest(
        @NotBlank @Size(max = 160) String subject,
        @NotNull SupportTicketCategory category,
        @NotBlank @Size(max = 10000) String body,
        List<String> attachmentKeys) {}
