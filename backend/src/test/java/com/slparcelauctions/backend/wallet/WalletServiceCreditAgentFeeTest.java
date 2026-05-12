package com.slparcelauctions.backend.wallet;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.slparcelauctions.backend.escrow.command.TerminalCommandRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.wallet.broadcast.WalletBroadcastPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WalletServiceCreditAgentFeeTest {

    @Test
    void creditAgentFee_addsBalanceAndAppendsLedger() throws Exception {
        UserRepository userRepo = mock(UserRepository.class);
        UserLedgerRepository ledgerRepo = mock(UserLedgerRepository.class);
        BidReservationRepository resRepo = mock(BidReservationRepository.class);
        TerminalCommandRepository cmdRepo = mock(TerminalCommandRepository.class);
        WalletBroadcastPublisher pub = mock(WalletBroadcastPublisher.class);
        Clock clock = Clock.fixed(Instant.parse("2026-05-12T10:00:00Z"), ZoneOffset.UTC);

        WalletService svc = new WalletService(userRepo, ledgerRepo, resRepo, cmdRepo, pub, clock);

        User u = User.builder()
            .balanceLindens(0L).reservedLindens(0L)
            .username("agent").passwordHash("x").build();
        // Set id on user via reflection
        Field idField = com.slparcelauctions.backend.common.BaseEntity.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(u, 7L);
        // Set publicId for broadcast
        Field pidField = com.slparcelauctions.backend.common.BaseEntity.class.getDeclaredField("publicId");
        pidField.setAccessible(true);
        pidField.set(u, UUID.randomUUID());

        when(userRepo.findByIdForUpdate(7L)).thenReturn(Optional.of(u));
        when(ledgerRepo.save(any())).thenAnswer(i -> {
            UserLedgerEntry e = i.getArgument(0);
            Field lf = com.slparcelauctions.backend.common.BaseEntity.class.getDeclaredField("publicId");
            lf.setAccessible(true);
            lf.set(e, UUID.randomUUID());
            return e;
        });

        svc.creditAgentFee(7L, 999L, 250L);

        assertThat(u.getBalanceLindens()).isEqualTo(250L);

        ArgumentCaptor<UserLedgerEntry> cap = ArgumentCaptor.forClass(UserLedgerEntry.class);
        verify(ledgerRepo).save(cap.capture());
        UserLedgerEntry e = cap.getValue();
        assertThat(e.getUserId()).isEqualTo(7L);
        assertThat(e.getEntryType()).isEqualTo(UserLedgerEntryType.AGENT_FEE_CREDIT);
        assertThat(e.getAmount()).isEqualTo(250L);
        assertThat(e.getBalanceAfter()).isEqualTo(250L);
        assertThat(e.getRefType()).isEqualTo("AUCTION");
        assertThat(e.getRefId()).isEqualTo(999L);
    }

    @Test
    void creditAgentFee_rejectsNonPositiveAmount() {
        UserRepository userRepo = mock(UserRepository.class);
        UserLedgerRepository ledgerRepo = mock(UserLedgerRepository.class);
        BidReservationRepository resRepo = mock(BidReservationRepository.class);
        TerminalCommandRepository cmdRepo = mock(TerminalCommandRepository.class);
        WalletBroadcastPublisher pub = mock(WalletBroadcastPublisher.class);
        Clock clock = Clock.fixed(Instant.parse("2026-05-12T10:00:00Z"), ZoneOffset.UTC);

        WalletService svc = new WalletService(userRepo, ledgerRepo, resRepo, cmdRepo, pub, clock);

        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> svc.creditAgentFee(7L, 999L, 0L))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(userRepo, ledgerRepo);
    }
}
