# Listing + auction UX Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship three listing/auction UX changes: an editable "Sell Price" what-if projection, a dedicated "Buy now" button under Place bid, and real seller stats in the create-listing preview.

**Architecture:** Areas 1 and 2 are frontend-only. Area 3 enriches the seller-facing auction response DTO with the seller summary the public response already computes (no schema/Flyway, no new endpoint). One feature branch `feature/listing-auction-ux` off `dev`, one PR.

**Tech Stack:** Next.js 16 / React 19 / TS 5 / Vitest + Testing Library (frontend); Spring Boot 4 / Java 24 / JUnit (backend).

**Spec:** `docs/superpowers/specs/2026-05-18-listing-auction-ux-design.md`

**Global rules (apply to every task):** No emoji. No em-dashes or connector en-dashes in any user-facing copy, comments, commits, or PRs. Feature branch off `dev`. Backend changes need a paired migration only when an entity changes (none here). Run the relevant test command after each step.

---

### Task 1: Backend - populate seller summary on SellerAuctionResponse

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/dto/SellerAuctionResponse.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionDtoMapper.java:197-238` (the `toSellerResponse(Auction, Escrow, MapperBatchContext)` body)
- Modify: `backend/src/test/java/com/slparcelauctions/backend/auction/AuctionControllerTest.java` (the `new SellerAuctionResponse(...)` construction site - arity change)
- Test: `backend/src/test/java/com/slparcelauctions/backend/auction/AuctionDtoMapperTest.java` (create if absent; otherwise add a method)

**Context:** `AuctionDtoMapper.sellerSummary(User s)` (line 335) already builds a
`PublicAuctionResponse.SellerSummary` including the server-computed
`completionRate` (`SellerCompletionRateMapper.compute(...)`).
`toPublicResponse` passes `sellerSummary(a.getSeller())`; `toSellerResponse`
does not, so the seller-facing draft/activate preview has no seller block and
the frontend falls back to a `"You" / 0 sales` placeholder.

- [ ] **Step 1: Write the failing test**

Add to `AuctionDtoMapperTest` (mirror the existing public-response seller-summary test if one exists; otherwise construct an `Auction` with a seller `User` that has `avgSellerRating`, `totalSellerReviews`, `completedSales`, `cancelledWithBids`, `createdAt` set):

```java
@Test
void toSellerResponse_populatesSellerSummary_withSameComputationAsPublic() {
    Auction a = /* existing test-builder for an auction with a seeded seller */;
    SellerAuctionResponse seller = mapper.toSellerResponse(a);
    PublicAuctionResponse pub = mapper.toPublicResponse(a);

    assertThat(seller.seller()).isNotNull();
    assertThat(seller.seller()).isEqualTo(pub.seller());
}
```

- [ ] **Step 2: Run it, expect compile failure / red**

Run: `backend/mvnw -q -f backend/pom.xml test -Dtest=AuctionDtoMapperTest`
Expected: fails to compile (`SellerAuctionResponse` has no `seller()` accessor) or fails red.

- [ ] **Step 3: Add the record component**

In `SellerAuctionResponse.java`, add a component immediately after
`UUID sellerPublicId,` (line 28):

```java
        UUID sellerPublicId,
        /**
         * Seller reputation summary. Mirrors {@link PublicAuctionResponse.SellerSummary}
         * and is populated identically to the public view so the seller's own
         * draft/activate preview matches the buyer-facing card exactly
         * (including the server-computed completionRate).
         */
        PublicAuctionResponse.SellerSummary seller,
```

Add the import if not already present:
`import com.slparcelauctions.backend.auction.dto.PublicAuctionResponse;`
(same package, so reference as `PublicAuctionResponse.SellerSummary` directly;
no import needed since it is the same `dto` package).

- [ ] **Step 4: Pass the summary in the mapper**

In `AuctionDtoMapper.toSellerResponse(...)` (the 3-arg overload, the
`new SellerAuctionResponse(` call starting line 199), insert
`sellerSummary(a.getSeller()),` as the second constructor argument,
immediately after `a.getSeller().getPublicId(),`:

```java
        return new SellerAuctionResponse(
                a.getPublicId(),
                a.getSeller().getPublicId(),
                sellerSummary(a.getSeller()),
                a.getTitle(),
                /* ...rest unchanged... */
```

- [ ] **Step 5: Fix the other construction site**

`AuctionControllerTest.java` constructs `new SellerAuctionResponse(...)`
directly. Add the matching `seller` argument in the same position (after
`sellerPublicId`). Use a representative `PublicAuctionResponse.SellerSummary`
(or `null` if the test does not assert seller fields).

- [ ] **Step 6: Run the test, expect green**

Run: `backend/mvnw -q -f backend/pom.xml test -Dtest=AuctionDtoMapperTest`
Expected: PASS.

- [ ] **Step 7: Run the broader auction backend slice**

Run: `backend/mvnw -q -f backend/pom.xml test -Dtest='com.slparcelauctions.backend.auction.*'`
Expected: PASS (catches any other `SellerAuctionResponse` assertion that now sees the new field).

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/dto/SellerAuctionResponse.java backend/src/main/java/com/slparcelauctions/backend/auction/AuctionDtoMapper.java backend/src/test/java/com/slparcelauctions/backend/auction/AuctionControllerTest.java backend/src/test/java/com/slparcelauctions/backend/auction/AuctionDtoMapperTest.java
git commit -m "feat(auction): populate seller summary on SellerAuctionResponse"
```

---

### Task 2: Frontend - draft preview renders real seller stats

**Files:**
- Verify only (no logic change expected): `frontend/src/app/listings/(verified)/[publicId]/activate/DraftEditorClient.tsx:160-175`
- Verify only: `frontend/src/types/auction.ts` (`SellerAuctionResponse.seller?` already exists; confirm field names match the backend `SellerSummary` JSON)
- Modify: `frontend/src/test/fixtures/auction.ts` (add a `seller` block to the seller-auction fixture)
- Test: `frontend/src/app/listings/(verified)/[publicId]/activate/ActivateClient.test.tsx`

**Context:** `DraftEditorClient` already maps `sellerCardData` from
`auction.seller` when present; with Task 1 it is now present. The placeholder
becomes defensive-only and must remain.

- [ ] **Step 1: Update the fixture**

In `frontend/src/test/fixtures/auction.ts`, add a `seller` block to the
seller-auction fixture object matching `AuctionSellerSummaryDto`:

```ts
seller: {
  publicId: "11111111-1111-1111-1111-111111111111",
  displayName: "Test Seller",
  avatarUrl: null,
  averageRating: 4.5,
  reviewCount: 12,
  completedSales: 7,
  completionRate: 0.92,
  memberSince: "2025-11-03",
},
```

- [ ] **Step 2: Write the failing test**

In `ActivateClient.test.tsx` (or the draft-editor test that renders
`DraftEditorClient`), add a case asserting the real seller card shows the
fixture stats, not the placeholder:

```ts
it("renders the seller's real reputation in the draft preview", async () => {
  // render with the seller-auction fixture (now carrying `seller`)
  expect(await screen.findByTestId("seller-profile-card")).toBeInTheDocument();
  expect(screen.getByText("Test Seller")).toBeInTheDocument();
  expect(screen.getByText(/7 completed sale/)).toBeInTheDocument();
  expect(screen.getByText(/Completion rate: 92%/)).toBeInTheDocument();
  expect(screen.queryByText(/^You$/)).not.toBeInTheDocument();
});
```

- [ ] **Step 3: Run it**

Run: `cd frontend && npm test -- ActivateClient`
Expected: PASS without any `DraftEditorClient` code change (Task 1 + fixture
drive it). If it fails because the test harness mocks `getAuction` without the
`seller` block, update that mock to use the fixture. Do NOT modify the
`DraftEditorClient` placeholder branch (keep it defensive).

- [ ] **Step 4: Confirm types**

Open `frontend/src/types/auction.ts`; confirm `AuctionSellerSummaryDto` field
names (`averageRating`, `reviewCount`, `completedSales`, `completionRate`,
`memberSince`, `publicId`, `displayName`, `avatarUrl`) match the backend
`SellerSummary` JSON (the public auction already deserializes this shape, so
no change is expected). If a mismatch exists, fix the fixture, not the type.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/test/fixtures/auction.ts "frontend/src/app/listings/(verified)/[publicId]/activate/ActivateClient.test.tsx"
git commit -m "test(listing): draft preview asserts real seller stats"
```

---

### Task 3: Sell Price editable projection

**Files:**
- Modify: `frontend/src/components/listing/AgentCommissionPreview.tsx`
- Modify: `frontend/src/components/listing/ListingWizardForm.tsx` (the `<AgentCommissionPreview ... />` render site, ~line 367)
- Test: `frontend/src/components/listing/AgentCommissionPreview.test.tsx`
- Test: `frontend/src/components/listing/ListingWizardForm.test.tsx`

- [ ] **Step 1: Write the failing tests**

In `AgentCommissionPreview.test.tsx`, replace the "List price" string
expectations with "Sell Price" and add:

```ts
it('labels the row "Sell Price" and the derived rows "at sell price"', () => {
  render(<AgentCommissionPreview startingBid={1000} reservePrice={2000} buyNowPrice={5000} groupName="Sunset Realty" groupPublicId="g1" agentCommissionRate={0.1} />);
  expect(screen.getByText("Sell Price")).toBeInTheDocument();
  expect(screen.getByText("Platform commission at sell price")).toBeInTheDocument();
  expect(screen.getByText("Your earnings at sell price")).toBeInTheDocument();
  expect(screen.getByText("Sunset Realty earnings at sell price")).toBeInTheDocument();
});

it("defaults the Sell Price input to buyNow ?? reserve ?? starting", () => {
  const { rerender } = render(<AgentCommissionPreview startingBid={1000} reservePrice={2000} buyNowPrice={5000} groupName="G" groupPublicId="g1" agentCommissionRate={0.1} />);
  expect((screen.getByTestId("sell-price-input") as HTMLInputElement).value).toBe("5000");
  rerender(<AgentCommissionPreview startingBid={1000} reservePrice={2000} buyNowPrice={null} groupName="G" groupPublicId="g1" agentCommissionRate={0.1} />);
  expect((screen.getByTestId("sell-price-input") as HTMLInputElement).value).toBe("2000");
  rerender(<AgentCommissionPreview startingBid={1000} reservePrice={null} buyNowPrice={null} groupName="G" groupPublicId="g1" agentCommissionRate={0.1} />);
  expect((screen.getByTestId("sell-price-input") as HTMLInputElement).value).toBe("1000");
});

it("recomputes the projected rows when the Sell Price input changes", async () => {
  render(<AgentCommissionPreview startingBid={1000} reservePrice={null} buyNowPrice={null} groupName="G" groupPublicId="g1" agentCommissionRate={0.1} />);
  const input = screen.getByTestId("sell-price-input");
  fireEvent.change(input, { target: { value: "10000" } });
  // platformCommission = floor(10000*0.05)=500; earnings=9500; agentSlice=floor(9500*0.1)=950; group=8550
  expect(screen.getByText("L$500", { exact: false })).toBeInTheDocument();
  expect(screen.getByText("L$950", { exact: false })).toBeInTheDocument();
  expect(screen.getByText("L$8,550", { exact: false })).toBeInTheDocument();
});

it("keeps the listing-fee gating keyed off starting bid, not the Sell Price input", async () => {
  const onInsufficient = vi.fn();
  // wallet balance mock so balance < floor(startingBid*0.05) toggles insufficient.
  render(<AgentCommissionPreview startingBid={1000} reservePrice={null} buyNowPrice={null} groupName="G" groupPublicId="g1" agentCommissionRate={0.1} onInsufficient={onInsufficient} />);
  const firstCallCount = onInsufficient.mock.calls.length;
  fireEvent.change(screen.getByTestId("sell-price-input"), { target: { value: "999999" } });
  // Editing the projection must not change the insufficiency decision.
  expect(onInsufficient.mock.calls.length).toBe(firstCallCount);
});
```

(Reuse the file's existing wallet-query mock setup for the gating test.)

- [ ] **Step 2: Run, expect red**

Run: `cd frontend && npm test -- AgentCommissionPreview`
Expected: FAIL (props `reservePrice`/`buyNowPrice` unknown, no `sell-price-input`, old labels).

- [ ] **Step 3: Implement**

In `AgentCommissionPreview.tsx`:

- Extend `AgentCommissionPreviewProps`: add `reservePrice: number | null;`
  and `buyNowPrice: number | null;`.
- Add state + default logic near the top of the component body:

```tsx
const defaultSell = buyNowPrice ?? reservePrice ?? startingBid;
const [raw, setRaw] = useState<string>(String(defaultSell));
const [touched, setTouched] = useState(false);
useEffect(() => {
  if (!touched) {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setRaw(String(defaultSell));
  }
}, [defaultSell, touched]);
const parsedSell = raw.trim() === "" ? defaultSell : Math.floor(Number(raw));
const sellPrice =
  Number.isFinite(parsedSell) && parsedSell > 0 ? parsedSell : defaultSell;
```

- Compute the displayed projection from `sellPrice` (not `startingBid`):

```tsx
const platformCommission = floorLindens(sellPrice, PLATFORM_COMMISSION_RATE);
const earnings = sellPrice - platformCommission;
const agentSlice = floorLindens(earnings, agentCommissionRate);
const groupSlice = earnings - agentSlice;
```

- Keep the fee/gating decoupled and based on `startingBid`:

```tsx
const listingFee = floorLindens(startingBid, PLATFORM_COMMISSION_RATE);
const insufficient = balance !== null && balance < listingFee;
const shortfall = insufficient && balance !== null ? listingFee - balance : 0;
```

(`onInsufficient` effect unchanged - it depends on `insufficient`, which is
now starting-bid-derived only.)

- Keep `if (startingBid <= 0) return null;`.
- Replace the "List price" `<dt>/<dd>` row: `<dt>` text becomes
  `Sell Price`; the `<dd>` becomes a controlled numeric input:

```tsx
<div className="flex justify-between gap-4 py-1">
  <dt className="text-fg-muted">Sell Price</dt>
  <dd>
    <input
      type="number"
      inputMode="numeric"
      min={1}
      step={1}
      aria-label="Sell price (L$)"
      data-testid="sell-price-input"
      value={raw}
      onChange={(e) => {
        setTouched(true);
        setRaw(e.target.value);
      }}
      className="w-28 rounded-md bg-surface-raised px-2 py-1 text-right tabular-nums font-medium text-fg ring-1 ring-transparent focus:outline-none focus:ring-2 focus:ring-brand"
    />
  </dd>
</div>
```

- Rename the three derived `<dt>` strings to "Platform commission at sell
  price", "Your earnings at sell price", "{groupName} earnings at sell
  price". Keep the `(5%)`, `({ratePct}% of remaining)`, `(remaining)`
  annotations and all existing classNames.
- Add `useEffect` to the React import if not already imported.

- [ ] **Step 4: Wire the wizard**

In `ListingWizardForm.tsx`, at the `<AgentCommissionPreview>` render site,
add `reservePrice={...}` and `buyNowPrice={...}` from the same
auction-settings state object that supplies `startingBid` (the
`AuctionSettingsValue` carries `reservePrice` and `buyNowPrice` as
`number | null`). Update `ListingWizardForm.test.tsx` assertions that
reference "List price" to "Sell Price" and pass the new props in any direct
`AgentCommissionPreview` render.

- [ ] **Step 5: Run, expect green**

Run: `cd frontend && npm test -- AgentCommissionPreview ListingWizardForm`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/listing/AgentCommissionPreview.tsx frontend/src/components/listing/AgentCommissionPreview.test.tsx frontend/src/components/listing/ListingWizardForm.tsx frontend/src/components/listing/ListingWizardForm.test.tsx
git commit -m "feat(listing): editable Sell Price what-if projection"
```

---

### Task 4: Buy now button under Place bid

**Files:**
- Modify: `frontend/src/components/auction/PlaceBidForm.tsx`
- Test: `frontend/src/components/auction/PlaceBidForm.test.tsx`

- [ ] **Step 1: Write the failing tests**

Add to `PlaceBidForm.test.tsx`:

```ts
it("shows a Buy now button when the auction has a buy-now price", () => {
  renderForm({ buyNowPrice: 25000 });
  expect(screen.getByTestId("place-bid-buy-now")).toHaveTextContent("Buy now · L$25,000");
});

it("does not show the Buy now button when there is no buy-now price", () => {
  renderForm({ buyNowPrice: null });
  expect(screen.queryByTestId("place-bid-buy-now")).not.toBeInTheDocument();
});

it("Buy now opens the buy-now confirm and places a bid at exactly the buy-now price", async () => {
  const placeBidSpy = /* spy on placeBid */;
  renderForm({ buyNowPrice: 25000 });
  fireEvent.click(screen.getByTestId("place-bid-buy-now"));
  // ConfirmBidDialog buy-now branch is open
  fireEvent.click(screen.getByRole("button", { name: /Buy now · L\$25,000/ }));
  await waitFor(() => expect(placeBidSpy).toHaveBeenCalledWith(expect.any(String), 25000));
});

it("disables Buy now when disconnected", () => {
  renderForm({ buyNowPrice: 25000, connectionState: { status: "reconnecting" } });
  expect(screen.getByTestId("place-bid-buy-now")).toBeDisabled();
});
```

(Use the file's existing render helper / mock conventions; `renderForm` is
illustrative for whatever the file already uses.)

- [ ] **Step 2: Run, expect red**

Run: `cd frontend && npm test -- PlaceBidForm`
Expected: FAIL (no `place-bid-buy-now`).

- [ ] **Step 3: Implement**

In `PlaceBidForm.tsx`, immediately after the existing submit `<Button>`
(the one rendering `{buttonLabel}`, ~line 206-215) and before the
`{!isConnected ? ...}` helper, add:

```tsx
{buyNow != null ? (
  <Button
    type="button"
    variant="secondary"
    fullWidth
    disabled={!isConnected || mutation.isPending}
    data-testid="place-bid-buy-now"
    onClick={() => setConfirm({ kind: "buy-now", amount: buyNow })}
  >
    {`Buy now · L$${buyNow.toLocaleString()}`}
  </Button>
) : null}
```

No other change: the existing `confirm.kind === "buy-now"` dialog block
(lines 225-237) already renders for `buyNow != null` and calls
`submitBid(confirm.amount)`; `amount === buyNow` so the dialog copy is
accurate. The group-COI early return already prevents the button from
rendering for group members; `BidPanel` variant gating prevents
non-bidder viewers from mounting the form.

- [ ] **Step 4: Run, expect green**

Run: `cd frontend && npm test -- PlaceBidForm`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/auction/PlaceBidForm.tsx frontend/src/components/auction/PlaceBidForm.test.tsx
git commit -m "feat(auction): dedicated Buy now button under Place bid"
```

---

### Task 5: Postman, README sweep, full verification

**Files:**
- Modify: Postman SLPA collection (seller `GET /api/v1/auctions/{id}` saved example)
- Modify: `README.md` (sweep for staleness; add a one-line note only if a slice description is now inaccurate)

- [ ] **Step 1: Postman**

Update the SLPA Postman collection's seller `GET /api/v1/auctions/{id}`
saved response/example so it includes the new `seller` block (mirror the
public auction's seller summary shape). Use the Postman MCP tools; do not
change any request scripts.

- [ ] **Step 2: README sweep**

Grep `README.md` for any slice text describing the seller-facing auction
response or the create-listing preview placeholder. If a sentence is now
inaccurate, correct it in one line. If nothing is stale, make no change
(the sweep itself satisfies the project convention).

- [ ] **Step 3: Full frontend verification**

Run: `cd frontend && npm run lint && npm test && npm run build && npm run verify`
Expected: all pass (CI is main-only; this is the gate).

- [ ] **Step 4: Full backend verification**

Run: `backend/mvnw -q -f backend/pom.xml test`
Expected: BUILD SUCCESS. A single failure in
`com.slparcelauctions.backend.auction.concurrency.BidCancelRaceTest` is the
known pre-existing flake (FOOTGUNS F.117) - re-run it in isolation to
confirm green before treating the suite as passing; it is unrelated to
this work.

- [ ] **Step 5: Commit any sweep/Postman-export changes**

```bash
git add README.md
git commit -m "docs: README sweep for listing/auction UX changes"
```

(Only if the sweep changed something. Never `git add -A` - the repo has
untracked unrelated files.)

---

## Self-review

- Spec coverage: Area 1 (Task 3), Area 2 (Task 4), Area 3 backend (Task 1) +
  frontend (Task 2), Postman/README/verification (Task 5). All spec sections
  mapped.
- Type consistency: `SellerAuctionResponse.seller` (backend record) ↔
  `AuctionSellerSummaryDto` (frontend, already present) ↔ the existing
  `sellerSummary()` computation reused verbatim, so JSON shape is identical
  to the already-working public response. `sellPrice` projection is local to
  `AgentCommissionPreview`; `listingFee`/`onInsufficient` remain
  `startingBid`-derived (decoupling pinned by a test in Task 3).
- No placeholders: every code/label string and test assertion is concrete;
  `renderForm`/test-builder references defer to each file's existing helper
  by design.
- Ordering: Task 1 (backend) precedes Task 2 (frontend Area 3) so the
  frontend test runs against the real enriched shape.
