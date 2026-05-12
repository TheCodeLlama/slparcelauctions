package com.slparcelauctions.backend.realty.slgroup.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * LSL founder-terminal callback payload (spec §5.1, §7.3). The terminal owner-says the
 * verification code typed in-world and the avatar UUID of the SL resident currently on
 * the terminal; backend cross-checks {@code founderAvatarUuid} against the SL group's
 * founder via the World API before flipping the registration to verified.
 */
public record SlGroupVerifyRequest(
        @NotBlank String verificationCode,
        @NotNull UUID founderAvatarUuid
) {}
