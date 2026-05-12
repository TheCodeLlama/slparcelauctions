package com.slparcelauctions.backend.realty.wallet;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.wallet.broadcast.GroupWalletBroadcastPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RealtyGroupWalletService#creditListingFeeRefund}.
 * Covers: happy path (balance credited, ledger row shape) + dissolved-group
 * rejection ({@link IllegalStateException}).
 */
class RealtyGroupWalletServiceCreditListingFeeRefundTest {

    private final RealtyGroupRepository groupRepo = mock(RealtyGroupRepository.class);
    private final RealtyGroupLedgerRepository ledgerRepo = mock(RealtyGroupLedgerRepository.class);
    private final GroupWalletBroadcastPublisher pub = mock(GroupWalletBroadcastPublisher.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-12T10:00:00Z"), ZoneOffset.UTC);

    private final RealtyGroupWalletService svc =
        new RealtyGroupWalletService(groupRepo, ledgerRepo, null, null, null, pub, null, clock);

    @Test
    void creditsBalanceAndAppendsRefundLedger() throws Exception {
        UUID publicId = UUID.randomUUID();
        RealtyGroup g = RealtyGroup.builder()
            .name("Acme").slug("acme").leaderId(1L)
            .balanceLindens(500L).reservedLindens(0L).build();
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

        svc.creditListingFeeRefund(42L, 999L, 200L, 17L);

        assertThat(g.getBalanceLindens()).isEqualTo(700L);

        ArgumentCaptor<RealtyGroupLedgerEntry> cap =
            ArgumentCaptor.forClass(RealtyGroupLedgerEntry.class);
        verify(ledgerRepo).save(cap.capture());
        RealtyGroupLedgerEntry entry = cap.getValue();
        assertThat(entry.getGroupId()).isEqualTo(42L);
        assertThat(entry.getEntryType()).isEqualTo(RealtyGroupLedgerEntryType.LISTING_FEE_REFUND);
        assertThat(entry.getAmount()).isEqualTo(200L);
        assertThat(entry.getBalanceAfter()).isEqualTo(700L);
        assertThat(entry.getReservedAfter()).isEqualTo(0L);
        assertThat(entry.getRefType()).isEqualTo("LISTING_FEE_REFUND");
        assertThat(entry.getRefId()).isEqualTo(17L);

        verify(pub).publish(eq(publicId), eq(700L), eq(0L), eq(700L),
            eq("LISTING_FEE_REFUND"), any(UUID.class));
        verify(groupRepo).save(g);
    }

    @Test
    void rejectsDissolvedGroup() {
        RealtyGroup g = RealtyGroup.builder()
            .name("Acme").slug("acme").leaderId(1L)
            .balanceLindens(0L).reservedLindens(0L).build();
        g.setDissolvedAt(OffsetDateTime.now());

        when(groupRepo.findByIdForUpdate(42L)).thenReturn(Optional.of(g));

        assertThatThrownBy(() -> svc.creditListingFeeRefund(42L, 999L, 200L, 17L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("dissolved group");

        verify(ledgerRepo, never()).save(any());
        verify(groupRepo, never()).save(any());
    }
}
