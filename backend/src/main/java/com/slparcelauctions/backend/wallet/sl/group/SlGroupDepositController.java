package com.slparcelauctions.backend.wallet.sl.group;

import java.util.UUID;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.escrow.terminal.TerminalRepository;
import com.slparcelauctions.backend.escrow.terminal.TerminalService;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.auth.RealtyGroupAuthorizer;
import com.slparcelauctions.backend.realty.moderation.exception.RealtyGroupSuspendedException;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
import com.slparcelauctions.backend.realty.wallet.RealtyGroupWalletService;
import com.slparcelauctions.backend.realty.wallet.exception.DepositAmountOutOfRangeException;
import com.slparcelauctions.backend.sl.SlHeaderValidator;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.wallet.sl.SlWalletResponse;
import com.slparcelauctions.backend.wallet.sl.SlWalletResponseReason;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * SL-headers + shared-secret gated endpoint for the in-world terminal's
 * "Pay to group" {@code money()} response.
 *
 * <p>The terminal hits this when an avatar has paid L$ AND has a pending
 * group-deposit context slot (set via the prior {@code /avatar-groups} dialog).
 * The endpoint credits the selected group's wallet from the L$ already in the
 * script's hands.
 *
 * <p><b>Refund discipline (per CLAUDE.md):</b> by the time this endpoint runs,
 * L$ has already reached the script. Every post-auth failure path returns
 * {@code REFUND} so the LSL script bounces the L$ back via
 * {@code llTransferLindenDollars}. {@code ERROR} is reserved for upstream auth
 * rejection (bad shard / owner / shared-secret), which is mapped by
 * {@code WalletSlExceptionHandler} before any deposit logic runs.
 *
 * <p>See design spec §4.3.
 */
@RestController
@RequestMapping("/api/v1/sl/wallet")
@RequiredArgsConstructor
@Slf4j
public class SlGroupDepositController {

    private final TerminalService terminalService;
    private final TerminalRepository terminalRepository;
    private final SlHeaderValidator headerValidator;
    private final UserRepository userRepository;
    private final RealtyGroupRepository groupRepository;
    private final RealtyGroupAuthorizer authorizer;
    private final RealtyGroupWalletService walletService;

    @PostMapping("/group-deposit")
    public SlWalletResponse groupDeposit(
            @RequestHeader(value = "X-SecondLife-Shard", required = false) String shard,
            @RequestHeader(value = "X-SecondLife-Owner-Key", required = false) String ownerKey,
            @Valid @RequestBody SlGroupDepositRequest req) {
        headerValidator.validate(shard, ownerKey);
        terminalService.assertSharedSecret(req.sharedSecret());

        // Unknown terminal: REFUND. By the time this endpoint runs, the
        // money() event has fired and L$ is in the script's hands -- so
        // every post-auth failure must bounce. Header + shared-secret
        // pre-flight already validated the caller as a real SLParcels
        // terminal; an unrecognised terminal-row here is a config mismatch
        // on our side, not a payer-facing issue, and returning ERROR (no
        // bounce) would steal from the payer. Matches the peer
        // SlWalletController.deposit path (see CLAUDE.md "always refund on
        // deposit error").
        if (!terminalRepository.existsById(req.terminalId())) {
            return SlWalletResponse.refund(SlWalletResponseReason.UNKNOWN_TERMINAL,
                "terminalId not registered");
        }
        // Authenticated traffic from this terminal keeps it "live" in the
        // dispatcher's view (mirrors SlWalletController.deposit).
        terminalService.markSeen(req.terminalId());

        UUID payerUuid;
        try {
            payerUuid = UUID.fromString(req.payerUuid());
        } catch (IllegalArgumentException e) {
            // Unparseable payerUuid implies an LSL bug, not a real payer with
            // an unknown account — match the wallet deposit controller's
            // mapping (REFUND/UNKNOWN_TERMINAL) so the LSL script still
            // bounces the L$ rather than swallowing it.
            return SlWalletResponse.refund(SlWalletResponseReason.UNKNOWN_TERMINAL,
                "payerUuid not parseable as UUID");
        }

        // From here on every failure is REFUND (L$ in hand).
        Long userId = userRepository.findIdBySlAvatarUuid(payerUuid).orElse(null);
        if (userId == null) {
            return SlWalletResponse.refund(SlWalletResponseReason.UNKNOWN_PAYER,
                "no SLParcels account linked to this avatar");
        }

        RealtyGroup group = groupRepository.findByPublicId(req.groupPublicId()).orElse(null);
        if (group == null) {
            return SlWalletResponse.refund(SlWalletResponseReason.UNKNOWN_GROUP,
                "unknown group");
        }
        if (group.getDissolvedAt() != null) {
            return SlWalletResponse.refund(SlWalletResponseReason.GROUP_DISSOLVED,
                "group dissolved");
        }

        try {
            authorizer.assertCan(userId, group.getId(),
                RealtyGroupPermission.DEPOSIT_TO_GROUP_WALLET);
        } catch (RuntimeException e) {
            // Lost membership row, permission revoked, ex-member, etc.
            // Catch broadly — the authorizer can throw any of
            // RealtyGroupPermissionDeniedException / RealtyGroupNotFoundException
            // / GroupDissolvedException, and we want every flavour to refund.
            log.info("group-deposit terminal: permission denied for userId={}, groupId={}: {}",
                userId, group.getId(), e.getMessage());
            return SlWalletResponse.refund(SlWalletResponseReason.PERMISSION_REVOKED,
                "no deposit permission");
        }

        // Region name from the terminal row for the ledger description.
        String regionName = terminalRepository.findById(req.terminalId())
            .map(t -> t.getRegionName())
            .orElse(null);

        try {
            walletService.depositFromTerminal(group.getId(), req.amount(), userId,
                regionName, req.slTransactionKey());
            return SlWalletResponse.ok();
        } catch (DepositAmountOutOfRangeException e) {
            return SlWalletResponse.refund(SlWalletResponseReason.AMOUNT_OUT_OF_RANGE,
                e.getMessage());
        } catch (RealtyGroupSuspendedException e) {
            return SlWalletResponse.refund(SlWalletResponseReason.GROUP_SUSPENDED,
                "group suspended");
        }
    }
}
