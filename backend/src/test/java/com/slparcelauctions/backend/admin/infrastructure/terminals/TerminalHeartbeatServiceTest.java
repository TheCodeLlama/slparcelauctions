package com.slparcelauctions.backend.admin.infrastructure.terminals;

import com.slparcelauctions.backend.escrow.terminal.Terminal;
import com.slparcelauctions.backend.escrow.terminal.TerminalRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "auth.cleanup.enabled=false",
    "slpa.auction-end.enabled=false",
    "slpa.ownership-monitor.enabled=false",
    "slpa.escrow.ownership-monitor-job.enabled=false",
    "slpa.escrow.timeout-job.enabled=false",
    "slpa.escrow.command-dispatcher-job.enabled=false",
    "slpa.review.scheduler.enabled=false",
    "slpa.notifications.cleanup.enabled=false",
    "slpa.notifications.sl-im.cleanup.enabled=false",
    "slpa.bot-pool-health-log.enabled=false"
})
@Transactional
class TerminalHeartbeatServiceTest {

    @Autowired
    TerminalHeartbeatService service;

    @Autowired
    TerminalRepository repo;

    @Test
    void heartbeatUpdatesBalanceAndTimestamp() {
        Terminal t = Terminal.builder()
                .terminalId("term-A")
                .httpInUrl("http://example.com/in")
                .active(true)
                .lastSeenAt(OffsetDateTime.now())
                .build();
        repo.save(t);

        service.handle(new TerminalHeartbeatRequest("term-A", 14_231L));

        Terminal updated = repo.findById("term-A").orElseThrow();
        assertThat(updated.getLastReportedBalance()).isEqualTo(14_231L);
        assertThat(updated.getLastHeartbeatAt()).isNotNull();
    }

    @Test
    void unknownTerminalThrows() {
        assertThatThrownBy(() -> service.handle(new TerminalHeartbeatRequest("missing", 0L)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
