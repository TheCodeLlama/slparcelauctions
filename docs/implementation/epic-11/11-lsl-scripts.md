# Phase 11: LSL Scripts (In-World Objects)

> **Before starting:** read [CONVENTIONS.md](../CONVENTIONS.md) for project-wide rules (no new migrations, Lombok required, vertical slices, feature-based packages).

_Reference: DESIGN.md Sections 5.1, 5.2, 5.3, 9_

---

## Goal

Three in-world LSL scripts for Second Life: a player verification terminal, an escrow payment terminal, and a rezzable parcel verifier object. These are the in-world side of the platform that communicate with the Java backend via HTTP.

---

## What Needs to Happen

### Verification Terminal (5.1)

- Scripted object that players touch to verify their SLPA account
- On touch: prompt for 6-digit verification code via `llTextBox()`
- Gather avatar data: UUID (`llDetectedKey`), legacy name (`llDetectedName`), display name (`llGetDisplayName`), username (`llGetUsername`), account age (`llRequestAgentData` with `DATA_BORN`), payment info (`DATA_PAYINFO`)
- POST all data to backend endpoint via `llHTTPRequest`
- SL automatically injects `X-SecondLife-Owner-Key`, `X-SecondLife-Shard`, `X-SecondLife-Region` headers
- Display success/failure result to the user
- Should display floating text when idle indicating its purpose

### Escrow Terminal (5.2)

- Scripted object that receives L$ payments and sends payouts
- Must have `PERMISSION_DEBIT` granted (for payouts via `llTransferLindenDollars`)
- Receiving payments: `money()` event captures payer UUID and amount, POSTs to backend
- Sending payouts: receives commands from backend via HTTP-in (`llRequestURL`)
- `llTransferLindenDollars` for outgoing payouts with `transaction_result` event for confirmation
- Report transaction results back to backend
- Re-register HTTP-in URL on region restart (`changed(CHANGED_REGION_START)`)
- Authenticate incoming HTTP-in requests with a shared secret
- Handle unexpected payments (POST to backend, which decides on refund)
- `llSetPayPrice` configured based on backend instructions

### Parcel Verifier Object (5.3)

- Small rezzable object that sellers drop on their parcel
- On rez: immediately reads parcel data via `llGetParcelDetails` (parcel UUID, owner, name, area, description, prim capacity)
- POSTs data to backend
- On success response: notify owner via `llOwnerSay`, then self-destruct (`llDie()`)
- On failure: notify owner with reason, then self-destruct
- Should be distributed free on SL Marketplace

---

## Acceptance Criteria

- Verification terminal: touch → code entry → data gathered → POST to backend → result displayed to user
- Escrow terminal: receives L$ payment → POSTs to backend → backend acknowledged
- Escrow terminal: receives payout command via HTTP-in → executes `llTransferLindenDollars` → reports result
- Escrow terminal: re-registers HTTP-in URL after region restart
- Parcel verifier: rez → reads parcel data → POSTs to backend → self-destructs
- All scripts validate they're on the Production shard
- All HTTP requests include proper headers for backend validation
- Error handling: network failures, timeout, invalid responses all handled gracefully with user feedback

---

## Notes

- LSL has significant limitations: 64KB script memory, 16KB HTTP body limit, event-driven (no threads), 2048-byte string limit in many functions.
- `llRequestAgentData` is async - you'll need to handle the `dataserver` event and correlate request IDs.
- HTTP-in URLs are temporary and change on any region restart, script reset, or sim migration. The re-registration pattern is critical.
- `PERMISSION_DEBIT` is granted once by the object owner and persists until script reset/deletion. Cannot be revoked programmatically.
- Rate limit on `llTransferLindenDollars`: 30 payments per 30 seconds per owner per region.
- This phase is a separate track (LSL, not Java/JS) and can be worked in parallel with other phases once the backend endpoints exist.
