package com.slparcelauctions.backend.escrow.command;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.escrow.command.dto.PayoutResultRequest;
import com.slparcelauctions.backend.escrow.payment.dto.SlCallbackResponse;
import com.slparcelauctions.backend.escrow.terminal.TerminalService;
import com.slparcelauctions.backend.sl.SlHeaderValidator;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * {@code POST /api/v1/sl/escrow/payout-result} — the in-world terminal
 * posts here after executing a PAYOUT or REFUND command (spec §7.3). Runs
 * the same three-layer trust check as the payment receiver:
 *
 * <ol>
 *   <li>SL header validation via {@link SlHeaderValidator} —
 *       {@code X-SecondLife-Shard} + {@code X-SecondLife-Owner-Key}.</li>
 *   <li>Shared-secret check via {@link TerminalService#assertSharedSecret}
 *       (constant-time comparison).</li>
 *   <li>Body resolution via {@link TerminalCommandService#applyCallback},
 *       which looks up the command by {@code idempotencyKey} and applies
 *       the success / failure state transitions under a pessimistic lock.</li>
 * </ol>
 *
 * <p>Returns an {@link SlCallbackResponse} {@code OK} on every recognised
 * outcome (success, retry scheduled, stall) so the terminal's LSL script
 * can simply log and return. The non-2xx cases are reserved for the two
 * 403 gates (headers + secret) and the 404-shaped {@code ESCROW_NOT_FOUND}
 * mapping for an unknown idempotency key.
 */
@RestController
@RequestMapping("/api/v1/sl/escrow")
@RequiredArgsConstructor
public class PayoutResultController {

    private final TerminalCommandService terminalCommandService;
    private final TerminalService terminalService;
    private final SlHeaderValidator headerValidator;

    @PostMapping("/payout-result")
    public SlCallbackResponse handle(
            @RequestHeader(value = "X-SecondLife-Shard", required = false) String shard,
            @RequestHeader(value = "X-SecondLife-Owner-Key", required = false) String ownerKey,
            @Valid @RequestBody PayoutResultRequest body) {
        headerValidator.validate(shard, ownerKey);
        terminalService.assertSharedSecret(body.sharedSecret());
        terminalCommandService.applyCallback(body);
        return SlCallbackResponse.ok();
    }
}
