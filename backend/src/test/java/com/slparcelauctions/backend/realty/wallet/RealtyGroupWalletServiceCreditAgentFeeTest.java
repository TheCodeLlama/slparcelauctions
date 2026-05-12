package com.slparcelauctions.backend.realty.wallet;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.wallet.broadcast.GroupWalletBroadcastPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RealtyGroupWalletServiceCreditAgentFeeTest {

    private final RealtyGroupRepository groupRepo = mock(RealtyGroupRepository.class);
    private final RealtyGroupLedgerRepository ledgerRepo = mock(RealtyGroupLedgerRepository.class);
    private final GroupWalletBroadcastPublisher publisher = mock(GroupWalletBroadcastPublisher.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-12T10:00:00Z"), ZoneOffset.UTC);

    private final RealtyGroupWalletService service = new RealtyGroupWalletService(
        groupRepo, ledgerRepo, null, null, null, publisher, null, clock);

    @Test
    void creditAgentFee_addsBalanceAppendsLedgerAndBroadcasts() throws Exception {
        UUID publicId = UUID.randomUUID();
        RealtyGroup g = RealtyGroup.builder()
            .name("Acme").slug("acme").leaderId(1L)
            .balanceLindens(1000L).reservedLindens(0L)
            .build();
        // Inject publicId via reflection because BaseEntity.publicId has NONE-access setter
        Field f = com.slparcelauctions.backend.common.BaseEntity.class.getDeclaredField("publicId");
        f.setAccessible(true);
        f.set(g, publicId);

        when(groupRepo.findByIdForUpdate(42L)).thenReturn(Optional.of(g));
        when(ledgerRepo.save(any(RealtyGroupLedgerEntry.class)))
            .thenAnswer(inv -> {
                RealtyGroupLedgerEntry entry = inv.getArgument(0);
                // Set publicId on the saved ledger entry via reflection
                Field lf = com.slparcelauctions.backend.common.BaseEntity.class.getDeclaredField("publicId");
                lf.setAccessible(true);
                lf.set(entry, UUID.randomUUID());
                return entry;
            });

        service.creditAgentFee(42L, 999L, 500L);

        assertThat(g.getBalanceLindens()).isEqualTo(1500L);

        ArgumentCaptor<RealtyGroupLedgerEntry> entryCap = ArgumentCaptor.forClass(RealtyGroupLedgerEntry.class);
        verify(ledgerRepo).save(entryCap.capture());
        RealtyGroupLedgerEntry entry = entryCap.getValue();
        assertThat(entry.getGroupId()).isEqualTo(42L);
        assertThat(entry.getEntryType()).isEqualTo(RealtyGroupLedgerEntryType.AGENT_FEE_CREDIT);
        assertThat(entry.getAmount()).isEqualTo(500L);
        assertThat(entry.getBalanceAfter()).isEqualTo(1500L);
        assertThat(entry.getReservedAfter()).isEqualTo(0L);
        assertThat(entry.getRefType()).isEqualTo("AUCTION");
        assertThat(entry.getRefId()).isEqualTo(999L);
        assertThat(entry.getActorUserId()).isNull();

        verify(publisher).publish(eq(publicId), eq(1500L), eq(0L), eq(1500L),
            eq("AGENT_FEE_CREDIT"), any(UUID.class));
        verify(groupRepo).save(g);
    }

    @Test
    void creditAgentFee_clearsDormancyPhaseWhenActive() throws Exception {
        UUID publicId = UUID.randomUUID();
        RealtyGroup g = RealtyGroup.builder()
            .name("Acme").slug("acme").leaderId(1L)
            .balanceLindens(500L).reservedLindens(0L)
            .build();
        Field f = com.slparcelauctions.backend.common.BaseEntity.class.getDeclaredField("publicId");
        f.setAccessible(true);
        f.set(g, publicId);
        g.setWalletDormancyPhase((short) 2);
        g.setWalletDormancyStartedAt(java.time.OffsetDateTime.now());

        when(groupRepo.findByIdForUpdate(10L)).thenReturn(Optional.of(g));
        when(ledgerRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.creditAgentFee(10L, 1L, 100L);

        assertThat(g.getWalletDormancyPhase()).isNull();
        assertThat(g.getWalletDormancyStartedAt()).isNull();
    }

    @Test
    void creditAgentFee_doesNotClearCompletedDormancy() throws Exception {
        UUID publicId = UUID.randomUUID();
        RealtyGroup g = RealtyGroup.builder()
            .name("Acme").slug("acme").leaderId(1L)
            .balanceLindens(200L).reservedLindens(0L)
            .build();
        Field f = com.slparcelauctions.backend.common.BaseEntity.class.getDeclaredField("publicId");
        f.setAccessible(true);
        f.set(g, publicId);
        g.setWalletDormancyPhase((short) 99);

        when(groupRepo.findByIdForUpdate(20L)).thenReturn(Optional.of(g));
        when(ledgerRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.creditAgentFee(20L, 2L, 50L);

        // Phase 99 (COMPLETED) must not be cleared
        assertThat(g.getWalletDormancyPhase()).isEqualTo((short) 99);
    }
}
