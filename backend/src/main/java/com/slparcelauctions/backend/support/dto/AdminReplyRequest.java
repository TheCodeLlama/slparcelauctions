package com.slparcelauctions.backend.support.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminReplyRequest(
        @NotBlank @Size(max = 10000) String body,
        List<String> attachmentKeys,
        Boolean internalNote) {}
