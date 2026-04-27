package com.slparcelauctions.backend.notification.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.slparcelauctions.backend.notification.ws.envelope.PenaltyClearedEnvelope;

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
        "slpa.notifications.sl-im.cleanup.enabled=false"
})
class AccountStateBroadcasterTest {

    @MockitoBean
    SimpMessagingTemplate template;

    @Autowired
    AccountStateBroadcaster broadcaster;

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    void penaltyCleared_routesToUserQueueAccount() {
        broadcaster.broadcastPenaltyCleared(7L);

        ArgumentCaptor<Object> cap = ArgumentCaptor.forClass(Object.class);
        verify(template).convertAndSendToUser(eq("7"), eq("/queue/account"), cap.capture());
        assertThat(cap.getValue()).isInstanceOf(PenaltyClearedEnvelope.class);
        assertThat(((PenaltyClearedEnvelope) cap.getValue()).type()).isEqualTo("PENALTY_CLEARED");
    }

    @Test
    void penaltyCleared_swallowsExceptions() {
        doThrow(new RuntimeException("broker down")).when(template)
                .convertAndSendToUser(any(), any(), any());

        assertThatCode(() -> broadcaster.broadcastPenaltyCleared(7L)).doesNotThrowAnyException();
    }
}
