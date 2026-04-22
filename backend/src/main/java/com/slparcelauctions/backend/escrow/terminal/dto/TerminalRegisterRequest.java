package com.slparcelauctions.backend.escrow.terminal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/sl/terminal/register}.
 *
 * <p>{@code sharedSecret} carries the {@code slpa.escrow.terminal-shared-secret}
 * value from the in-world script; the server-side check is performed by
 * {@link com.slparcelauctions.backend.escrow.terminal.TerminalService#assertSharedSecret(String)}.
 */
public record TerminalRegisterRequest(
        @NotBlank @Size(max = 100) String terminalId,
        @NotBlank @Size(max = 500)
        @Pattern(regexp = "^https?://.+")
        String httpInUrl,
        @Size(max = 100) String regionName,
        @NotBlank String sharedSecret) { }
