# Realty Groups: G — Part 3: Section E (case-3 escrow) + Section F polish + Section B frontend cleanup

> Index: [`2026-05-12-realty-groups-final-cleanup.md`](2026-05-12-realty-groups-final-cleanup.md).
> Spec: [`docs/superpowers/specs/2026-05-12-realty-groups-final-cleanup-design.md`](../specs/2026-05-12-realty-groups-final-cleanup-design.md).

**Tasks 22–27.** Section E case-3 zero-payout fix (escrow + LSL + notification + spec sync), Section F polish (`SystemUserResolver` field, reverify batch size, `@Transactional` boundary audit), Section B frontend cleanup (`AgentFeePreview` → `AgentCommissionPreview`).

**Order rule:** Part 3 depends on Parts 1 + 2 only for the dropped C-era fields it references — `auction.realtyGroupId`, `auction.realtyGroupSlGroupId`, `RealtyGroup.agentCommissionRate` on member rows (none of these are touched by Part 1's drops). Task 23 (LSL) and Task 25 (spec doc edit) touch files no other task in this part references; they are file-disjoint with all of 22 / 24 / 26 / 27.

**Concurrency:** Tasks 22, 23, 24, 25 are pairwise file-disjoint and parallel-safe. Task 26 (F polish bundle, three sub-step groups) is parallel-safe with all of 22–25. Task 27 (Section B frontend) is parallel-safe with all of 22–26. Reviewers should be told the concurrent set is `{22, 23, 24, 25, 26, 27}` if dispatched in one batch — every task in this part is independent of every other.

---

## Task 22: `TerminalCommandService.queuePayout` zero-payout early-return + `runZeroPayoutSuccessInline` + `EscrowService` caller fix [parallel-safe]

**Spec ref:** §8.1.

**Background:** Today `TerminalCommandService.queuePayout` (lines 81–88) unconditionally enqueues a `TerminalCommandAction.PAYOUT` row with `escrow.getPayoutAmt()`. For case-3 (SL-group-owned) auctions, `EscrowService.createForEndedAuction` already sets `payoutAmt = 0`, so the terminal POST is meaningless: it transfers L$0 in-world and round-trips for no economic effect. The downstream wallet credits (agent commission slice + group slice) happen inside the post-payout success path via `AgentCommissionDistributor`. After this task, case-3 escrows skip the terminal entirely and run the success path inline.

The single caller is `EscrowService.queuePayoutOnConfirm(Escrow)` at `backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowService.java:443`. There is no separate `EscrowPayoutService` class — the spec's earlier wording was loose; the caller fix lives in `EscrowService`.

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/command/TerminalCommandService.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowService.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/escrow/command/TerminalCommandServiceZeroPayoutTest.java`

- [ ] **Step 1: Write failing tests**

Test file: `backend/src/test/java/com/slparcelauctions/backend/escrow/command/TerminalCommandServiceZeroPayoutTest.java`

```java
package com.slparcelauctions.backend.escrow.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.escrow.EscrowState;
import com.slparcelauctions.backend.escrow.EscrowTransactionRepository;
import com.slparcelauctions.backend.escrow.EscrowTransactionStatus;
import com.slparcelauctions.backend.escrow.EscrowTransactionType;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Verifies the Sub-project G §8.1 short-circuit:
 * <ul>
 *   <li>{@code queuePayout} returns {@code Optional.empty()} for payoutAmt == 0.</li>
 *   <li>No {@link TerminalCommand} row is persisted for the escrow.</li>
 *   <li>An {@code AUCTION_ESCROW_PAYOUT} ledger row with amount=0 is written.</li>
 *   <li>The escrow transitions to {@link EscrowState#COMPLETED}.</li>
 *   <li>Re-invocation is idempotent: a second call is a no-op (no extra ledger row,
 *       no second {@code AgentCommissionDistributor} invocation).</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "slpa.realty.group-bulk-suspend.enabled=false",
    "slpa.realty.sl-group.reverify.enabled=false",
    "slpa.realty.group-suspension-expiry.enabled=false"
})
class TerminalCommandServiceZeroPayoutTest {

    @Autowired TerminalCommandService svc;
    @Autowired EscrowRepository escrowRepo;
    @Autowired EscrowTransactionRepository ledgerRepo;
    @Autowired TerminalCommandRepository cmdRepo;
    @Autowired AuctionRepository auctionRepo;
    @Autowired UserRepository userRepo;
    @Autowired RealtyGroupRepository groupRepo;

    @Test
    @Transactional
    void queuePayout_returns_empty_and_runs_success_inline_when_payout_amt_is_zero() {
        // Arrange — fixture builders (omitted for brevity; reuse the test
        // fixture helpers already used by E's escrow tests):
        Escrow escrow = fixtureCase3Escrow(0L);

        Optional<TerminalCommand> result = svc.queuePayout(escrow);

        assertThat(result).isEmpty();
        assertThat(cmdRepo.findAllByEscrowId(escrow.getId())).isEmpty();

        Escrow reloaded = escrowRepo.findById(escrow.getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(EscrowState.COMPLETED);

        long payoutRows = ledgerRepo.findByEscrowId(escrow.getId()).stream()
                .filter(r -> r.getType() == EscrowTransactionType.AUCTION_ESCROW_PAYOUT)
                .count();
        assertThat(payoutRows).isEqualTo(1L);
    }

    @Test
    @Transactional
    void queuePayout_is_idempotent_when_escrow_already_completed() {
        Escrow escrow = fixtureCase3Escrow(0L);
        svc.queuePayout(escrow);                 // first call — completes inline
        long ledgerCountAfterFirst = ledgerRepo.count();

        Optional<TerminalCommand> second = svc.queuePayout(escrow);

        assertThat(second).isEmpty();
        assertThat(ledgerRepo.count()).isEqualTo(ledgerCountAfterFirst);
    }

    @Test
    @Transactional
    void queuePayout_enqueues_a_command_when_payout_amt_is_positive() {
        Escrow escrow = fixtureCase1Escrow(1000L);

        Optional<TerminalCommand> result = svc.queuePayout(escrow);

        assertThat(result).isPresent();
        assertThat(result.get().getAmount()).isEqualTo(1000L);
        assertThat(result.get().getAction()).isEqualTo(TerminalCommandAction.PAYOUT);
    }

    // Fixture builders: real entities saved via the @Autowired repos.
    // Build a verified seller, a buyer, a realty group + SL-group registration,
    // an Auction with realtyGroupId + realtyGroupSlGroupId set, then an Escrow
    // in TRANSFER_CONFIRMED state with payoutAmt = 0 and commissionAmt > 0.
    private Escrow fixtureCase3Escrow(long payoutAmt) {
        // Implementation detail: mirror the fixture shape used in
        // backend/src/test/java/.../escrow/EscrowServiceCase3Test.java
        // (whichever test class E added when it first introduced the
        // payoutAmt = 0 invariant). Returns a persisted Escrow whose
        // auction.realtyGroupSlGroupId != null and payoutAmt = 0.
        throw new UnsupportedOperationException("fill in from E's case-3 fixture");
    }

    private Escrow fixtureCase1Escrow(long payoutAmt) {
        // Legacy case-1 fixture for the positive-path assertion. After Part 1
        // Section A removes case-1, this can be simplified to a plain
        // individual (non-group) escrow — the positive path doesn't depend on
        // realtyGroupId at all.
        throw new UnsupportedOperationException("fill in from a plain individual escrow fixture");
    }
}
```

Note: the implementer subagent should reuse whatever fixture pattern the existing case-3 escrow tests use. The unresolved `fixtureCase3Escrow` / `fixtureCase1Escrow` markers here are placeholders for that copy-paste — they're not new infrastructure.

- [ ] **Step 2: Run the tests to confirm they fail**

```bash
./mvnw test -Dtest=TerminalCommandServiceZeroPayoutTest
```

Expected: `queuePayout_returns_empty_and_runs_success_inline_when_payout_amt_is_zero` fails because `queuePayout` currently returns a non-null `TerminalCommand` (not `Optional`). The compile itself will fail until the return type is changed in Step 3.

- [ ] **Step 3: Refactor `queuePayout` to return `Optional<TerminalCommand>` with the early-return**

Edit `backend/src/main/java/com/slparcelauctions/backend/escrow/command/TerminalCommandService.java`. Change the `queuePayout` signature + body:

```java
import java.util.Optional;
// ... existing imports
import com.slparcelauctions.backend.auction.Auction;

/**
 * Queues an escrow payout to the seller's SL terminal. Sub-project G §8.1
 * short-circuit: when {@code escrow.getPayoutAmt() == 0L} (case-3
 * SL-group-owned auctions; the agent commission and group slice both flow
 * through {@link AgentCommissionDistributor} inside the success path),
 * the terminal round-trip is skipped and the post-payout work runs inline
 * via {@link #runZeroPayoutSuccessInline}. Returns {@link Optional#empty()}
 * in that case so the caller knows no {@code TerminalCommand} was enqueued.
 *
 * @return the enqueued command, or {@link Optional#empty()} when the
 *         payout amount is zero and the success path ran inline.
 */
@Transactional(propagation = Propagation.MANDATORY)
public Optional<TerminalCommand> queuePayout(Escrow escrow) {
    if (escrow.getPayoutAmt() != null && escrow.getPayoutAmt() == 0L) {
        if (escrow.getState() == EscrowState.COMPLETED) {
            // Idempotent replay: a previous call already ran the inline
            // success path. No-op so we don't double-write the ledger row,
            // double-notify the seller, or double-credit commissions.
            log.info("queuePayout: escrow {} already COMPLETED, no-op", escrow.getId());
            return Optional.empty();
        }
        log.info("queuePayout: escrow {} payoutAmt=0 (case-3), running success path inline",
                escrow.getId());
        runZeroPayoutSuccessInline(escrow, OffsetDateTime.now(clock));
        return Optional.empty();
    }
    String recipientUuid = escrow.getAuction().getSeller().getSlAvatarUuid().toString();
    TerminalCommand cmd = queue(escrow.getId(), null,
            TerminalCommandAction.PAYOUT, TerminalCommandPurpose.AUCTION_ESCROW,
            recipientUuid, escrow.getPayoutAmt(),
            idempotencyKey("ESC", escrow.getId(), TerminalCommandAction.PAYOUT, 1));
    return Optional.of(cmd);
}
```

Then add the private `runZeroPayoutSuccessInline` method immediately below `handleEscrowPayoutSuccess`. It mirrors `handleEscrowPayoutSuccess`'s post-payout work minus the bot/LSL plumbing (no `TerminalCommand` row to find, no SL transaction key, no terminal id):

```java
/**
 * Sub-project G §8.1 -- post-payout success path for the case-3 zero-payout
 * branch. Mirrors {@link #handleEscrowPayoutSuccess}'s body but is driven
 * directly from {@link #queuePayout} (no terminal callback because no
 * terminal round-trip happened). Writes the {@code AUCTION_ESCROW_PAYOUT}
 * ledger row with amount=0, transitions the escrow to COMPLETED, bumps the
 * seller's completedSales counter, broadcasts the EscrowCompletedEnvelope
 * after commit, notifies the seller, and invokes
 * {@link AgentCommissionDistributor#distribute} so the agent slice and group
 * slice land in their respective wallets.
 *
 * <p>Idempotency is enforced by the caller -- {@link #queuePayout} short-
 * circuits when the escrow is already COMPLETED.
 */
private void runZeroPayoutSuccessInline(Escrow escrow, OffsetDateTime now) {
    EscrowService.enforceTransitionAllowed(
            escrow.getId(), escrow.getState(), EscrowState.COMPLETED);
    escrow.setState(EscrowState.COMPLETED);
    escrow.setCompletedAt(now);
    escrow = escrowRepo.save(escrow);

    monitorLifecycle.onEscrowTerminal(escrow);

    User seller = escrow.getAuction().getSeller();
    int prior = seller.getCompletedSales() == null ? 0 : seller.getCompletedSales();
    seller.setCompletedSales(prior + 1);
    userRepo.save(seller);

    // Same shape as the terminal-callback path; amount = 0, no slTxn, no
    // terminalId. Reconciliation by type (AUCTION_ESCROW_PAYOUT) still works.
    ledgerRepo.save(EscrowTransaction.builder()
            .escrow(escrow)
            .auction(escrow.getAuction())
            .type(EscrowTransactionType.AUCTION_ESCROW_PAYOUT)
            .status(EscrowTransactionStatus.COMPLETED)
            .amount(0L)
            .payee(escrow.getAuction().getSeller())
            .completedAt(now)
            .build());
    ledgerRepo.save(EscrowTransaction.builder()
            .escrow(escrow)
            .auction(escrow.getAuction())
            .type(EscrowTransactionType.AUCTION_ESCROW_COMMISSION)
            .status(EscrowTransactionStatus.COMPLETED)
            .amount(escrow.getCommissionAmt())
            .completedAt(now)
            .build());

    final Escrow finalEscrow = escrow;
    final EscrowCompletedEnvelope env = EscrowCompletedEnvelope.of(finalEscrow, now);
    registerAfterCommit(() -> broadcastPublisher.publishCompleted(env));

    // Seller payout notification; Task 24 tweaks the body copy so case-3
    // doesn't say "L$0 payout received". Amount is still 0 here -- the
    // builder decides the body string based on realtyGroupId.
    notificationPublisher.escrowPayout(
            finalEscrow.getAuction().getSeller().getId(),
            finalEscrow.getAuction().getId(),
            finalEscrow.getId(),
            finalEscrow.getAuction().getTitle(),
            0L);

    // Case-3 distributor: credits agent_slice to the listing agent's wallet,
    // group_slice to the group wallet. Both flow through here because
    // payoutAmt = 0 meant no L$ left SLPA via the terminal. By construction
    // (Sub-project E spec §9.6 post-G), case-3 is the only branch that
    // reaches this method.
    if (finalEscrow.getAuction().getRealtyGroupSlGroupId() != null) {
        agentCommissionDistributor.distribute(
            finalEscrow.getAuction(),
            finalEscrow.getFinalBidAmount(),
            finalEscrow.getCommissionAmt());
    }
}
```

- [ ] **Step 4: Fix the caller in `EscrowService`**

Edit `backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowService.java`. The current call (line 443-444) is:

```java
void queuePayoutOnConfirm(Escrow escrow) {
    terminalCommandService.queuePayout(escrow);
}
```

It already discards the return value, so the source-level change is just a Javadoc clarification (no signature break). Update the Javadoc comment on the method to reflect the new shape:

```java
/**
 * Delegates to {@link TerminalCommandService#queuePayout} once the
 * ownership monitor confirms the seller has transferred the parcel to
 * the winner. For non-zero payouts (case-1 / individual), the state flip
 * from {@code TRANSFER_PENDING} to {@code COMPLETED} is owned by the
 * callback path in {@code TerminalCommandService.applyCallback}, not this
 * hook -- queuing the command merely schedules the terminal POST.
 *
 * <p>Sub-project G §8.1: for case-3 (SL-group-owned, payoutAmt = 0),
 * {@code queuePayout} short-circuits and runs the success path inline,
 * returning {@link Optional#empty()}. The state flip to {@code COMPLETED}
 * has already happened by the time this method returns. We discard the
 * return value either way -- the contract is fire-and-forget; the caller
 * doesn't care which branch ran.
 */
void queuePayoutOnConfirm(Escrow escrow) {
    terminalCommandService.queuePayout(escrow);
}
```

Audit other call sites with `Grep`:

```bash
# expect: TerminalCommandService.java (the definition) + EscrowService.java
# (the caller) + the new test class + possibly an existing test that asserts
# the old return type.
```

The implementer subagent must run `Grep` for `queuePayout` and update every caller. The only production caller is `EscrowService.queuePayoutOnConfirm`; any test that asserts on the return type needs its assertion updated to `Optional<TerminalCommand>`.

- [ ] **Step 5: Run the tests to confirm they pass**

```bash
./mvnw test -Dtest=TerminalCommandServiceZeroPayoutTest
./mvnw test -Dtest='TerminalCommandService*Test,EscrowService*Test'
```

Expected: green. The whole-package run catches any test that touched `queuePayout`'s old return shape.

- [ ] **Step 6: Run the full test suite as a safety net**

```bash
./mvnw test
```

Expected: green.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/escrow/command/TerminalCommandService.java \
        backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowService.java \
        backend/src/test/java/com/slparcelauctions/backend/escrow/command/TerminalCommandServiceZeroPayoutTest.java
git commit -m "feat(realty-g): TerminalCommandService.queuePayout zero-payout early-return + runZeroPayoutSuccessInline"
```

---

## Task 23: LSL `slpa-terminal.lsl` graceful $0 PAYOUT handler + README update [parallel-safe]

**Spec ref:** §8.2.

**Background:** Post-Task 22 the backend never enqueues a $0 PAYOUT command. But a stale command produced before the backend deploy can still arrive in-world; the current LSL rejects `amount <= 0` with a 400 (line 662-665) and never POSTs to `/payout-result`, leaving the backend command stuck in `QUEUED` until the dispatcher's stall window. Better: ack the command as success with a `skipped-zero-amount` memo so the backend's COMPLETED-via-callback path clears it on the very next callback round-trip.

LSL has no automated test framework. Verification is read-back of the diff in the PR + a manual in-world deploy step in the PR body.

**Files:**
- Modify: `lsl-scripts/slpa-terminal/slpa-terminal.lsl`
- Modify: `lsl-scripts/slpa-terminal/README.md`

- [ ] **Step 1: Locate the PAYOUT command handler**

The handler lives in the `http_request` event around lines 637–680. The current shape:

```lsl
// Lines 648-665, abridged:
string action      = llJsonGetValue(body, ["action"]);
string ikey        = llJsonGetValue(body, ["idempotencyKey"]);
string recipient   = llJsonGetValue(body, ["recipientUuid"]);
integer amount     = (integer)llJsonGetValue(body, ["amount"]);

if (action == "REFUND") { ... return; }

if (recipient == JSON_INVALID || amount <= 0) {
    llHTTPResponse(id, 400, "{\"error\":\"missing recipient or amount\"}");
    return;
}
```

`amount <= 0` is folded into the missing-recipient guard today. We need to split it out into a leading guard that posts a success result back via `/payout-result`, so the backend's COMPLETED-via-callback path clears the stale command.

The script does not have a `postPayoutResult` helper. The POST to `PAYOUT_RESULT_URL` is inlined in the `transaction_result` event (line 711). For Task 23 we replicate the same wire shape inline -- the same JSON body with `success:true` and a sentinel `slTransactionKey` (since no real transfer happened, we use `"0"` to signal "no SL transaction").

- [ ] **Step 2: Edit the LSL script**

Inside the `http_request` event, just before the `if (recipient == JSON_INVALID || amount <= 0)` guard, insert the new leading guard. Use the existing `escapeJson(...)` helper that the script already has.

```lsl
// Sub-project G §8.2 -- graceful skip for stale $0 PAYOUT commands. After
// the backend's runZeroPayoutSuccessInline short-circuit ships, backend
// never emits amount=0; but a command queued before the deploy can still
// arrive. Ack as success so the backend clears the command on the
// callback path rather than letting it stall.
if (action == "PAYOUT" && amount <= 0) {
    llOwnerSay("SLParcels Terminal: ignoring 0-L$ PAYOUT for ikey=" + ikey);
    // Ack receipt to the dispatcher first so it doesn't retry the POST.
    llHTTPResponse(id, 200, "{\"status\":\"ACCEPTED\"}");
    // Post the synthetic success callback. slTransactionKey "0" signals
    // "no SL transaction happened" -- backend's ledger code treats it as
    // an opaque string and stores it on the AUCTION_ESCROW_PAYOUT row.
    string skipBody = "{"
        + "\"idempotencyKey\":\"" + escapeJson(ikey) + "\","
        + "\"success\":true,"
        + "\"slTransactionKey\":\"0\","
        + "\"memo\":\"skipped-zero-amount\","
        + "\"terminalId\":\"" + escapeJson(TERMINAL_ID) + "\","
        + "\"sharedSecret\":\"" + escapeJson(SHARED_SECRET) + "\""
        + "}";
    payoutResultReqId = llHTTPRequest(PAYOUT_RESULT_URL,
        [HTTP_METHOD, "POST",
         HTTP_MIMETYPE, "application/json",
         HTTP_BODY_MAXLENGTH, 16384],
        skipBody);
    return;
}
```

Place the guard immediately after the `if (action == "REFUND") { ... return; }` block and before the existing `if (recipient == JSON_INVALID || amount <= 0)` rejection. The new guard takes the case-3 path; the existing guard continues to handle malformed commands (negative amounts for action != PAYOUT, missing recipient, etc).

The existing `if (recipient == JSON_INVALID || amount <= 0)` line stays as-is. After the Step 2 insertion, a stale PAYOUT with `amount = 0` never reaches the existing guard; a malformed WITHDRAW with `amount <= 0` still falls through to a 400.

The `memo` field is new on the wire. The backend's `PayoutResultRequest` record does not read it (verified by Grep in Step 4 below) -- Jackson tolerates the extra field silently. If the controller is annotated `@JsonIgnoreProperties(ignoreUnknown = false)` anywhere, drop the field; otherwise it's a free human-readable trail.

- [ ] **Step 3: Update `lsl-scripts/slpa-terminal/README.md`**

Append to the "Operations" section's bullet list (after the `SLParcels Terminal: HTTP-in WITHDRAW ...` bullet around line 128):

```markdown
- `SLParcels Terminal: ignoring 0-L$ PAYOUT for ikey=...` — sub-project G §8.2
  graceful-skip behaviour. The backend's post-G `runZeroPayoutSuccessInline`
  path never emits amount=0 PAYOUT commands, but a stale command from before
  the deploy can still arrive in-world. The script acks the command as
  success with memo `skipped-zero-amount` so the backend clears it on the
  next callback round-trip rather than letting it stall. No L$ moves; no
  refund needed.
```

- [ ] **Step 4: Verify the backend tolerates the new `memo` field**

```bash
# expect: a record/class definition without explicit unknown-property rejection
```

Use the Grep tool: search `PayoutResultRequest` in `backend/src/main/java/com/slparcelauctions/backend/escrow/command/dto/`. The record's wire shape is `{idempotencyKey, success, slTransactionKey, errorMessage, terminalId, sharedSecret}`. Jackson's default is `ignoreUnknown = true` for records (`FAIL_ON_UNKNOWN_PROPERTIES = false` in Spring Boot's default Jackson config). Confirm no class-level `@JsonIgnoreProperties(ignoreUnknown = false)` anywhere in the request type's package. If the implementer finds an explicit failure-on-unknown setting, drop the `"memo"` JSON field from the LSL body in Step 2; the rest of the behaviour stays.

- [ ] **Step 5: Read back the diff (no automated test for LSL)**

```bash
git diff lsl-scripts/slpa-terminal/slpa-terminal.lsl
git diff lsl-scripts/slpa-terminal/README.md
```

Expected: the leading guard appears in the right spot, the existing `recipient == JSON_INVALID || amount <= 0` rejection is unchanged, the README bullet is in place. The PR body must include a manual deploy step (drag the updated `.lsl` into the prim at every SLParcels venue + reset the script).

- [ ] **Step 6: Commit**

```bash
git add lsl-scripts/slpa-terminal/slpa-terminal.lsl lsl-scripts/slpa-terminal/README.md
git commit -m "feat(realty-g): slpa-terminal graceful \$0 PAYOUT skip via /payout-result callback"
```

---

## Task 24: Seller payout notification body — case-3 tweak [parallel-safe]

**Spec ref:** §8.3.

**Background:** Today `NotificationPublisherImpl.escrowPayout` composes the body unconditionally as `"Payout received: L$<payoutAmt>" + " — " + "<parcelName> escrow completed successfully."`. For case-3, `payoutAmt = 0`, producing a misleading "Payout received: L$0" subject + body. We need the subject to stay informative for both cases and the body to surface the case-3-relevant numbers (commission amount + group slice + group name).

There is no `EscrowPayoutNotificationBuilder` class -- the body composition lives inline in `NotificationPublisherImpl.escrowPayout` (lines 194-204). The data builder helper is `NotificationDataBuilder.escrowPayout`.

The publisher's existing signature is:

```java
void escrowPayout(long sellerUserId, long auctionId, long escrowId,
                  String parcelName, long payoutL);
```

For case-3 we need to know the realty group's name + the commission amount + the group slice amount. Three options for plumbing:

1. **Extend the publisher signature** with new params (group name + commissionAmt + groupSliceAmt), pass nulls/zeros for the non-case-3 callers.
2. **Re-fetch inside the publisher** via injected repos (Auction → RealtyGroup → name; commission slice = `escrow.commissionAmt`; group slice = `escrow.finalBidAmount - escrow.commissionAmt`).
3. **Pass an `EscrowPayoutContext` record** with all the fields the body composer needs.

Option (1) is the lightest. The publisher's signature already has `payoutL` for the case-1/individual path; adding three nullable params keeps the wire shape stable.

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisher.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisherImpl.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/command/TerminalCommandService.java` (both callers — the existing `handleEscrowPayoutSuccess` from Task 22 and the new `runZeroPayoutSuccessInline`)
- Modify: any test that asserts the seller payout notification body

- [ ] **Step 1: Write a failing test**

Test file: `backend/src/test/java/com/slparcelauctions/backend/notification/NotificationPublisherImplCase3PayoutBodyTest.java`

```java
package com.slparcelauctions.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationPublisherImplCase3PayoutBodyTest {

    @Mock NotificationService notificationService;
    @InjectMocks NotificationPublisherImpl publisher;

    @Test
    void case3_body_uses_commission_and_group_slice_copy() {
        publisher.escrowPayout(
            42L, 100L, 200L, "Forest Cove",
            0L,                              // payoutL = 0 for case-3
            "Sunset Realty",                 // groupName (non-null = case-3)
            150L,                            // commissionAmt
            850L);                           // groupSliceAmt

        ArgumentCaptor<NotificationEvent> captor =
            ArgumentCaptor.forClass(NotificationEvent.class);
        verify(notificationService).publish(captor.capture());

        NotificationEvent evt = captor.getValue();
        assertThat(evt.body()).isEqualTo(
            "Your auction payout is complete. L$150 agent commission paid; "
            + "L$850 credited to Sunset Realty group wallet.");
        assertThat(evt.body()).doesNotContain("L$0");
        assertThat(evt.title()).startsWith("Payout"); // subject unchanged
    }

    @Test
    void non_case3_body_uses_legacy_copy() {
        publisher.escrowPayout(
            42L, 100L, 200L, "Forest Cove",
            1000L,                           // payoutL > 0
            null,                            // groupName null = non-case-3
            0L, 0L);

        ArgumentCaptor<NotificationEvent> captor =
            ArgumentCaptor.forClass(NotificationEvent.class);
        verify(notificationService).publish(captor.capture());

        NotificationEvent evt = captor.getValue();
        assertThat(evt.body()).contains("Forest Cove");
        assertThat(evt.body()).contains("escrow completed successfully");
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails**

```bash
./mvnw test -Dtest=NotificationPublisherImplCase3PayoutBodyTest
```

Expected: compile failure (new method signature) or method-resolution failure. Either is a valid "red".

- [ ] **Step 3: Extend the publisher interface**

Edit `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisher.java`. Replace the existing `escrowPayout` signature with the case-3-aware shape:

```java
/**
 * Seller-facing payout-completed notification.
 *
 * <p>Sub-project G §8.3: copy differs by case. For case-3 (SL-group-owned;
 * {@code groupName != null}) the body surfaces the commission slice and
 * group slice instead of "L$0 payout received". For case-1 / individual
 * the body is the legacy "payout received" copy.
 *
 * @param payoutL           L$ paid to the seller (0 for case-3)
 * @param groupName         realty group display name, or {@code null} for
 *                          non-case-3
 * @param commissionAmt     L$ credited to the listing agent's wallet (case-3 only;
 *                          ignored when {@code groupName == null})
 * @param groupSliceAmt     L$ credited to the group wallet (case-3 only;
 *                          ignored when {@code groupName == null})
 */
void escrowPayout(long sellerUserId, long auctionId, long escrowId,
                  String parcelName, long payoutL,
                  String groupName, long commissionAmt, long groupSliceAmt);
```

- [ ] **Step 4: Update the implementation**

Edit `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisherImpl.java`:

```java
@Override
public void escrowPayout(long sellerUserId, long auctionId, long escrowId,
                          String parcelName, long payoutL,
                          String groupName, long commissionAmt, long groupSliceAmt) {
    String title;
    String body;
    if (groupName != null) {
        // Sub-project G §8.3 -- case-3 (SL-group-owned). payoutL is 0
        // by construction; report the slices instead so the seller sees
        // the actual L$ movement.
        title = "Auction payout processed: " + parcelName;
        body = String.format(
            "Your auction payout is complete. L$%d agent commission paid; "
            + "L$%d credited to %s group wallet.",
            commissionAmt, groupSliceAmt, groupName);
    } else {
        // Legacy / case-1 / individual.
        title = String.format("Payout received: L$%,d", payoutL);
        body = parcelName + " escrow completed successfully.";
    }
    notificationService.publish(new NotificationEvent(
        sellerUserId, NotificationCategory.ESCROW_PAYOUT, title, body,
        NotificationDataBuilder.escrowPayout(auctionId, escrowId, parcelName, payoutL),
        null
    ));
}
```

The data builder payload doesn't change shape -- frontend consumers keep reading `payoutL`. Case-3 just renders as 0 in the data blob, which is fine because the human-readable body already explains the slice numbers.

- [ ] **Step 5: Update both `TerminalCommandService` callers**

The two callers that invoke `notificationPublisher.escrowPayout`:

(a) `handleEscrowPayoutSuccess` (line 313 today, pre-Task 22). For case-1 / individual the new params are `null, 0L, 0L`. For the case where `realtyGroupSlGroupId != null` (the case-3 callback path, which post-Task 22 should never actually fire because case-3 takes the inline branch), still pass case-3 params so the body is correct if a stale command callback arrives. Resolve `groupName` from the auction's `RealtyGroup`:

```java
String groupName = null;
long commissionAmt = 0L;
long groupSliceAmt = 0L;
if (finalEscrow.getAuction().getRealtyGroupSlGroupId() != null) {
    RealtyGroup g = groupRepo.findById(finalEscrow.getAuction().getRealtyGroupId())
            .orElseThrow();
    groupName = g.getName();
    commissionAmt = finalEscrow.getCommissionAmt();
    groupSliceAmt = finalEscrow.getFinalBidAmount() - finalEscrow.getCommissionAmt();
}
notificationPublisher.escrowPayout(
    finalEscrow.getAuction().getSeller().getId(),
    finalEscrow.getAuction().getId(),
    finalEscrow.getId(),
    finalEscrow.getAuction().getTitle(),
    cmd.getAmount(),
    groupName, commissionAmt, groupSliceAmt);
```

(b) `runZeroPayoutSuccessInline` (added in Task 22). Always case-3 by construction; same group-name resolution + slice arithmetic. The implementer should extract the resolution into a shared private helper to avoid duplication, e.g.:

```java
private record SellerNotifyExtras(String groupName, long commissionAmt, long groupSliceAmt) {}

private SellerNotifyExtras resolveSellerNotifyExtras(Escrow escrow) {
    if (escrow.getAuction().getRealtyGroupSlGroupId() == null) {
        return new SellerNotifyExtras(null, 0L, 0L);
    }
    RealtyGroup g = groupRepo.findById(escrow.getAuction().getRealtyGroupId())
            .orElseThrow();
    long commissionAmt = escrow.getCommissionAmt();
    long groupSliceAmt = escrow.getFinalBidAmount() - commissionAmt;
    return new SellerNotifyExtras(g.getName(), commissionAmt, groupSliceAmt);
}
```

Both call sites then read from `resolveSellerNotifyExtras(escrow)`.

`TerminalCommandService` already injects `groupWalletWithdrawalCallbackHandler`; if `RealtyGroupRepository` isn't already a field, add it (`@Lombok @RequiredArgsConstructor` handles wiring).

- [ ] **Step 6: Sweep every other `notificationPublisher.escrowPayout(...)` call site**

```bash
# expect: only the two callers in TerminalCommandService. Each needs the new
# trailing params.
```

Use the Grep tool. Mock-based unit tests that stub the publisher don't need updates beyond their argument matchers (e.g. `verify(publisher).escrowPayout(anyLong(), ...)` continues to work — the matcher list grows by three). Any explicit-argument verify needs the new positional args.

- [ ] **Step 7: Run the tests**

```bash
./mvnw test -Dtest=NotificationPublisherImplCase3PayoutBodyTest
./mvnw test
```

Expected: targeted test green + full suite green.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisher.java \
        backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisherImpl.java \
        backend/src/main/java/com/slparcelauctions/backend/escrow/command/TerminalCommandService.java \
        backend/src/test/java/com/slparcelauctions/backend/notification/NotificationPublisherImplCase3PayoutBodyTest.java
git commit -m "feat(realty-g): seller payout notification body — case-3 commission + group-slice copy"
```

---

## Task 25: Spec §9.6 sync in E design doc [parallel-safe]

**Spec ref:** §8.4.

**Background:** The E spec at `docs/superpowers/specs/2026-05-12-realty-groups-sl-group-listing-design.md` §9.6 still describes the pre-G distributor wiring -- "subtracts agent_slice from payoutAmt" and the dual `agentCommissionDistributor` / `agentFeeDistributor` branch. After Sub-project G §4 (Part 1) deletes `AgentFeeDistributor` entirely, the case-1 branch disappears; after Task 22 the case-3 branch runs through `runZeroPayoutSuccessInline` rather than the terminal-callback `handleEscrowPayoutSuccess`. The spec sentence in §9.6 is now stale and contradicts the code that will land in this PR.

This task is a docs-only edit. No tests, no commit-ordering subtlety -- the spec sync rides along with the code so future readers don't see contradictory docs.

**Files:**
- Modify: `docs/superpowers/specs/2026-05-12-realty-groups-sl-group-listing-design.md` (§9.6 only)

- [ ] **Step 1: Read the current §9.6 wording**

The block starts at line 437 with the header `### 9.6 Distributor wiring` and continues through ~line 447. Current text shows the dual-branch wiring + the "subtracts agent_slice" sentence.

- [ ] **Step 2: Rewrite §9.6 to match post-G implementation**

Replace the §9.6 body with the following:

```markdown
### 9.6 Distributor wiring

For case 3, `payoutAmt = 0`. The agent slice and the group slice both flow
through `AgentCommissionDistributor` inside the payout-success path -- see
`TerminalCommandService.runZeroPayoutSuccessInline` (sub-project G §8.1) for
the post-G flow. The terminal round-trip is skipped because no L$ leaves
SLPA in-world; both wallet credits happen entirely inside backend.

```java
// Sub-project G §8.1, inside TerminalCommandService.runZeroPayoutSuccessInline:
if (auction.getRealtyGroupSlGroupId() != null) {
    agentCommissionDistributor.distribute(
        auction, finalBidAmount, commissionAmt);  // credits agent_slice + group_slice
}
```

Case-1 has been removed by sub-project G §4 (`AgentFeeDistributor` deleted; the
group-listed-not-SL-group-owned branch no longer exists). All realty-group
listings post-G are case 3.

`EscrowService.createForEndedAuction` sets `payoutAmt = 0` for case 3 at escrow
creation time (unchanged from E). The success-path inline branch in
`TerminalCommandService.queuePayout` is what triggers
`AgentCommissionDistributor`; the terminal callback path is reserved for
non-case-3 escrows.
```

The new text references `runZeroPayoutSuccessInline` by name so future readers can `git grep` from the spec back to the implementation, and explicitly notes that case-1 is gone post-G.

- [ ] **Step 3: Verify the doc still renders well**

No tooling for this; just read the section + the adjacent §9.5 + §10 to confirm flow.

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/specs/2026-05-12-realty-groups-sl-group-listing-design.md
git commit -m "docs(realty-g): spec §9.6 sync — point at runZeroPayoutSuccessInline post-G"
```

---

## Task 26: F polish bundle — `SystemUserResolver` field + reverify batch-size + `@Transactional` audit [parallel-safe]

**Spec ref:** §9 (sub-steps §9.1, §9.2, §9.3).

**Background:** Three small cleanups F shipped without. Treated as one task with three commit-able sub-steps. The sub-steps are pairwise file-disjoint, so the implementer subagent may parallel them internally if it wants, but they're presented sequentially below because they're each cheap.

**Files (all sub-steps combined):**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/monitoring/BulkSuspendedListingExpiryTask.java`
- Modify: associated test for the task (drop the `SystemUserResolver` mock if injected)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/moderation/RealtyGroupModerationProperties.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/SlGroupReverifyTask.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/RealtyGroupSlGroupRepository.java` (if `findDueForReverify` needs a `Pageable` variant)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/moderation/AdminRealtyGroupSuspensionController.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/moderation/AdminRealtyGroupBulkListingsController.java`
- Modify: one or more of the services those controllers delegate to (`RealtyGroupSuspensionService`, `BulkListingSuspendService`)
- Modify: `docs/implementation/CONVENTIONS.md`

### 26a — Drop `SystemUserResolver` from `BulkSuspendedListingExpiryTask`

The field exists at line 78 of `BulkSuspendedListingExpiryTask.java`:

```java
@SuppressWarnings("unused") // injected for parity with the plan + future system-actor hooks
private final SystemUserResolver systemUserResolver;
```

It's never read. The actual system-actor attribution happens inside `AdminActionService.recordSystemAction`. The field, its constructor param (synthesised by Lombok `@RequiredArgsConstructor`), and any test fixture that injects a `SystemUserResolver` mock need to go.

- [ ] **Step 1: Edit `BulkSuspendedListingExpiryTask`**

Delete:
- The `import com.slparcelauctions.backend.admin.audit.SystemUserResolver;` line at the top
- The `private final SystemUserResolver systemUserResolver;` line (and its `@SuppressWarnings`)
- The "injected for parity with the plan + future system-actor hooks" Javadoc bullet (currently the last paragraph of the class Javadoc, starting "The {@link SystemUserResolver} is injected for future use ...")

- [ ] **Step 2: Update tests**

```bash
# expect: a unit test that constructs the task and may pass a SystemUserResolver mock
```

Find the test file (likely `backend/src/test/java/com/slparcelauctions/backend/auction/monitoring/BulkSuspendedListingExpiryTaskTest.java`) and:
- Drop any `@Mock SystemUserResolver ...` field
- Drop the mock from any constructor invocation
- Drop any `@Spy`/`@InjectMocks` reference

If the test uses constructor injection through Mockito's `@InjectMocks`, removing the field is enough; Mockito wires the constructor automatically.

- [ ] **Step 3: Run the test**

```bash
./mvnw test -Dtest=BulkSuspendedListingExpiryTaskTest
```

Expected: green.

### 26b — `slpa.realty.sl-group.reverify-batch-size` property + `SlGroupReverifyTask` batch cap

Today `SlGroupReverifyTask.runOnce` (line 60) fetches `repo.findDueForReverify(threshold)` unbounded. At low cardinality this is fine; at higher cardinality a single sweep could starve other transactions for the same connection. We add a per-tick cap, default `Integer.MAX_VALUE` (effectively unbounded -- matches today's behaviour).

- [ ] **Step 1: Extend `RealtyGroupModerationProperties.SlGroupReverify`**

Edit `backend/src/main/java/com/slparcelauctions/backend/realty/moderation/RealtyGroupModerationProperties.java`. Add a `reverifyBatchSize` field to the nested class:

```java
@Getter
@Setter
public static class SlGroupReverify {
    private int reverifyCadenceDays = 30;
    private int reverifyFetchFailureThreshold = 3;
    /**
     * Sub-project G §9.2 -- per-tick cap on the number of due rows fetched
     * by {@link com.slparcelauctions.backend.realty.slgroup.SlGroupReverifyTask}.
     * Default {@link Integer#MAX_VALUE} (effectively unbounded, matching the
     * pre-G behaviour). Operators dial down via
     * {@code slpa.realty.sl-group.reverify-batch-size} + redeploy if a sweep
     * starts starving other transactions on the same connection pool.
     */
    @jakarta.validation.constraints.Min(1)
    private int reverifyBatchSize = Integer.MAX_VALUE;

    private Enabled reverify = new Enabled();

    @Getter
    @Setter
    public static class Enabled {
        private boolean enabled = true;
    }
}
```

The property binds as `slpa.realty.sl-group.reverify-batch-size` (Spring's relaxed-binding picks up `reverifyBatchSize` from camelCase).

- [ ] **Step 2: Add a paginated repository query**

Check `RealtyGroupSlGroupRepository.findDueForReverify` -- it currently returns `List<RealtyGroupSlGroup>` taking only `OffsetDateTime threshold`. Add a `Pageable` overload:

```java
@Query("""
    SELECT g FROM RealtyGroupSlGroup g
     WHERE g.verified = TRUE
       AND g.unregisteredAt IS NULL
       AND (g.lastRevalidatedAt IS NULL OR g.lastRevalidatedAt < :threshold)
     ORDER BY g.lastRevalidatedAt ASC NULLS FIRST, g.id ASC
    """)
List<RealtyGroupSlGroup> findDueForReverify(
        @org.springframework.data.repository.query.Param("threshold") OffsetDateTime threshold,
        org.springframework.data.domain.Pageable pageable);
```

(Keep the existing parameterless overload if other callers use it; the implementer should confirm via Grep. If `SlGroupReverifyTask` is the only caller, replace the existing method's signature in place rather than overloading.)

- [ ] **Step 3: Update `SlGroupReverifyTask.runOnce`**

```java
@Scheduled(fixedRate = 60L * 60L * 1000L)
public void runOnce() {
    OffsetDateTime threshold = OffsetDateTime.now(clock)
            .minusDays(props.getSlGroup().getReverifyCadenceDays());
    int batchSize = props.getSlGroup().getReverifyBatchSize();
    List<RealtyGroupSlGroup> due = repo.findDueForReverify(
            threshold, PageRequest.of(0, batchSize));
    if (due.isEmpty()) {
        return;
    }
    for (RealtyGroupSlGroup row : due) {
        try {
            reverifyService.recheck(row.getId());
        } catch (Exception e) {
            log.warn("Reverify failed for slGroup {}", row.getId(), e);
        }
    }
    log.info("Reverified {} SL groups (threshold={}, cap={})", due.size(), threshold, batchSize);
}
```

Add the import `import org.springframework.data.domain.PageRequest;`.

- [ ] **Step 4: Write a test asserting the cap is respected**

Test file: `backend/src/test/java/com/slparcelauctions/backend/realty/slgroup/SlGroupReverifyTaskBatchSizeTest.java`

```java
package com.slparcelauctions.backend.realty.slgroup;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import com.slparcelauctions.backend.realty.moderation.RealtyGroupModerationProperties;

@ExtendWith(MockitoExtension.class)
class SlGroupReverifyTaskBatchSizeTest {

    @Mock RealtyGroupSlGroupRepository repo;
    @Mock SlGroupReverifyService reverifyService;

    @Test
    void run_once_passes_configured_batch_size_to_repo() {
        RealtyGroupModerationProperties props = new RealtyGroupModerationProperties();
        props.getSlGroup().setReverifyBatchSize(50);

        Clock fixed = Clock.fixed(java.time.Instant.parse("2026-05-12T00:00:00Z"),
                                  ZoneOffset.UTC);
        SlGroupReverifyTask task = new SlGroupReverifyTask(repo, reverifyService, props, fixed);

        when(repo.findDueForReverify(any(OffsetDateTime.class), any())).thenReturn(List.of());

        task.runOnce();

        ArgumentCaptor<org.springframework.data.domain.Pageable> pageableCap =
            ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        verify(repo, times(1)).findDueForReverify(any(OffsetDateTime.class), pageableCap.capture());
        org.springframework.data.domain.Pageable p = pageableCap.getValue();
        org.assertj.core.api.Assertions.assertThat(p.getPageSize()).isEqualTo(50);
        org.assertj.core.api.Assertions.assertThat(p.getPageNumber()).isEqualTo(0);
    }
}
```

- [ ] **Step 5: Run the test**

```bash
./mvnw test -Dtest=SlGroupReverifyTaskBatchSizeTest
```

Expected: green.

### 26c — `@Transactional` boundary audit + CONVENTIONS.md rule

Per spec §9.3: for each `@Transactional`-annotated method on `AdminRealtyGroupSuspensionController` and `AdminRealtyGroupBulkListingsController`, decide based on the number of service calls it makes:

1. **1:1 service call** → move `@Transactional` to the service entrypoint, drop from the controller.
2. **Multi-service composition** → keep `@Transactional` on the controller and add a `// tx spans <svcA>.<methodA> + <svcB>.<methodB>` comment naming the composition.

Reading the current controllers (verified above):

| Method | Service call shape | Decision |
|---|---|---|
| `AdminRealtyGroupSuspensionController.list` | 1:1 → `service.listHistory(publicId)` | Move `@Transactional(readOnly = true)` to `RealtyGroupSuspensionService.listHistory`. |
| `AdminRealtyGroupSuspensionController.issue` | 1:1 → `service.issue(...)` | Move `@Transactional` to `RealtyGroupSuspensionService.issue`. |
| `AdminRealtyGroupSuspensionController.lift` | 1:1 → `service.lift(...)` | Move `@Transactional` to `RealtyGroupSuspensionService.lift`. |
| `AdminRealtyGroupBulkListingsController.suspendAll` | Multi: `groupRepo.findByPublicId(...)` + (cond) `suspensionRepo.findByPublicId(...)` + `bulkService.suspendAll(...)` | Keep on controller. Add `// tx spans RealtyGroupRepository.findByPublicId + RealtyGroupSuspensionRepository.findByPublicId + BulkListingSuspendService.suspendAll`. |
| `AdminRealtyGroupBulkListingsController.reinstateAll` | Multi: `groupRepo.findByPublicId(...)` + `bulkService.reinstateAll(...)` | Keep on controller. Add `// tx spans RealtyGroupRepository.findByPublicId + BulkListingSuspendService.reinstateAll`. |

The implementer should re-verify each row by reading the controller + service before editing (the spec deviation rule in §9.3 says any exception class + method must be documented in the PR-time deviation list).

- [ ] **Step 1: Audit + record decisions**

The implementer runs `Grep` for `@Transactional` in the two controller files and confirms the above table is still accurate. If a service method is not already `@Transactional` (most service methods carry `@Transactional` already as a default convention; check via Grep), the move is purely "drop from controller, no-op at service".

- [ ] **Step 2: Edit `AdminRealtyGroupSuspensionController`**

Drop `@Transactional(readOnly = true)` from `list`, drop `@Transactional` from `issue` and `lift`. Drop the `import org.springframework.transaction.annotation.Transactional;` if no other method uses it.

- [ ] **Step 3: Edit `RealtyGroupSuspensionService` (the three methods)**

Add `@Transactional(readOnly = true)` to `listHistory` and `@Transactional` to `issue` and `lift` if not already present. If they're already present, the move is a no-op at the service and just a delete at the controller.

- [ ] **Step 4: Edit `AdminRealtyGroupBulkListingsController`**

Keep `@Transactional` on both methods. Add a comment immediately above each annotation:

```java
// tx spans RealtyGroupRepository.findByPublicId
//        + RealtyGroupSuspensionRepository.findByPublicId
//        + BulkListingSuspendService.suspendAll
@PostMapping("/suspend-all")
@Transactional
public BulkSuspendResultDto suspendAll(...) { ... }
```

and:

```java
// tx spans RealtyGroupRepository.findByPublicId
//        + BulkListingSuspendService.reinstateAll
@PostMapping("/reinstate-all")
@Transactional
public ReinstateResultDto reinstateAll(...) { ... }
```

- [ ] **Step 5: Append the rule to CONVENTIONS.md**

Edit `docs/implementation/CONVENTIONS.md`. Add a new subsection at the end of the "Backend (Java / Spring Boot)" section, after the existing `## Testing` block (the implementer should find the right anchor; preserve the existing markdown heading hierarchy):

```markdown
### Transaction boundaries

**Default transaction boundary is the service entrypoint.** Controller methods
should not carry `@Transactional` unless they compose multiple service calls in
one transaction. Controllers that DO carry `@Transactional` must add a single
adjacent comment naming the composition:

```java
// tx spans <ServiceA>.<methodA> + <ServiceB>.<methodB>
@Transactional
public ResultDto endpoint(...) { ... }
```

Reviewers reject controller-level `@Transactional` without that comment. The
rule is enforced by review, not tooling — no static-analysis check exists.

The rationale: keeping the boundary at the service makes the transaction's
extent legible from the call site, lets service-layer tests exercise rollback
semantics directly, and prevents accidental "controller-as-orchestrator"
patterns that hide multi-step writes behind a single HTTP endpoint.

Spec cross-link: `docs/superpowers/specs/2026-05-12-realty-groups-final-cleanup-design.md` §9.3
(sub-project G introduced this rule after auditing F's two moderation controllers).
```

- [ ] **Step 6: Run the full test suite**

```bash
./mvnw test
```

Expected: green. Pay attention to any test that asserted rollback at the controller boundary -- those would surface as failures here. None expected on inspection of the current code.

- [ ] **Step 7: Verify with Grep**

```bash
# expect: AdminRealtyGroupBulkListingsController.java (with the // tx spans comment)
#       + RealtyGroupSuspensionService.java + BulkListingSuspendService.java
# NOT expected: AdminRealtyGroupSuspensionController.java
```

Run the Grep tool for `@Transactional` in `backend/src/main/java/com/slparcelauctions/backend/realty/moderation/` + `backend/src/main/java/com/slparcelauctions/backend/auction/monitoring/` (for `BulkListingSuspendService`). Confirm every controller hit has an adjacent `// tx spans` comment.

- [ ] **Step 8: Commit (one commit for all three sub-steps)**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/monitoring/BulkSuspendedListingExpiryTask.java \
        backend/src/test/java/com/slparcelauctions/backend/auction/monitoring/BulkSuspendedListingExpiryTaskTest.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/moderation/RealtyGroupModerationProperties.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/SlGroupReverifyTask.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/RealtyGroupSlGroupRepository.java \
        backend/src/test/java/com/slparcelauctions/backend/realty/slgroup/SlGroupReverifyTaskBatchSizeTest.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/moderation/AdminRealtyGroupSuspensionController.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/moderation/AdminRealtyGroupBulkListingsController.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/moderation/RealtyGroupSuspensionService.java \
        docs/implementation/CONVENTIONS.md
git commit -m "refactor(realty-g): F polish — drop SystemUserResolver field, add reverify-batch-size, move @Transactional"
```

(If `RealtyGroupSuspensionService` already carried `@Transactional` on all three methods, drop it from the staged list — only stage files actually modified.)

---

## Task 27: Section B frontend cleanup — `AgentFeePreview` swap + group profile fee block removal [parallel-safe]

**Spec ref:** §5.

**Background:** Section B has two concrete deliverables:

1. **Component swap.** The listing wizard still branches on `parcelIsSlGroupOwned ? <AgentCommissionPreview> : <AgentFeePreview>` at `frontend/src/components/listing/ListingWizardForm.tsx:362-385`. Post-G case-1 is gone (no legacy "agent listing their own land under a group" flow remains), so the branch collapses to always-`AgentCommissionPreview`. `AgentFeePreview.tsx` + `AgentFeePreview.test.tsx` are deleted.

2. **Group profile page agent-fee-rate block.** Two leader/admin-side group profile forms still render the `Agent fee rate` + `Agent fee split` inputs:
   - `frontend/src/components/realty/GroupProfileForm.tsx` (leader-side, lines 49–51, 111–112, 127–128, 140–141, 308–314 — the `agentFeeRate` / `agentFeeSplit` inputs)
   - `frontend/src/components/admin/realty-groups/AdminGroupProfileForm.tsx` (admin-side, lines 34–35, 68–69, 78–79, 90–91, 155–170 — same shape)
   Both submit these fields through the `update` API, which Part 1 Task 6 already strips from `UpdateRealtyGroupRequest`. The frontend inputs become orphaned. Remove them.

The public profile page at `frontend/src/app/group/[slug]/page.tsx` does NOT currently render an `agentFeeRate` line (verified). The spec's "group profile page" reference covers the two profile-edit forms above.

**Files:**
- Delete: `frontend/src/components/listing/AgentFeePreview.tsx`
- Delete: `frontend/src/components/listing/AgentFeePreview.test.tsx`
- Modify: `frontend/src/components/listing/ListingWizardForm.tsx`
- Modify: `frontend/src/components/listing/ListingWizardForm.test.tsx`
- Modify: `frontend/src/components/realty/GroupProfileForm.tsx`
- Modify: `frontend/src/components/realty/GroupProfileForm.test.tsx`
- Modify: `frontend/src/components/admin/realty-groups/AdminGroupProfileForm.tsx`
- Modify: `frontend/src/components/admin/realty-groups/AdminGroupProfileForm.test.tsx` (if it asserts on the dropped inputs)

- [ ] **Step 1: Sweep for current `AgentFeePreview` usage**

```bash
# expect: ListingWizardForm.tsx (the consumer) + the component + its test +
# the wizard test asserting on its render.
```

Use the Grep tool for `AgentFeePreview` across `frontend/src/`. Confirm the only production consumer is `ListingWizardForm`.

- [ ] **Step 2: Write failing tests for the post-swap wizard behaviour**

Add to `frontend/src/components/listing/ListingWizardForm.test.tsx` (or modify the existing case-1/case-3 branch assertion). The expected new shape: when an eligible group is selected and `startingBid > 0`, the wizard renders `<AgentCommissionPreview>` *regardless* of whether the parcel is SL-group-owned:

```ts
it("renders AgentCommissionPreview for any eligible-group selection (post-G — case-1 removed)", async () => {
  // Set up a non-SL-group-owned parcel (parcelIsSlGroupOwned = false), pick a group,
  // set startingBid > 0, assert the AgentCommissionPreview component is in the DOM
  // and AgentFeePreview is NOT.
});
```

Run the test to confirm it fails (because `AgentFeePreview` still renders for the non-SL-group case today).

```bash
cd frontend && npm test -- ListingWizardForm
```

Expected: the new test fails.

- [ ] **Step 3: Edit `ListingWizardForm.tsx`**

Delete the import:

```tsx
import { AgentFeePreview } from "./AgentFeePreview";
```

Collapse the branch (lines 362-385). Before:

```tsx
{selectedGroup && draft.state.startingBid > 0 && (
  parcelIsSlGroupOwned ? (
    <AgentCommissionPreview ... />
  ) : (
    // Legacy case 1: agent listing their own land under
    // a group. Group takes a flat agent-fee slice off
    // the seller's payout.
    <AgentFeePreview
      startingBid={draft.state.startingBid}
      groupName={selectedGroup.name}
      agentFeeRate={selectedGroup.agentFeeRate}
      groupPublicId={selectedGroup.publicId}
      onInsufficient={setGroupWalletInsufficient}
    />
  )
)}
```

After:

```tsx
{selectedGroup && draft.state.startingBid > 0 && (
  <AgentCommissionPreview
    startingBid={draft.state.startingBid}
    groupName={selectedGroup.name}
    groupPublicId={selectedGroup.publicId}
    onInsufficient={setGroupWalletInsufficient}
  />
)}
```

The `parcelIsSlGroupOwned` local is still used by `ListAsGroupPicker`'s `showIndividual` prop (line 360) -- don't remove it, just the branch above. After Part 1 Task 6 strips `selectedGroup.agentFeeRate` from the type, the `agentFeeRate={...}` line would be a compile error anyway, so this deletion is mandatory.

- [ ] **Step 4: Delete `AgentFeePreview.tsx` + its test**

```bash
git rm frontend/src/components/listing/AgentFeePreview.tsx \
       frontend/src/components/listing/AgentFeePreview.test.tsx
```

- [ ] **Step 5: Edit `GroupProfileForm.tsx` (leader-side)**

Remove every `agentFeeRate` / `agentFeeSplit` reference:
- Lines 49-51 in the Zod schema: drop the two `decimalString` lines.
- Lines 111-112 (`defaultValues`): drop both.
- Lines 127-128 (submit payload): drop both.
- Lines 140-141 (reset after save): drop both.
- Lines ~300-314 (the two `<Input>` blocks): delete the wrapper `<div>` and its two children.
- The Javadoc bullet at line 71 (`* - agentFeeRate/agentFeeSplit -> {@code CONFIGURE_FEES}`): drop the line.

If the file imports `decimalString` solely for these fields, the import becomes unused -- drop it if so (the implementer should check via Grep within the file).

- [ ] **Step 6: Edit `AdminGroupProfileForm.tsx`**

Symmetric edit:
- Lines 34-35 schema → drop.
- Lines 68-69 `defaultValues` → drop.
- Lines 78-79 submit payload → drop.
- Lines 90-91 reset → drop.
- Lines 155-170 (the two-column grid wrapping `Agent fee rate` + `Agent fee split` inputs) → drop the wrapping `<div>` and its children.

- [ ] **Step 7: Update tests + Vitest snapshots**

For each modified component, the corresponding `*.test.tsx` likely renders the form and asserts on input presence. Inspect:

- `frontend/src/components/realty/GroupProfileForm.test.tsx` -- drop any `data-testid="...-fee-rate"` / `...-fee-split` assertions.
- `frontend/src/components/admin/realty-groups/AdminGroupProfileForm.test.tsx` -- same.
- `frontend/src/components/listing/ListingWizardForm.test.tsx` -- swap the case-1-asserting cases over to expect `AgentCommissionPreview` unconditionally. Mock fixtures around line 522 / 569 / 609 / 663 still carry `agentFeeRate: 0.02` on the eligible-group fixture; after Task 12 (Part 2) drops `agentFeeRate` from `ListingEligibleGroupDto` and replaces with `agentCommissionRate`, those fixtures need updating too. Coordinate with Task 12's fixture work -- the implementer for Task 27 should re-Grep for `agentFeeRate` in `frontend/src/` after Task 12 lands, to confirm no orphan references remain.

If any test relies on a Vitest snapshot of the affected components, regenerate the snapshot:

```bash
cd frontend && npm test -- -u GroupProfileForm AdminGroupProfileForm ListingWizardForm
```

(Use `-u` only after confirming the visible diff in the snapshot matches the intended change.)

- [ ] **Step 8: Run frontend verify**

```bash
cd frontend && npm run verify
```

`npm run verify` runs lint + the project's guard scripts + coverage. Any orphan `agentFeeRate` reference surfaces as a TS error (Part 1 Task 8 dropped the field from `RealtyGroupPublic`; Part 2 Task 12 dropped it from `ListingEligibleGroupDto`). Expected: green.

- [ ] **Step 9: Final sweep**

```bash
# expect: zero hits in frontend/src/ for AgentFeePreview after deletion.
```

Use the Grep tool for `AgentFeePreview` across `frontend/src/` -- the result should be empty. Repeat for `agentFeeRate` within `frontend/src/` excluding `auction.ts` (the auction type retains the field as historical / response-time data for closed pre-G auctions) and any test fixtures the implementer deliberately keeps to assert backward-compat read behaviour. The expected non-deletion targets:
- `frontend/src/types/auction.ts` lines 141 + 247 (`agentFeeRate?: number | null` on closed auctions) — keep, this is read-side history.
- Test fixtures that exercise pre-G read paths.

All admin/leader profile-edit usages should be gone.

- [ ] **Step 10: Commit**

```bash
git add -A frontend/
git commit -m "refactor(realty-g): replace AgentFeePreview with AgentCommissionPreview, drop fee-rate inputs from group profile forms"
```

---
