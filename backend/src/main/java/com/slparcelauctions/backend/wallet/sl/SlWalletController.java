package com.slparcelauctions.backend.wallet.sl;

import java.util.UUID;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.escrow.terminal.TerminalRepository;
import com.slparcelauctions.backend.escrow.terminal.TerminalService;
import com.slparcelauctions.backend.sl.SlHeaderValidator;
import com.slparcelauctions.backend.wallet.WalletService;
import com.slparcelauctions.backend.wallet.exception.InsufficientAvailableBalanceException;
import com.slparcelauctions.backend.wallet.exception.UserNotLinkedException;
import com.slparcelauctions.backend.wallet.exception.UserStatusBlockedException;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * SL-headers-gated wallet endpoints called by the in-world SLParcels Terminal.
 *
 * <p>{@code POST /api/v1/sl/wallet/deposit} — terminal posts after a
 * {@code money()} event. Returns {@code OK} on credit, {@code REFUND} on
 * unknown payer / banned user (LSL bounces the L$), or {@code ERROR} on
 * auth failures (LSL does NOT bounce).
 *
 * <p>{@code POST /api/v1/sl/wallet/withdraw-request} — terminal posts after
 * touch-confirmed withdraw. Returns {@code OK + queueId} on debit-and-queue,
 * {@code REFUND_BLOCKED} on insufficient balance / not linked / frozen user
 * (LSL does NOT bounce — no L$ was paid in this flow), or {@code ERROR} on
 * auth failures.
 *
 * <p>See spec §4.1.
 */
@RestController
@RequestMapping("/api/v1/sl/wallet")
@RequiredArgsConstructor
@Slf4j
public class SlWalletController {

    private final WalletService walletService;
    private final TerminalService terminalService;
    private final TerminalRepository terminalRepository;
    private final SlHeaderValidator headerValidator;

    @PostMapping("/deposit")
    public SlWalletResponse deposit(
            @RequestHeader(value = "X-SecondLife-Shard", required = false) String shard,
            @RequestHeader(value = "X-SecondLife-Owner-Key", required = false) String ownerKey,
            @Valid @RequestBody SlWalletDepositRequest req) {
        headerValidator.validate(shard, ownerKey);
        terminalService.assertSharedSecret(req.sharedSecret());
        // Deposit is L$-bearing — when ANY deposit-time check fails, the
        // L$ is already in the script's hands and we instruct it to refund.
        // Returning ERROR (script logs only, no bounce) would steal from the
        // payer, so unrecognised terminals / unparseable payer UUIDs are
        // mapped to REFUND too. Only the upstream auth failures
        // (header / shared-secret) remain ERROR — those throw before we
        // get here and indicate an attacker, not a real payer.
        if (!terminalRepository.existsById(req.terminalId())) {
            return SlWalletResponse.refund(SlWalletResponseReason.UNKNOWN_TERMINAL,
                    "terminalId not registered");
        }
        // Authenticated traffic from this terminal keeps it "live" in the
        // dispatcher's view — without this, lastSeenAt would only refresh on
        // /sl/terminal/register, and an idle-but-healthy terminal would
        // silently drop out of dispatch rotation after the live window
        // expires.
        terminalService.markSeen(req.terminalId());

        UUID payerUuid;
        try {
            payerUuid = UUID.fromString(req.payerUuid());
        } catch (IllegalArgumentException e) {
            return SlWalletResponse.refund(SlWalletResponseReason.UNKNOWN_TERMINAL,
                    "payerUuid not parseable as UUID");
        }

        try {
            walletService.deposit(payerUuid, req.amount(), req.slTransactionKey());
            return SlWalletResponse.ok();
        } catch (UserNotLinkedException e) {
            return SlWalletResponse.refund(SlWalletResponseReason.UNKNOWN_PAYER,
                    "no SLParcels account linked to this avatar");
        } catch (UserStatusBlockedException e) {
            return SlWalletResponse.refund(SlWalletResponseReason.USER_FROZEN,
                    "wallet is currently frozen for this account");
        }
    }

    @PostMapping("/withdraw-request")
    public SlWalletResponse withdrawRequest(
            @RequestHeader(value = "X-SecondLife-Shard", required = false) String shard,
            @RequestHeader(value = "X-SecondLife-Owner-Key", required = false) String ownerKey,
            @Valid @RequestBody SlWalletWithdrawRequest req) {
        headerValidator.validate(shard, ownerKey);
        terminalService.assertSharedSecret(req.sharedSecret());
        if (!terminalRepository.existsById(req.terminalId())) {
            return SlWalletResponse.error(SlWalletResponseReason.UNKNOWN_TERMINAL,
                    "terminalId not registered");
        }
        // Authenticated traffic from this terminal keeps it "live" in the
        // dispatcher's view — without this, lastSeenAt would only refresh on
        // /sl/terminal/register, and an idle-but-healthy terminal would
        // silently drop out of dispatch rotation after the live window
        // expires.
        terminalService.markSeen(req.terminalId());

        UUID payerUuid;
        try {
            payerUuid = UUID.fromString(req.payerUuid());
        } catch (IllegalArgumentException e) {
            return SlWalletResponse.error(SlWalletResponseReason.UNKNOWN_TERMINAL,
                    "payerUuid not parseable as UUID");
        }

        try {
            WalletService.WithdrawQueuedResult result =
                    walletService.withdrawTouchInitiated(payerUuid, req.amount(), req.slTransactionKey());
            if (result instanceof WalletService.WithdrawQueuedResult.Ok ok) {
                return SlWalletResponse.okWithQueue(ok.entry().getId());
            }
            // Replay returns the original entry; treat as OK with the same queueId.
            return SlWalletResponse.okWithQueue(result.entry().getId());
        } catch (UserNotLinkedException e) {
            return SlWalletResponse.refundBlocked(SlWalletResponseReason.NOT_LINKED,
                    "no SLParcels account linked to this avatar");
        } catch (UserStatusBlockedException e) {
            return SlWalletResponse.refundBlocked(SlWalletResponseReason.USER_FROZEN,
                    "wallet is currently frozen for this account");
        } catch (com.slparcelauctions.backend.wallet.exception.WalletFrozenException e) {
            return SlWalletResponse.refundBlocked(SlWalletResponseReason.USER_FROZEN,
                    "wallet is currently frozen by an admin");
        } catch (InsufficientAvailableBalanceException e) {
            return SlWalletResponse.refundBlocked(SlWalletResponseReason.INSUFFICIENT_BALANCE,
                    "insufficient available balance: " + e.getAvailable());
        }
    }
}
