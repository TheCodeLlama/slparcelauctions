package com.slparcelauctions.backend.wallet.broadcast;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.wallet.UserLedgerRepository;

class WalletBroadcastPublisherTest {

    @Test
    void publishWithoutActiveTx_sendsImmediately() {
        SimpMessagingTemplate tpl = mock(SimpMessagingTemplate.class);
        UserLedgerRepository ledgerRepo = mock(UserLedgerRepository.class);
        Mockito.when(ledgerRepo.sumPendingWithdrawals(42L)).thenReturn(0L);
        Clock clock = Clock.fixed(Instant.parse("2026-05-01T12:00:00Z"), ZoneOffset.UTC);
        WalletBroadcastPublisher pub = new WalletBroadcastPublisher(tpl, ledgerRepo, clock);

        User u = User.builder().username("u-" + java.util.UUID.randomUUID().toString().substring(0, 8)).id(42L)
                .balanceLindens(100L).reservedLindens(20L)
                .penaltyBalanceOwed(0L).build();

        pub.publish(u, "DEPOSIT", UUID.randomUUID());

        verify(tpl).convertAndSendToUser(eq("42"), eq("/queue/wallet"),
                any(WalletBalanceChangedEnvelope.class));
    }
}
