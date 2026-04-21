package com.slparcelauctions.backend.auction.broadcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import com.slparcelauctions.backend.auction.AuctionEndOutcome;
import com.slparcelauctions.backend.auction.BidType;
import com.slparcelauctions.backend.auction.dto.BidHistoryEntry;

/**
 * Unit coverage for {@link StompAuctionBroadcastPublisher} — verifies the
 * destination pattern and that the envelope flows through unchanged. The
 * real STOMP broker is not exercised here; that lives in
 * {@code BidWebSocketIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class StompAuctionBroadcastPublisherTest {

    @Mock
    SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    StompAuctionBroadcastPublisher publisher;

    @Test
    void publishSettlement_sendsEnvelopeToAuctionTopic() {
        OffsetDateTime now = OffsetDateTime.parse("2026-04-20T12:00:00Z");
        BidHistoryEntry entry = new BidHistoryEntry(
                10L, 20L, "Alice", 500L, BidType.MANUAL, null, null, now);
        BidSettlementEnvelope envelope = new BidSettlementEnvelope(
                "BID_SETTLEMENT",
                42L,
                now,
                500L,
                20L,
                "Alice",
                1,
                now.plusDays(1),
                now.plusDays(1),
                List.of(entry));

        publisher.publishSettlement(envelope);

        ArgumentCaptor<String> destCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payloadCap = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(destCap.capture(), payloadCap.capture());
        assertThat(destCap.getValue()).isEqualTo("/topic/auction/42");
        assertThat(payloadCap.getValue()).isSameAs(envelope);
        verifyNoMoreInteractions(messagingTemplate);
    }

    @Test
    void publishEnded_sendsEnvelopeToAuctionTopic() {
        OffsetDateTime now = OffsetDateTime.parse("2026-04-20T12:00:00Z");
        AuctionEndedEnvelope envelope = new AuctionEndedEnvelope(
                "AUCTION_ENDED",
                77L,
                now,
                now,
                AuctionEndOutcome.SOLD,
                1_500L,
                33L,
                "Bob",
                4);

        publisher.publishEnded(envelope);

        verify(messagingTemplate).convertAndSend(eq("/topic/auction/77"), (Object) eq(envelope));
        verifyNoMoreInteractions(messagingTemplate);
    }

    @Test
    void publishSettlement_usesAuctionIdFromEnvelope() {
        // Topic must be derived from envelope.auctionId(), never a captured
        // field — guards against a future refactor that introduces a second
        // id field (e.g. parcelId) and accidentally routes the publish to it.
        BidSettlementEnvelope envelope = new BidSettlementEnvelope(
                "BID_SETTLEMENT", 9_001L,
                OffsetDateTime.parse("2026-04-20T12:00:00Z"),
                100L, 1L, "Carol", 1,
                OffsetDateTime.parse("2026-04-21T12:00:00Z"),
                OffsetDateTime.parse("2026-04-21T12:00:00Z"),
                List.of());

        publisher.publishSettlement(envelope);

        verify(messagingTemplate).convertAndSend(eq("/topic/auction/9001"), (Object) eq(envelope));
    }
}
