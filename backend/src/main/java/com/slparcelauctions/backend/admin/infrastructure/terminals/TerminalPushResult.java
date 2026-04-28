package com.slparcelauctions.backend.admin.infrastructure.terminals;

public record TerminalPushResult(
        String terminalId,
        String terminalName,
        boolean success,
        String errorMessage) {
}
