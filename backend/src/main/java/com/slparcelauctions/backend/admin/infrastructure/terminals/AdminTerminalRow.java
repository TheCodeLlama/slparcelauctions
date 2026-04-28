package com.slparcelauctions.backend.admin.infrastructure.terminals;

import java.time.OffsetDateTime;

public record AdminTerminalRow(
        String terminalId,
        String regionName,
        String httpInUrl,
        OffsetDateTime lastSeenAt,
        OffsetDateTime lastHeartbeatAt,
        Long lastReportedBalance,
        Integer currentSecretVersion) {
}
