package com.slparcelauctions.backend.notification.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.slparcelauctions.backend.notification.NotificationCategory;
import com.slparcelauctions.backend.notification.NotificationDao;
import com.slparcelauctions.backend.notification.NotificationGroup;
import com.slparcelauctions.backend.notification.dto.NotificationDto;
import com.slparcelauctions.backend.notification.ws.envelope.NotificationUpsertedEnvelope;
import com.slparcelauctions.backend.notification.ws.envelope.ReadStateChangedEnvelope;

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
class NotificationWsBroadcasterTest {

    @MockitoBean
    SimpMessagingTemplate template;

    @Autowired
    NotificationWsBroadcaster broadcaster;

    // ── helpers ───────────────────────────────────────────────────────────────

    private static final UUID FIXED_UUID = UUID.fromString("00000000-0000-0000-0000-000000000042");

    private NotificationDto makeDto() {
        return new NotificationDto(FIXED_UUID, NotificationCategory.OUTBID,
                NotificationGroup.BIDDING, "title", "body", Map.of(),
                false, OffsetDateTime.now(), OffsetDateTime.now());
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    void broadcastUpsert_routesToUserQueueNotifications() {
        var dto = new NotificationDto(FIXED_UUID, NotificationCategory.OUTBID,
                NotificationGroup.BIDDING, "title", "body", Map.of(),
                false, OffsetDateTime.now(), OffsetDateTime.now());
        var result = new NotificationDao.UpsertResult(FIXED_UUID, false, OffsetDateTime.now(), OffsetDateTime.now());

        broadcaster.broadcastUpsert(7L, result, dto);

        ArgumentCaptor<Object> envelopeCap = ArgumentCaptor.forClass(Object.class);
        verify(template).convertAndSendToUser(eq("7"), eq("/queue/notifications"), envelopeCap.capture());
        var env = (NotificationUpsertedEnvelope) envelopeCap.getValue();
        assertThat(env.type()).isEqualTo("NOTIFICATION_UPSERTED");
        assertThat(env.isUpdate()).isFalse();
        assertThat(env.notification().publicId()).isEqualTo(FIXED_UUID);
    }

    @Test
    void broadcastUpsert_carriesIsUpdateFlag() {
        var dto = makeDto();
        var result = new NotificationDao.UpsertResult(FIXED_UUID, true, OffsetDateTime.now(), OffsetDateTime.now());

        broadcaster.broadcastUpsert(7L, result, dto);

        ArgumentCaptor<Object> cap = ArgumentCaptor.forClass(Object.class);
        verify(template).convertAndSendToUser(any(), any(), cap.capture());
        assertThat(((NotificationUpsertedEnvelope) cap.getValue()).isUpdate()).isTrue();
    }

    @Test
    void broadcastReadStateChanged_sendsInvalidationEnvelope() {
        broadcaster.broadcastReadStateChanged(7L);

        ArgumentCaptor<Object> cap = ArgumentCaptor.forClass(Object.class);
        verify(template).convertAndSendToUser(eq("7"), eq("/queue/notifications"), cap.capture());
        assertThat(cap.getValue()).isInstanceOf(ReadStateChangedEnvelope.class);
        assertThat(((ReadStateChangedEnvelope) cap.getValue()).type()).isEqualTo("READ_STATE_CHANGED");
    }

    @Test
    void broadcastUpsert_swallowsExceptions() {
        doThrow(new RuntimeException("broker down")).when(template)
                .convertAndSendToUser(any(), any(), any());
        var dto = makeDto();
        var result = new NotificationDao.UpsertResult(FIXED_UUID, false, OffsetDateTime.now(), OffsetDateTime.now());

        // Should NOT throw — broadcaster swallows + logs.
        assertThatCode(() -> broadcaster.broadcastUpsert(7L, result, dto)).doesNotThrowAnyException();
    }
}
