package com.slparcelauctions.backend.sl.dto;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for the dev-profile simulate helper. Everything except
 * {@code verificationCode} is optional - {@link #toSlVerifyRequest()} fills
 * sensible defaults so the frontend dev harness can POST just the code.
 */
public record DevSimulateRequest(
        @NotBlank @Pattern(regexp = "^[0-9]{6}$") String verificationCode,
        UUID avatarUuid,
        String avatarName,
        String displayName,
        String username,
        LocalDate bornDate,
        Integer payInfo
) {
    public SlVerifyRequest toSlVerifyRequest() {
        return new SlVerifyRequest(
                verificationCode,
                avatarUuid != null ? avatarUuid : UUID.randomUUID(),
                avatarName != null ? avatarName : "Dev Tester",
                displayName != null ? displayName : "Dev Tester",
                username != null ? username : "dev.tester",
                bornDate != null ? bornDate : LocalDate.of(2012, 1, 1),
                payInfo != null ? payInfo : 3);
    }
}
