package com.slparcelauctions.backend.admin.infrastructure.terminals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
class TerminalSecretServiceTest {

    @Autowired TerminalSecretService service;
    @Autowired TerminalSecretRepository repo;

    @Test
    void firstRotateInsertsV1() {
        TerminalSecret v1 = service.rotate();
        assertThat(v1.getSecretVersion()).isEqualTo(1);
        assertThat(v1.getRetiredAt()).isNull();
    }

    @Test
    void secondRotateInsertsV2KeepsV1Active() {
        service.rotate();
        TerminalSecret v2 = service.rotate();
        assertThat(v2.getSecretVersion()).isEqualTo(2);
        assertThat(repo.findByRetiredAtIsNullOrderBySecretVersionDesc()).hasSize(2);
    }

    @Test
    void thirdRotateRetiresV1() {
        service.rotate();
        service.rotate();
        service.rotate();
        List<TerminalSecret> active = repo.findByRetiredAtIsNullOrderBySecretVersionDesc();
        assertThat(active).hasSize(2);
        assertThat(active.get(0).getSecretVersion()).isEqualTo(3);
        assertThat(active.get(1).getSecretVersion()).isEqualTo(2);
    }

    @Test
    void acceptMatchesCurrentAndPrevious() {
        TerminalSecret v1 = service.rotate();
        TerminalSecret v2 = service.rotate();
        assertThat(service.accept(v1.getSecretValue())).isTrue();
        assertThat(service.accept(v2.getSecretValue())).isTrue();
        assertThat(service.accept("wrong")).isFalse();
    }

    @Test
    void acceptRejectsRetiredVersion() {
        TerminalSecret v1 = service.rotate();
        service.rotate();
        service.rotate();
        assertThat(service.accept(v1.getSecretValue())).isFalse();
    }

    @Test
    void currentReturnsHighestActive() {
        service.rotate();
        service.rotate();
        TerminalSecret cur = service.current().orElseThrow();
        assertThat(cur.getSecretVersion()).isEqualTo(2);
    }
}
