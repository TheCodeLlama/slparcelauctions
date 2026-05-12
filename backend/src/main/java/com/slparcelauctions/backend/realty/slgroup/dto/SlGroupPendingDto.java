package com.slparcelauctions.backend.realty.slgroup.dto;

import java.time.OffsetDateTime;

public record SlGroupPendingDto(
        String verificationCode,
        OffsetDateTime verificationCodeExpiresAt,
        OffsetDateTime lastPolledAt,
        int pollAttempts
) {}
