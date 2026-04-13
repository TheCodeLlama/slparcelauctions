package com.slparcelauctions.backend.verification.dto;

import java.time.OffsetDateTime;

public record ActiveCodeResponse(String code, OffsetDateTime expiresAt) {}
