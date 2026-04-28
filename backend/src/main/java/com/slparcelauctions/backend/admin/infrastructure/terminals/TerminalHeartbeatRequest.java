package com.slparcelauctions.backend.admin.infrastructure.terminals;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record TerminalHeartbeatRequest(
        @NotBlank String terminalKey,
        @NotNull @PositiveOrZero Long accountBalance) {
}
