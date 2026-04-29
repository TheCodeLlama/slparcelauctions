package com.slparcelauctions.backend.admin.infrastructure.terminals;

import java.util.List;

public record TerminalRotationResponse(
        int newVersion,
        String secretValue,
        List<TerminalPushResult> terminalPushResults) {
}
