package com.slparcelauctions.backend.escrow.terminal;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class TerminalHeartbeatColumnsTest {

    @Test
    void terminalHeartbeatColumnsDefaultNull() {
        Terminal t = new Terminal();
        assertThat(t.getLastHeartbeatAt()).isNull();
        assertThat(t.getLastReportedBalance()).isNull();
    }

    @Test
    void terminalHeartbeatColumnsRoundtrip() {
        Terminal t = new Terminal();
        t.setLastHeartbeatAt(OffsetDateTime.parse("2026-04-27T03:00:00Z"));
        t.setLastReportedBalance(15_000L);
        assertThat(t.getLastHeartbeatAt()).isEqualTo("2026-04-27T03:00:00Z");
        assertThat(t.getLastReportedBalance()).isEqualTo(15_000L);
    }
}
