package com.slparcelauctions.backend.realty.wallet;

import org.junit.jupiter.api.Test;

import com.slparcelauctions.backend.escrow.command.TerminalCommand;
import com.slparcelauctions.backend.escrow.command.TerminalCommandPurpose;
import com.slparcelauctions.backend.notification.NotificationPublisher;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GroupWalletWithdrawalCallbackHandler}.
 *
 * <p>Covers: onSuccess parses the GWAL-{n} idempotency key, calls
 * recordWithdrawalSuccess, and notifies; onStall reverses and notifies;
 * malformed/null key logs and returns without calling the service.
 */
class GroupWalletWithdrawalCallbackHandlerTest {

    private final RealtyGroupWalletService svc = mock(RealtyGroupWalletService.class);
    private final NotificationPublisher notif = mock(NotificationPublisher.class);
    private final GroupWalletWithdrawalCallbackHandler handler =
        new GroupWalletWithdrawalCallbackHandler(svc, notif);

    @Test
    void onSuccess_parsesLedgerIdAndRecordsAndNotifies() {
        TerminalCommand cmd = TerminalCommand.builder()
            .purpose(TerminalCommandPurpose.GROUP_WALLET_WITHDRAWAL)
            .idempotencyKey("GWAL-42")
            .amount(500L)
            .build();
        when(svc.findGroupIdForLedgerEntry(42L)).thenReturn(7L);

        handler.onSuccess(cmd, "slTxn-abc");

        verify(svc).recordWithdrawalSuccess(42L, "slTxn-abc");
        verify(notif).groupWalletWithdrawalCompleted(7L, 500L, 42L);
    }

    @Test
    void onSuccess_nullGroupId_stillRecordsButSkipsNotification() {
        TerminalCommand cmd = TerminalCommand.builder()
            .idempotencyKey("GWAL-42")
            .amount(500L)
            .build();
        when(svc.findGroupIdForLedgerEntry(42L)).thenReturn(null);

        handler.onSuccess(cmd, "slTxn-xyz");

        verify(svc).recordWithdrawalSuccess(42L, "slTxn-xyz");
        verify(notif, never()).groupWalletWithdrawalCompleted(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void onStall_reversesAndNotifies() {
        TerminalCommand cmd = TerminalCommand.builder()
            .idempotencyKey("GWAL-99")
            .amount(250L)
            .build();
        when(svc.findGroupIdForLedgerEntry(99L)).thenReturn(7L);

        handler.onStall(cmd, "transport failure");

        verify(svc).recordWithdrawalReversal(99L, "transport failure");
        verify(notif).groupWalletWithdrawalReversed(7L, 250L, 99L, "transport failure");
    }

    @Test
    void onSuccess_malformedKey_noServiceCall() {
        TerminalCommand cmd = TerminalCommand.builder()
            .idempotencyKey("WALLET-99")  // wrong prefix
            .amount(100L)
            .build();

        handler.onSuccess(cmd, "slTxn-xxx");

        verify(svc, never()).recordWithdrawalSuccess(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
    }

    @Test
    void onStall_nullKey_noServiceCall() {
        TerminalCommand cmd = TerminalCommand.builder()
            .idempotencyKey(null)
            .amount(100L)
            .build();

        handler.onStall(cmd, "reason");

        verify(svc, never()).recordWithdrawalReversal(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
    }
}
