package com.slparcelauctions.backend.sl.dto;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for {@code POST /api/v1/sl/verify}. Posted by an in-world LSL
 * verification terminal once the player enters their 6-digit code on the device.
 * The avatar metadata fields are sourced from {@code llRequestAgentData} and
 * {@code llGetObjectDetails} on the SL side.
 *
 * <p>{@code payInfo} is the LSL {@code DATA_PAYINFO} integer (0 = no info,
 * 1 = used, 2 = used+verified, 3 = payment info on file + used).
 */
public record SlVerifyRequest(
        @NotBlank @Pattern(regexp = "^[0-9]{6}$") String verificationCode,
        @NotNull UUID avatarUuid,
        @NotBlank String avatarName,
        @NotBlank String displayName,
        @NotBlank String username,
        @NotNull LocalDate bornDate,
        @NotNull @Min(0) @Max(3) Integer payInfo
) {}
