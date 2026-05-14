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
import com.slparcelauctions.backend.realty.wallet.exception.InsufficientGroupBalanceException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RealtyGroupWalletService#debitListingFee}.
 * Covers: happy path (balance debited, ledger row with actor_user_id, dormancy
 * clear side effect) + insufficient-balance throws
 * {@link InsufficientGroupBalanceException}.
 */
class RealtyGroupWalletServiceDebitListingFeeTest {

    private final RealtyGroupRepository groupRepo = mock(RealtyGroupRepository.class);
    private final RealtyGroupLedgerRepository ledgerRepo = mock(RealtyGroupLedgerRepository.class);
    private final GroupWalletBroadcastPublisher pub = mock(GroupWalletBroadcastPublisher.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-12T10:00:00Z"), ZoneOffset.UTC);

    private final RealtyGroupWalletService svc =
        new RealtyGroupWalletService(groupRepo, ledgerRepo, null, null, null, pub, null, null, null, null, null, clock);

    @Test
    void debitsBalanceAndAppendsLedgerWithActor() throws Exception {
        UUID publicId = UUID.randomUUID();
        RealtyGroup g = RealtyGroup.builder()
            .name("Acme").slug("acme").leaderId(1L)
            .balanceLindens(1000L).reservedLindens(0L).build();
        Field f = com.slparcelauctions.backend.common.BaseEntity.class.getDeclaredField("publicId");
        f.setAccessible(true);
        f.set(g, publicId);

        when(groupRepo.findByIdForUpdate(42L)).thenReturn(Optional.of(g));
        when(ledgerRepo.save(any(RealtyGroupLedgerEntry.class)))
            .thenAnswer(inv -> {
                RealtyGroupLedgerEntry entry = inv.getArgument(0);
                Field lf = com.slparcelauctions.backend.common.BaseEntity.class
                    .getDeclaredField("publicId");
                lf.setAccessible(true);
                lf.set(entry, UUID.randomUUID());
                return entry;
            });

        svc.debitListingFee(42L, 999L, 250L, 7L);

        assertThat(g.getBalanceLindens()).isEqualTo(750L);

        ArgumentCaptor<RealtyGroupLedgerEntry> cap = ArgumentCaptor.forClass(RealtyGroupLedgerEntry.class);
        verify(ledgerRepo).save(cap.capture());
        RealtyGroupLedgerEntry e = cap.getValue();
        assertThat(e.getGroupId()).isEqualTo(42L);
        assertThat(e.getEntryType()).isEqualTo(RealtyGroupLedgerEntryType.LISTING_FEE_DEBIT);
        assertThat(e.getAmount()).isEqualTo(250L);
        assertThat(e.getBalanceAfter()).isEqualTo(750L);
        assertThat(e.getReservedAfter()).isEqualTo(0L);
        assertThat(e.getRefType()).isEqualTo("AUCTION");
        assertThat(e.getRefId()).isEqualTo(999L);
        assertThat(e.getActorUserId()).isEqualTo(7L);

        verify(pub).publish(eq(publicId), eq(750L), eq(0L), eq(750L),
            eq("LISTING_FEE_DEBIT"), any(UUID.class));
        verify(groupRepo).save(g);
    }

    @Test
    void clearsDormancyPhaseWhenActive() throws Exception {
        UUID publicId = UUID.randomUUID();
        RealtyGroup g = RealtyGroup.builder()
            .name("Acme").slug("acme").leaderId(1L)
            .balanceLindens(500L).reservedLindens(0L).build();
        Field f = com.slparcelauctions.backend.common.BaseEntity.class.getDeclaredField("publicId");
        f.setAccessible(true);
        f.set(g, publicId);
        g.setWalletDormancyPhase((short) 1);
        g.setWalletDormancyStartedAt(java.time.OffsetDateTime.now());

        when(groupRepo.findByIdForUpdate(10L)).thenReturn(Optional.of(g));
        when(ledgerRepo.save(any())).thenAnswer(inv -> {
            RealtyGroupLedgerEntry entry = inv.getArgument(0);
            Field lf = com.slparcelauctions.backend.common.BaseEntity.class
                .getDeclaredField("publicId");
            lf.setAccessible(true);
            lf.set(entry, UUID.randomUUID());
            return entry;
        });

        svc.debitListingFee(10L, 1L, 100L, 5L);

        assertThat(g.getWalletDormancyPhase()).isNull();
        assertThat(g.getWalletDormancyStartedAt()).isNull();
    }

    @Test
    void throwsWhenInsufficientBalance() {
        RealtyGroup g = RealtyGroup.builder()
            .name("Acme").slug("acme").leaderId(1L)
            .balanceLindens(100L).reservedLindens(0L).build();
        when(groupRepo.findByIdForUpdate(42L)).thenReturn(Optional.of(g));

        assertThatThrownBy(() -> svc.debitListingFee(42L, 999L, 250L, 7L))
            .isInstanceOf(InsufficientGroupBalanceException.class);

        verify(ledgerRepo, never()).save(any());
        verify(groupRepo, never()).save(any());
    }

    @Test
    void throwsWhenInsufficientAvailableLindens() {
        // reserved_lindens > 0 reduces available below balance
        RealtyGroup g = RealtyGroup.builder()
            .name("Acme").slug("acme").leaderId(1L)
            .balanceLindens(300L).reservedLindens(200L).build();
        when(groupRepo.findByIdForUpdate(42L)).thenReturn(Optional.of(g));

        // available = 100, requesting 200
        assertThatThrownBy(() -> svc.debitListingFee(42L, 999L, 200L, 7L))
            .isInstanceOf(InsufficientGroupBalanceException.class);
    }
}
