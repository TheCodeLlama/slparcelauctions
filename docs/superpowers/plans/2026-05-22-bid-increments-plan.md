# Per-Auction Bid Increments Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the auction creator set a flat per-auction minimum bid increment at creation, replacing the fixed code-level tiered ladder as a runtime rule (it survives only as a create-time suggestion).

**Architecture:** New `bid_increment` column on `auctions` (V44). `BidIncrementTable` renamed `BidIncrementSuggester` and reframed as a create-time suggestion source. `BidService` + `ProxyBidService` read `auction.getBidIncrement()` instead of the static table. `AuctionCreateRequest` / `AuctionUpdateRequest` carry an optional `@Min(1)` increment; `AuctionService` resolves omitted values via the suggester. DTOs expose it; the create form pre-fills it.

**Tech Stack:** Spring Boot 4 / Java 24 / Flyway / JPA / Lombok; Next.js 16 / React 19 / TypeScript 5 / Vitest + RTL; Postman.

**Spec:** `docs/superpowers/specs/2026-05-22-bid-increments-design.md`.

**Migration number:** V44 (V43 = theme image variants is the latest on disk).

**Spec deviation (deliberate, baked into this plan):** Spec section 2 says "No `@Builder.Default`". The plan instead uses `@Builder.Default private Long bidIncrement = 50L;` on the entity. Reason: dozens of existing test fixtures build `Auction` via `.builder()` without setting the field; with no builder default they would produce `null`, NPE `BidService`, and break the suite. `AuctionService.create` still sets the field explicitly from the resolved value, so the builder default is never the operative value for a real auction. This mirrors the resolution the theme-image-variants feature reached for `RealtyGroup.memberSeatLimit`.

**Per-task verification (every task):** run `cd backend && ./mvnw test -Dtest='...'` for backend tasks; for the frontend task run BOTH `npm test` AND `npm run build` (Vitest does not type-check; a prior feature shipped a build break because `tsc` was never run per-task). Commit + push before declaring done.

---

## File Structure

**Backend — new:**

| File | Responsibility |
|---|---|
| `backend/src/main/resources/db/migration/V44__auction_bid_increment.sql` | adds `bid_increment` column |

**Backend — modify:**

| File | Change |
|---|---|
| `auction/Auction.java` | new `bidIncrement` field |
| `auction/BidIncrementTable.java` | rename file + class to `BidIncrementSuggester`; method `minIncrement(currentBid)` to `suggestedIncrement(startingBid)` |
| `auction/BidService.java` | replace `BidIncrementTable.minIncrement(...)` with `auction.getBidIncrement()` |
| `auction/ProxyBidService.java` | same replacement at all call sites |
| `auction/AuctionService.java` | resolve `bidIncrement` on create + update |
| `auction/dto/AuctionCreateRequest.java` | new optional `@Min(1) Long bidIncrement` |
| `auction/dto/AuctionUpdateRequest.java` | new optional `@Min(1) Long bidIncrement` |
| `auction/dto/PublicAuctionResponse.java` | expose `bidIncrement` |
| `auction/dto/SellerAuctionResponse.java` | expose `bidIncrement` |
| `auction/AuctionDtoMapper.java` (or wherever the response mappers live) | map `bidIncrement` into both responses |
| `backend/src/test/java/.../auction/BidIncrementTableTest.java` | rename to `BidIncrementSuggesterTest` |

**Frontend — new:**

| File | Responsibility |
|---|---|
| `frontend/src/lib/auction/suggestedBidIncrement.ts` | TS replication of the suggestion tiers |
| `frontend/src/lib/auction/suggestedBidIncrement.test.ts` | sibling test |

**Frontend — modify:**

| File | Change |
|---|---|
| `frontend/src/types/auction.ts` | add `bidIncrement` to the auction response type(s); add to create/update request types |
| `frontend/src/components/listing/ListingWizardForm.tsx` | "Minimum bid increment" field, pre-filled + dirty-flag auto-tracking |
| `frontend/src/components/listing/ListingWizardForm.test.tsx` | new field cases |
| the bid panel component (find via grep) | next-min-bid hint reads `auction.bidIncrement` |

---

### Task 1: V44 migration + `Auction.bidIncrement` field

**Files:**
- Create: `backend/src/main/resources/db/migration/V44__auction_bid_increment.sql`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/Auction.java`

- [ ] **Step 1: Write the migration**

`backend/src/main/resources/db/migration/V44__auction_bid_increment.sql`:

```sql
-- Per-auction minimum bid increment. The DEFAULT 50 satisfies NOT NULL for any
-- pre-existing non-live rows (ended / cancelled); there are no live auctions.
-- AuctionService writes the creator's resolved value explicitly on every new
-- auction, so the column default is never the operative value for a real one.
ALTER TABLE auctions
  ADD COLUMN bid_increment BIGINT NOT NULL DEFAULT 50;
```

- [ ] **Step 2: Add the entity field**

In `Auction.java`, near `startingBid` / `currentBid` (around line 184), add:

```java
@Builder.Default
@Column(name = "bid_increment", nullable = false)
private Long bidIncrement = 50L;
```

`@Builder.Default` is deliberate (see the plan header deviation note) — it keeps builder-constructed test fixtures non-null. `AuctionService.create` overwrites it with the resolved value (Task 3).

- [ ] **Step 3: Compile + run a context-loading test**

```bash
cd backend && ./mvnw test -Dtest=AuctionRepositoryIntegrationTest
```
Expected: green; Flyway logs `Successfully applied 1 migration to schema "public", now at version v44`. If no `AuctionRepositoryIntegrationTest` exists, run any `@SpringBootTest` in the auction package to boot the context against the migrated schema.

- [ ] **Step 4: Commit + push**

```bash
git add backend/src/main/resources/db/migration/V44__auction_bid_increment.sql \
        backend/src/main/java/com/slparcelauctions/backend/auction/Auction.java
git commit -m "feat(bid-increment): V44 migration + Auction.bidIncrement field"
git push
```

---

### Task 2: Rename `BidIncrementTable` to `BidIncrementSuggester` + rewire bid services

The rename breaks `BidService` and `ProxyBidService` compilation (they reference `BidIncrementTable`). The fix IS the rewire — so this is one task: the table stops being a runtime rule, the services read the per-auction column.

**Files:**
- Modify (rename): `backend/src/main/java/com/slparcelauctions/backend/auction/BidIncrementTable.java` to `BidIncrementSuggester.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/BidService.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/ProxyBidService.java`
- Modify (rename): `backend/src/test/java/com/slparcelauctions/backend/auction/BidIncrementTableTest.java` to `BidIncrementSuggesterTest.java`

- [ ] **Step 1: Rename the class + method**

Create `BidIncrementSuggester.java` (and delete `BidIncrementTable.java`). Content:

```java
package com.slparcelauctions.backend.auction;

/**
 * Create-time suggestion source for an auction's minimum bid increment.
 *
 * <p>This is a SUGGESTION ONLY. It pre-fills the create form and is the
 * fallback when a create request omits an increment. It is NOT a runtime
 * bid-validation rule - once an auction exists, its {@code bid_increment}
 * column is the sole authority (see BidService / ProxyBidService).
 *
 * <p>The tier breakpoints are unchanged from the former BidIncrementTable
 * (DESIGN.md section 4.7); only the framing and the input changed - the
 * suggestion keys off the auction's starting bid, since at create time
 * there is no current bid.
 */
public final class BidIncrementSuggester {

    private BidIncrementSuggester() {
        // no instances
    }

    /**
     * Suggested minimum bid increment in L$, derived from the starting bid.
     * Always strictly positive.
     */
    public static long suggestedIncrement(long startingBid) {
        if (startingBid < 1_000L)    return 50L;
        if (startingBid < 10_000L)   return 100L;
        if (startingBid < 100_000L)  return 500L;
        return 1_000L;
    }
}
```

- [ ] **Step 2: Rewire `BidService`**

In `BidService.java`, the minimum-bid gate (around line 164-169):

```java
long currentBid = auction.getCurrentBid() == null ? 0L : auction.getCurrentBid();
long minRequired = currentBid > 0L
        ? currentBid + auction.getBidIncrement()
        : auction.getStartingBid();
if (amount < minRequired) {
    throw new BidTooLowException(minRequired);
}
```

And the post-bid next-min hint (around line 200): `amount + BidIncrementTable.minIncrement(amount)` becomes `amount + auction.getBidIncrement()`.

Remove the now-unused `BidIncrementTable` import.

- [ ] **Step 3: Rewire `ProxyBidService`**

Replace every `BidIncrementTable.minIncrement(X)` with `auction.getBidIncrement()`:
- The Branch-1 floor (around line 280-281): `currentBid + auction.getBidIncrement()`.
- Branch-2 settle (around line 300-302): `existing.getMaxAmount() + auction.getBidIncrement()`.
- Branch-3 settle (around line 310-312): `proxy.getMaxAmount() + auction.getBidIncrement()`.
- `minRequiredForNextBid` helper (around line 362): `currentBid + auction.getBidIncrement()`.

`auction` is in scope at every one of these sites. Remove the unused import.

- [ ] **Step 4: Rename the suggester test**

`BidIncrementTableTest.java` to `BidIncrementSuggesterTest.java`. Same four boundary assertions, against `BidIncrementSuggester.suggestedIncrement(startingBid)`:

```java
@Test void below1k_suggests50()   { assertThat(BidIncrementSuggester.suggestedIncrement(999L)).isEqualTo(50L); }
@Test void at1k_suggests100()     { assertThat(BidIncrementSuggester.suggestedIncrement(1_000L)).isEqualTo(100L); }
@Test void at10k_suggests500()    { assertThat(BidIncrementSuggester.suggestedIncrement(10_000L)).isEqualTo(500L); }
@Test void at100k_suggests1000()  { assertThat(BidIncrementSuggester.suggestedIncrement(100_000L)).isEqualTo(1_000L); }
@Test void zero_suggests50()      { assertThat(BidIncrementSuggester.suggestedIncrement(0L)).isEqualTo(50L); }
```

- [ ] **Step 5: Run the bid-service tests**

```bash
cd backend && ./mvnw test -Dtest='BidService*,ProxyBidService*,BidIncrementSuggesterTest'
```

Existing `BidService` / `ProxyBidService` tests build `Auction` via `.builder()`. With Task 1's `@Builder.Default = 50L`, those auctions carry `bidIncrement = 50`, which equals the smallest tier the old table returned for a fresh (0-current-bid) auction — so existing tests that bid against a low-value auction stay green. Tests that exercised a higher tier (e.g. a L$50k auction expecting a L$500 increment) will now see L$50 instead and must be updated: set `.bidIncrement(...)` explicitly on those fixtures to the value the test intends. Update each such test to set the increment it means to assert against.

Expected: green after those fixture updates.

- [ ] **Step 6: Commit + push**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/ \
        backend/src/test/java/com/slparcelauctions/backend/auction/
git commit -m "feat(bid-increment): BidIncrementSuggester rename + bid services read per-auction increment"
git push
```

---

### Task 3: Create + update request fields + `AuctionService` resolution

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/dto/AuctionCreateRequest.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/dto/AuctionUpdateRequest.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionService.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/auction/AuctionServiceTest.java` (or the existing create/update test class)

- [ ] **Step 1: Add the request fields**

`AuctionCreateRequest` — add after `buyNowPrice`:

```java
@Min(value = 1, message = "bidIncrement must be at least 1")
Long bidIncrement,
```

`AuctionUpdateRequest` — add after `buyNowPrice`:

```java
@Min(value = 1, message = "bidIncrement must be at least 1")
Long bidIncrement,
```

Both are nullable records fields; `@Min(1)` only fires when non-null. (`@Min` is already imported in both files.)

- [ ] **Step 2: Resolve on create**

In `AuctionService.create`, where the `Auction` builder is assembled (around line 103-105, near `.startingBid(req.startingBid())`), add:

```java
.bidIncrement(req.bidIncrement() != null
        ? req.bidIncrement()
        : BidIncrementSuggester.suggestedIncrement(req.startingBid()))
```

- [ ] **Step 3: Resolve on update**

In `AuctionService.update`, alongside the existing `if (req.startingBid() != null) { a.setStartingBid(req.startingBid()); }` block (around line 173-174), add:

```java
if (req.bidIncrement() != null) {
    a.setBidIncrement(req.bidIncrement());
}
```

`update` already gates on `status == DRAFT || status == DRAFT_PAID` (around line 151), so `bidIncrement` inherits the same pre-active edit window as `startingBid` for free.

- [ ] **Step 4: Tests**

Add to the auction create/update test class:

```java
@Test
void create_omittedBidIncrement_resolvesToSuggestion() {
    // startingBid 5000 -> suggester returns 100
    // build a create request with bidIncrement = null
    // assert the persisted auction has bidIncrement == 100
}

@Test
void create_explicitBidIncrement_isUsedVerbatim() {
    // create request with bidIncrement = 250, startingBid 5000
    // assert persisted auction has bidIncrement == 250 (not the 100 suggestion)
}

@Test
void update_bidIncrement_appliedWhilePreActive() {
    // a DRAFT auction; update request with bidIncrement = 300
    // assert auction.bidIncrement == 300
}
```

The `@Min(1)` rejection (0 / negative -> 400) is bean-validation on the controller boundary; cover it in the controller integration test (Task 4 touches the controller test file) OR add a `@SpringBootTest + @AutoConfigureMockMvc` case here asserting `POST /api/v1/auctions` with `bidIncrement: 0` returns 400. Use `@SpringBootTest + @AutoConfigureMockMvc`, never `@WebMvcTest`.

- [ ] **Step 5: Run**

```bash
cd backend && ./mvnw test -Dtest='AuctionService*,AuctionControllerIntegrationTest'
```

- [ ] **Step 6: Commit + push**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/ \
        backend/src/test/java/com/slparcelauctions/backend/auction/
git commit -m "feat(bid-increment): create + update requests carry optional increment; service resolves it"
git push
```

---

### Task 4: Expose `bidIncrement` on the auction response DTOs

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/dto/PublicAuctionResponse.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/dto/SellerAuctionResponse.java`
- Modify: the mapper that builds those records (find via `grep -rln "PublicAuctionResponse" backend/src/main/java` — likely `AuctionDtoMapper`)
- Test: the mapper's test + any auction-controller integration test asserting the response shape

- [ ] **Step 1: Add the field to both records**

`PublicAuctionResponse` — add `Long bidIncrement` next to `startingBid` (line ~53). `SellerAuctionResponse` — same, next to its `startingBid` (line ~43). Record component; place consistently.

- [ ] **Step 2: Map it**

In the mapper, every site that constructs a `PublicAuctionResponse` or `SellerAuctionResponse` passes `auction.getBidIncrement()` for the new component. Find all constructor call sites (the records are positional — the compiler will flag every incomplete call after the field is added).

- [ ] **Step 3: Tests**

In the mapper test: assert `bidIncrement` is carried into both response types. In the auction-controller integration test: assert `GET /api/v1/auctions/{publicId}` returns a `bidIncrement` field.

- [ ] **Step 4: Run**

```bash
cd backend && ./mvnw test -Dtest='AuctionDtoMapper*,AuctionControllerIntegrationTest'
```

- [ ] **Step 5: Commit + push**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/ \
        backend/src/test/java/com/slparcelauctions/backend/auction/
git commit -m "feat(bid-increment): expose bidIncrement on public + seller auction responses"
git push
```

---

### Task 5: Frontend — suggestion helper, create-form field, bid panel

**Files:**
- Create: `frontend/src/lib/auction/suggestedBidIncrement.ts`
- Create: `frontend/src/lib/auction/suggestedBidIncrement.test.ts`
- Modify: `frontend/src/types/auction.ts`
- Modify: `frontend/src/components/listing/ListingWizardForm.tsx`
- Modify: `frontend/src/components/listing/ListingWizardForm.test.tsx`
- Modify: the bid panel component (find via `grep -rln "minimum bid\|nextMinBid\|min next" frontend/src/components`)

- [ ] **Step 1: Suggestion helper + test**

`frontend/src/lib/auction/suggestedBidIncrement.ts`:

```ts
/**
 * Suggested minimum bid increment (L$) derived from the starting bid.
 * Mirrors the backend BidIncrementSuggester tiers. Used to pre-fill the
 * create form; the creator can override.
 */
export function suggestedBidIncrement(startingBid: number): number {
  if (startingBid < 1_000) return 50;
  if (startingBid < 10_000) return 100;
  if (startingBid < 100_000) return 500;
  return 1_000;
}
```

`suggestedBidIncrement.test.ts`: assert the four tier boundaries (999 to 50, 1000 to 100, 10000 to 500, 100000 to 1000) plus 0 to 50.

- [ ] **Step 2: Types**

In `frontend/src/types/auction.ts`: add `bidIncrement: number` to the auction response type(s) that mirror `PublicAuctionResponse` / `SellerAuctionResponse`. Add optional `bidIncrement?: number` to the create-request and update-request types.

- [ ] **Step 3: Create-form field**

In `ListingWizardForm.tsx`, the auction-settings section: add a "Minimum bid increment" numeric L$ input. State + behavior:
- A `bidIncrementDirty` boolean flag (default false).
- When the starting-bid field changes AND `bidIncrementDirty` is false, set the increment field to `suggestedBidIncrement(startingBid)`.
- When the creator edits the increment field directly, set `bidIncrementDirty = true` (it stops auto-tracking).
- The field is always included in the submitted request payload.
- Client-side validation: integer, `>= 1`. Surface an inline error mirroring the backend `@Min(1)`.

- [ ] **Step 4: Bid panel**

In the bid panel component: the "next minimum bid" hint reads `currentBid > 0 ? currentBid + auction.bidIncrement : auction.startingBid`. Replace any prior hardcoded / tiered assumption. Also surface "Minimum bid increment: L$X" as a static line near the bid input (small `text-fg-muted`).

- [ ] **Step 5: Tests**

`ListingWizardForm.test.tsx`:
- The increment field renders.
- Changing the starting bid updates the increment field to the suggestion (while not dirty).
- After the creator edits the increment field, changing the starting bid no longer moves it.
- The submitted request carries `bidIncrement`.

Bid-panel test: the next-min-bid hint equals `currentBid + auction.bidIncrement`.

- [ ] **Step 6: Run tests AND build**

```bash
cd frontend && npm test -- --run suggestedBidIncrement ListingWizardForm
cd frontend && npm run build
cd frontend && npm run verify
```

`npm run build` is mandatory — it runs `tsc`, which `npm test` (Vitest) does not. All three must pass.

- [ ] **Step 7: Commit + push**

```bash
git add frontend/src/lib/auction/ frontend/src/types/auction.ts \
        frontend/src/components/listing/
# plus the bid panel file
git commit -m "feat(bid-increment): create-form increment field + bid-panel next-min-bid hint"
git push
```

---

### Task 6: Postman + README + DEFERRED_WORK check + PR into dev

**Files:**
- Modify: SLPA Postman collection (cloud)
- Modify: `README.md`
- Modify: `docs/implementation/DEFERRED_WORK.md` (only if anything was deferred)

- [ ] **Step 1: Postman**

In the SLPA collection: the create-auction and update-auction requests gain a `bidIncrement` body field. Add (or extend) a bid request that posts a sub-increment amount and `pm.test`-asserts the rejection.

If the `mcp__postman__*` tools are unavailable, skip and note it as a follow-up.

- [ ] **Step 2: README sweep**

Add a short bullet under the existing feature list / auction section noting that auction creators set the minimum bid increment at creation (suggested from the starting bid, overridable). Check the README for any line describing the old fixed tiered increment ladder and update it.

- [ ] **Step 3: DEFERRED_WORK check**

```bash
grep -rn "TODO\|FIXME\|XXX" backend/src/main/java/com/slparcelauctions/backend/auction/BidIncrementSuggester.java \
        frontend/src/lib/auction/
```

If anything was deferred, record it in `docs/implementation/DEFERRED_WORK.md`. Expected: nothing.

- [ ] **Step 4: Full suites**

```bash
cd backend && ./mvnw test
cd frontend && npm test
cd frontend && npm run build
cd frontend && npm run verify
```

All green required. STOP and report if anything is red — do not open the PR.

- [ ] **Step 5: Commit README (+ DEFERRED_WORK if changed)**

```bash
git add README.md
git commit -m "docs: README note for per-auction bid increments"
git push
```

- [ ] **Step 6: Open PR into dev and merge it**

```bash
gh pr create --base dev --head feat/bid-increments \
  --title "feat(auction): per-auction creator-set bid increments (#396)" \
  --body "$(cat <<'EOF'
## Summary
- Implements per-auction bid increments per docs/superpowers/specs/2026-05-22-bid-increments-design.md (closes #396).
- New auctions.bid_increment column (Flyway V44).
- Creator sets a flat minimum bid increment at auction creation; the create form pre-fills it from the starting bid (overridable). Editable in the pre-active window alongside startingBid.
- BidIncrementTable renamed BidIncrementSuggester - reframed as a create-time suggestion source, no longer a runtime rule.
- BidService + ProxyBidService validate against the per-auction bid_increment column.
- bidIncrement exposed on the public + seller auction responses; the bid panel uses it for the next-minimum-bid hint.

## Test plan
- [x] backend ./mvnw test full suite green
- [x] frontend npm test + npm run build + npm run verify green
EOF
)"
```

Capture the PR number. Merge it into dev:

```bash
gh pr merge --merge <PR_NUMBER>
gh pr view <PR_NUMBER> --json state,mergeCommit -q '.state + " " + (.mergeCommit.oid // "none")'
```

Confirm `MERGED`. Do NOT open or merge a dev->main PR — the user handles that.

---

## Self-review

**Spec coverage:**
- Spec section 2 (data model) — Task 1.
- Spec section 3 (create / update / bid validation / proxy resolution) — Tasks 2 (bid + proxy rewire) and 3 (create + update resolution).
- Spec section 4 (the suggester) — Task 2.
- Spec section 5 (endpoints / DTO exposure) — Tasks 3 (request DTOs) and 4 (response DTOs).
- Spec section 6 (frontend) — Task 5.
- Spec section 7 (testing) — distributed across every task; each backend task carries its tests, Task 5 the frontend tests, Task 6 Postman.
- Spec section 8 (out of scope) — Task 6 DEFERRED_WORK check confirms nothing deferred.
- Spec section 9 (decision log) — context only.

**Placeholder scan:** No `TBD` / `TODO` / `implement later`. Every code step shows the actual code. Two "find via grep" handoffs (the response mapper, the bid panel component) include the grep command so the implementer locates the exact file — the file names vary and a stale guess would be worse than a grep.

**Type consistency:** `bidIncrement` (Java `Long`, TS `number`), `BidIncrementSuggester.suggestedIncrement(long startingBid)`, `auction.getBidIncrement()` — used identically across Tasks 1-5. `suggestedBidIncrement(startingBid)` (TS) mirrors the Java method name minus the class. Request fields `bidIncrement` on both create + update; response component `bidIncrement` on both public + seller responses.

**Resolved during plan-writing:** The spec's "No `@Builder.Default`" instruction is overridden — see the plan header deviation note. Without a builder default, the entity field is `null` for every `.builder()`-constructed test fixture, NPEs `BidService`, and breaks the suite. `@Builder.Default = 50L` plus explicit `AuctionService.create` assignment is the same pattern the theme-image-variants feature settled on for `RealtyGroup.memberSeatLimit`.
