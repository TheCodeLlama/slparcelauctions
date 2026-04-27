package com.slparcelauctions.backend.admin.ban.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LiftBanRequest(@NotBlank @Size(max = 1000) String liftedReason) {}
