package com.slparcelauctions.backend.verification.dto;

import java.time.OffsetDateTime;

public record GenerateCodeResponse(String code, OffsetDateTime expiresAt) {}
