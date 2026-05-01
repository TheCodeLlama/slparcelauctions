package com.slparcelauctions.backend.wallet.broadcast;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import com.slparcelauctions.backend.user.User;

class WalletBroadcastPublisherTest {

    @Test
    void publishWithoutActiveTx_sendsImmediately() {
        SimpMessagingTemplate tpl = mock(SimpMessagingTemplate.class);
        Clock clock = Clock.fixed(Instant.parse("2026-05-01T12:00:00Z"), ZoneOffset.UTC);
        WalletBroadcastPublisher pub = new WalletBroadcastPublisher(tpl, clock);

        User u = User.builder().id(42L)
                .balanceLindens(100L).reservedLindens(20L)
                .penaltyBalanceOwed(0L).build();

        pub.publish(u, "DEPOSIT", 7L);

        verify(tpl).convertAndSendToUser(eq("42"), eq("/queue/wallet"),
                any(WalletBalanceChangedEnvelope.class));
    }
}
