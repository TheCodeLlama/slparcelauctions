# Wallet & verifier bug fixes — design

## Context

Three bugs surfaced in prod testing on 2026-05-01 after the wallet model rolled out:

1. **Verifier reports failure on success.** Backend logs `SL verification succeeded` and writes the user as verified; the in-world LSL says "Verification failed".
2. **Wallet ledger doesn't auto-refresh** after a successful withdrawal completes — the live L$ balance updates but the ledger table still shows "Withdraw Queued".
3. **Withdrawals stay "Withdraw Queued" forever.** L$ are paid out in-world but the website never sees `WITHDRAW_COMPLETED`. Some commands are looping with attempt counts in the 25-29 range against a cap of 4.

Investigation showed that bugs 2 and 3 share a root cause, plus there's a bonus bug in the dispatcher worth fixing now.

## Root causes

### Bug 3 — LSL inflight tracking type mismatch

`slpa-terminal.lsl` records HTTP-in withdraw commands so the asynchronous `transaction_result` event can resolve back to the original `idempotencyKey` and POST `/sl/escrow/payout-result`. The bug is a type mismatch:

```lsl
// addInflightCommand (line ~361): stores txKey as type "key"
inflightCmdTxKeys = inflightCmdTxKeys + [txKey];

// removeInflightByTxKey (line ~368): searches as type "string"
integer idx = llListFindList(inflightCmdTxKeys, [(string)txKey]);
```

`llListFindList` is type-strict. A `key`-typed list element does **not** match a `string`-typed search element with the same UUID. Result: `idx = -1`, `transaction_result` short-circuits at `if (llGetListLength(inflight) == 0) return;`, no `/payout-result` POST, command sits IN_FLIGHT, the 5-minute timeout requeues, and `llTransferLindenDollars` is called again on the next dispatch — leading to potential double-payment.

### Bug 2 — same root cause as Bug 3

The wallet WebSocket broadcast publishes a `WalletBalanceChangedEnvelope` after every ledger mutation. The frontend's `useWalletWsSubscription` hook patches the balance fields and invalidates `["me", "wallet", "ledger"]` on every envelope. Both work correctly.

The reason the user perceives "ledger doesn't auto-refresh" is that the `WITHDRAW_COMPLETED` row is never appended (Bug 3) — so no second WS envelope fires after the queue event, and the ledger never re-loads to show a completion row. With Bug 3 fixed, Bug 2 evaporates. Verification step in the test plan covers it.

### Bug 1 — LSL JSON_TRUE constant

`llJsonGetValue(body, ["verified"])` for a JSON boolean `true` returns the LSL constant `JSON_TRUE = "~true"`, not the literal string `"true"`. `if (verified == "true")` is therefore always false, and the verification-terminal LSL falls into the "Verification failed" branch even when the backend returned `verified: true`.

### Bonus bug — `markStaleAndRequeue` doesn't enforce the attempt cap

`TerminalCommandDispatcherTask.markStaleAndRequeue` flips IN_FLIGHT commands back to FAILED for retry without checking `attemptCount >= MAX_ATTEMPTS`. Only the transport-failure path checks the cap. So a command that gets stuck in the IN_FLIGHT-timeout cycle (e.g., today's Bug 3 manifestation) loops forever, never stalls, and never refunds the user.

## Fixes

### Fix 1: LSL inflight tracking — store txKey as string

`lsl-scripts/slpa-terminal/slpa-terminal.lsl`:

```lsl
addInflightCommand(key txKey, string idempotencyKey, key recipient, integer amount) {
    ...
    inflightCmdTxKeys = inflightCmdTxKeys + [(string)txKey];   // cast at insertion
    ...
}
```

The `removeInflightByTxKey` search side already casts to string — keep that cast and the types now match.

Add a one-line comment noting the LSL type-strict footgun so a future change doesn't silently re-introduce the bug.

### Fix 2: LSL JSON_TRUE comparison

`lsl-scripts/verification-terminal/verification-terminal.lsl`:

```lsl
if (verified == JSON_TRUE) {
    // ✓ Linked SLPA #...
} else {
    // ✗ Verification failed.
}
```

`JSON_TRUE` is an LSL standard library constant.

### Fix 3: Dispatcher — enforce attempt cap on stale-requeue

`backend/src/main/java/com/slparcelauctions/backend/escrow/scheduler/TerminalCommandDispatcherTask.java`:

```java
public void markStaleAndRequeue(Long commandId) {
    TerminalCommand cmd = cmdRepo.findByIdForUpdate(commandId).orElse(null);
    if (cmd == null || cmd.getStatus() != TerminalCommandStatus.IN_FLIGHT) return;
    OffsetDateTime now = OffsetDateTime.now(clock);
    cmd.setLastError("IN_FLIGHT timeout without callback");

    if (cmd.getAttemptCount() >= EscrowRetryPolicy.MAX_ATTEMPTS) {
        cmd.setStatus(TerminalCommandStatus.FAILED);
        cmd.setRequiresManualReview(true);
        cmdRepo.save(cmd);
        // Mirror the existing transport-stall path: refund wallet
        // withdrawals + IM the user. Skip escrow stall envelope
        // because there's no escrow row.
        if (cmd.getPurpose() == TerminalCommandPurpose.WALLET_WITHDRAWAL) {
            walletWithdrawalCallbackHandler.onStall(cmd,
                "IN_FLIGHT timeout without callback");
        }
        log.error("Terminal command {} STALLED after {} attempts (in-flight timeout)",
                cmd.getId(), cmd.getAttemptCount());
        return;
    }

    cmd.setStatus(TerminalCommandStatus.FAILED);
    cmd.setNextAttemptAt(now);
    cmdRepo.save(cmd);
    log.warn("Command {} IN_FLIGHT timeout (dispatchedAt={}); requeued for immediate retry",
            cmd.getId(), cmd.getDispatchedAt());
}
```

A new unit test asserts that calling `markStaleAndRequeue` on a command with `attemptCount = MAX_ATTEMPTS` stalls instead of requeueing.

### Fix 4: Cleanup of existing stuck commands — wipe DB instead

The pre-fix loop produced commands at attempt 25+ that would re-dispatch after the LSL fix and double-pay the user. Rather than write cleanup SQL, run the same Fargate `postgres:16-alpine` truncate-everything wipe used earlier this session. The L$ that already left the prim during the buggy retry storm stays where it landed — no ledger to reconcile against because the ledger is gone. Pre-launch posture, no real customers; this is the simplest path.

Order of operations:

1. Land code fixes 1-3.
2. Deploy backend (GHA).
3. Wipe DB (`TRUNCATE ... RESTART IDENTITY CASCADE` via one-off Fargate task).
4. Operator resets in-world SLPA Terminal + Verification Terminal so they pick up the new LSL.
5. Operator re-registers their account + re-verifies + smoke-tests.

## Test plan

**Backend tests (new + existing):**
- New unit test for `markStaleAndRequeue` attempt-cap behavior.
- Existing dispatcher + wallet callback handler tests still pass.

**Manual end-to-end after deploy:**

1. Reset the in-world SLPA Terminal so it picks up the LSL fix.
2. Reset the in-world Verification Terminal so it picks up the LSL fix.
3. Have the alt verify their account → terminal should chat `✓ Linked SLPA #...` (not "Verification failed").
4. From the verified alt, deposit L$ via the SLPA Terminal.
5. From the website, withdraw L$1 → confirm:
   - Funds arrive **exactly once** in the alt's avatar.
   - Ledger shows both `Withdraw Queued` and `Withdrawal completed` rows.
   - Header pill briefly shows "Queued for Withdrawal: L$1" between the two events.
   - In-world IM "Withdrawal completed: L$1" arrives at the alt.
6. Verify the existing stuck commands no longer dispatch (by tailing CloudWatch).

## Out of scope

- Wider audit of LSL JSON parsing for similar JSON_TRUE/JSON_FALSE bugs in other scripts. (Only verification-terminal currently parses a boolean response.)
- Backend changes to the verification response shape (e.g., switching to 204 No Content). The LSL fix is sufficient and lower-risk.
- Refactor of the inflight-tracking strided list into a typed-record pattern. The cast fix is enough; refactor would be a YAGNI change.
