package com.slparcelauctions.backend.wallet.broadcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.wallet.WalletService;

@SpringBootTest
@ActiveProfiles("dev")
class WalletBroadcastIntegrationTest {

    @Autowired WalletService walletService;
    @Autowired UserRepository userRepo;
    @MockitoBean SimpMessagingTemplate messagingTemplate;

    @Test
    void deposit_publishesAfterCommit() {
        User u = userRepo.save(User.builder()
                .email("test+wallet-broadcast-" + UUID.randomUUID() + "@example.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .verified(true)
                .balanceLindens(0L)
                .reservedLindens(0L)
                .build());

        AtomicReference<WalletBalanceChangedEnvelope> captured = new AtomicReference<>();
        doAnswer(inv -> {
            captured.set(inv.getArgument(2, WalletBalanceChangedEnvelope.class));
            return null;
        }).when(messagingTemplate).convertAndSendToUser(
                eq(u.getId().toString()), eq("/queue/wallet"),
                any(WalletBalanceChangedEnvelope.class));

        walletService.deposit(u.getSlAvatarUuid(), 500L, UUID.randomUUID().toString());

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().balance()).isEqualTo(500L);
        assertThat(captured.get().reason()).isEqualTo("DEPOSIT");
        assertThat(captured.get().ledgerEntryId()).isNotNull();
    }
}
