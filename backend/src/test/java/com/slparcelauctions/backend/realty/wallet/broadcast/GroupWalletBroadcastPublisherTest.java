package com.slparcelauctions.backend.realty.wallet.broadcast;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import static org.mockito.Mockito.*;

class GroupWalletBroadcastPublisherTest {

    @Test
    void publishesEnvelopeOnGroupTopic() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        GroupWalletBroadcastPublisher pub = new GroupWalletBroadcastPublisher(template);
        UUID groupPublicId = UUID.randomUUID();
        UUID entryPublicId = UUID.randomUUID();

        pub.publish(groupPublicId, 1000L, 0L, 1000L,
            "LISTING_FEE_DEBIT", entryPublicId);

        verify(template).convertAndSend(
            eq("/topic/realty/groups/" + groupPublicId),
            any(GroupWalletBalanceChangedEnvelope.class));
    }
}
