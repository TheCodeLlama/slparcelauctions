package com.slparcelauctions.backend.wstest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WsTestBroadcastRequest(
    @NotBlank @Size(max = 500) String message
) {}
