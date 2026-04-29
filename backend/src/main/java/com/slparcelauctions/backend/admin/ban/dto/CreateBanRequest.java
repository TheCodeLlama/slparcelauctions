package com.slparcelauctions.backend.admin.ban.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.admin.ban.BanReasonCategory;
import com.slparcelauctions.backend.admin.ban.BanType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateBanRequest(
    @NotNull BanType banType,
    String ipAddress,
    UUID slAvatarUuid,
    OffsetDateTime expiresAt,
    @NotNull BanReasonCategory reasonCategory,
    @NotBlank @Size(max = 1000) String reasonText
) {}
