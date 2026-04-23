# Epic 03 Sub-Spec 2 — Listing UI + Ownership Monitoring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the seller-facing frontend for listing creation/edit/activate + the dashboard My Listings tab, plus the backend ownership-monitoring scheduled job, plus targeted backend tweaks to sub-spec 1.

**Architecture:** Three-page create flow (Configure with progressive disclosure → Review → Submit) + dedicated `/listings/[id]/activate` state machine (fee → method → verify) + wired My Listings dashboard tab. Backend tweaks move `verificationMethod` from create to verify trigger, consolidate failure transitions to `VERIFICATION_FAILED`, and add bid-summary DTO fields. Ownership monitoring is queue-style: a scheduler picks auctions whose `last_ownership_check_at` is older than 30 min and dispatches `@Async` per-listing checks; `SUSPENDED` status + `fraud_flag` entity capture detected ownership changes.

**Tech Stack:** Next.js 16.2.3, React 19, TypeScript 5, Tailwind CSS 4, React Query (TanStack Query), MSW (mock service worker for frontend tests), Vitest + React Testing Library; Spring Boot 4.0.5, Java 26, Spring Data JPA, WireMock (existing), JUnit 5 + Mockito + Testcontainers.

**Source spec:** `docs/superpowers/specs/2026-04-17-epic-03-sub-2-listing-ui-ownership-monitoring.md`

---

## Preflight checks

Before starting Task 1, confirm branch state and dependencies.

- [ ] **Confirm PR #17 status** (Epic 03 sub-spec 1 backend). Run:

```bash
gh pr view 17 --json state,mergedAt,title,baseRefName
```

- If `mergedAt` is not null → sub-spec 1 code is on `dev`; branch from `dev`.
- If `state == "OPEN"` → branch from `task/03-sub-1-parcel-verification-listing-lifecycle` instead. Rebase onto `dev` later once PR #17 merges.

- [ ] **Create the feature branch**

```bash
cd C:/Users/heath/Repos/Personal/slpa
git fetch origin

# If PR #17 merged:
git checkout dev && git pull --ff-only
git checkout -b task/03-sub-2-listing-ui-ownership-monitoring

# If PR #17 still open:
git checkout task/03-sub-1-parcel-verification-listing-lifecycle
git pull --ff-only
git checkout -b task/03-sub-2-listing-ui-ownership-monitoring

git push -u origin task/03-sub-2-listing-ui-ownership-monitoring
```

- [ ] **Read required docs**

Read `docs/implementation/CONVENTIONS.md`, `docs/implementation/FOOTGUNS.md`, and `docs/implementation/DEFERRED_WORK.md`. Items in `DEFERRED_WORK.md` pointing to Epic 04/05/06/07/09/10/11 are out of scope — this sub-spec is Task 03-05 frontend + Task 03-06 backend plus the backend tweaks listed in the spec §7.

- [ ] **Baseline backend test count**

```bash
cd backend && ./mvnw test -q
```

Expected: `BUILD SUCCESS`, ~413 tests (sub-spec 1 baseline). Record for Task 10 comparison.

- [ ] **Baseline frontend test count and lint**

```bash
cd frontend && npm test -- --run && npm run lint
```

Expected: both pass. Record test count.

- [ ] **Verify dev services are up**

```bash
docker ps --format "table {{.Names}}\t{{.Status}}" | grep -E "slpa-(postgres|redis|minio)"
```

Expected: all three running.

- [ ] **Correctness notes (do NOT treat as optional):**

  1. **`VerificationMethod` is currently nullable per the spec tweak** — Task 1 makes `Auction.verificationMethod` nullable. Sub-spec 1 may have made it non-null; Task 1 relaxes that.
  2. **`LOCKING_STATUSES`** does NOT include `SUSPENDED` — Task 4 adds the new enum value but keeps the locking set unchanged. A suspended parcel becomes re-listable.
  3. **Frontend uses React Query** (TanStack Query v5) per Epic 02 sub-spec 2b. Do not introduce SWR or raw fetch hooks for new queries.
  4. **No emojis in any frontend code.** Icons come from `frontend/src/components/ui/icons.ts` (lucide-react). Verify this file exists; if an icon name is not yet re-exported from there, add it rather than importing lucide directly.
  5. **React 19's `useOptimistic`/`useFormState` are available** but do not introduce them in this sub-spec unless the spec explicitly calls for optimistic UI — stick to React Query patterns matching Epic 02.
  6. **`sessionStorage` is the right scope for draft persistence** — not `localStorage`. Drafts shouldn't leak between tabs or survive browser restarts.
  7. **Photo staging uses `URL.createObjectURL`** for thumbnails; always revoke on unmount to avoid memory leaks.
  8. **Next.js 16.2.3 App Router** — pages are `app/**/page.tsx`. Nested folders with parentheses like `(verified)` are layout groups, not URL segments.
  9. **`open-in-view: false`** is on — backend repository methods that return an entity loaded outside a transaction must use `@EntityGraph` for any association the DTO mapper will touch. Sub-spec 1 already fixed this for `AuctionRepository`; don't regress it.
  10. **`ddl-auto: update`** is on. New columns and new entities will be created automatically; deletion of columns does NOT happen. Removing `verificationMethod` from creation does NOT drop the column — it simply leaves it nullable.

---

## File structure

### New backend files

```
backend/src/main/java/com/slparcelauctions/backend/
├── auction/
│   ├── monitoring/                                                   NEW package
│   │   ├── OwnershipMonitorScheduler.java
│   │   ├── OwnershipCheckTask.java
│   │   ├── OwnershipCheckTimestampInitializer.java
│   │   ├── SuspensionService.java
│   │   └── config/
│   │       └── OwnershipMonitorProperties.java                       @ConfigurationProperties
│   ├── fraud/                                                        NEW package
│   │   ├── FraudFlag.java                                            entity → fraud_flags table
│   │   ├── FraudFlagReason.java                                      enum
│   │   └── FraudFlagRepository.java
│   └── exception/
│       └── GroupLandRequiresSaleToBotException.java                  NEW (422)
├── config/                                                            NEW package
│   ├── PublicConfigController.java                                    GET /config/listing-fee
│   └── dto/
│       └── ListingFeeConfigResponse.java
└── dev/
    └── DevOwnershipMonitorController.java                             @Profile("dev")
```

### Modified backend files

- `backend/src/main/java/.../auction/Auction.java` — Task 1 makes `verificationMethod` nullable; Task 4 adds `lastOwnershipCheckAt: Instant?` and `consecutiveWorldApiFailures: int` fields.
- `backend/src/main/java/.../auction/AuctionStatus.java` — Task 4 adds `SUSPENDED`.
- `backend/src/main/java/.../auction/dto/AuctionCreateRequest.java` — Task 1 removes `verificationMethod` field.
- `backend/src/main/java/.../auction/dto/AuctionVerifyRequest.java` — NEW (Task 1) — body for verify trigger.
- `backend/src/main/java/.../auction/dto/SellerAuctionResponse.java` — Task 3 adds `currentHighBid: BigDecimal?` and `bidderCount: Long`.
- `backend/src/main/java/.../auction/dto/PublicAuctionResponse.java` — Task 3 adds the same two fields.
- `backend/src/main/java/.../auction/AuctionDtoMapper.java` — Task 3 wires the new fields.
- `backend/src/main/java/.../auction/AuctionService.java` — Task 1 stops writing `verificationMethod` on create.
- `backend/src/main/java/.../auction/AuctionVerificationService.java` — Task 1 accepts `method` on trigger; Task 2 ensures `verificationNotes` is populated on failure paths.
- `backend/src/main/java/.../auction/AuctionController.java` — Task 1 accepts `AuctionVerifyRequest` body on `PUT /verify`.
- `backend/src/main/java/.../auction/scheduled/ParcelCodeExpiryJob.java` — Task 2 transitions to `VERIFICATION_FAILED` (not `DRAFT_PAID`) with notes.
- `backend/src/main/java/.../auction/exception/AuctionExceptionHandler.java` — Task 1 adds `GroupLandRequiresSaleToBotException` handler.
- `backend/src/main/resources/application.yml` — Task 4 adds `slpa.ownership-monitor.*` block.

### New frontend files

```
frontend/src/
├── app/
│   ├── listings/
│   │   ├── create/
│   │   │   └── page.tsx                                              wizard host (Configure + Review)
│   │   └── [id]/
│   │       ├── edit/
│   │       │   └── page.tsx
│   │       └── activate/
│   │           └── page.tsx
│   └── dashboard/(verified)/listings/
│       └── page.tsx                                                  MODIFY (was EmptyState)
├── components/
│   ├── listing/                                                      NEW package
│   │   ├── ParcelLookupField.tsx
│   │   ├── ParcelLookupCard.tsx
│   │   ├── AuctionSettingsForm.tsx
│   │   ├── TagSelector.tsx
│   │   ├── PhotoUploader.tsx
│   │   ├── ListingPreviewCard.tsx
│   │   ├── ListingWizardLayout.tsx
│   │   ├── ActivateStatusStepper.tsx
│   │   ├── FeePaymentInstructions.tsx
│   │   ├── VerificationMethodPicker.tsx
│   │   ├── VerificationInProgressPanel.tsx
│   │   ├── VerificationMethodUuidEntry.tsx                           sub-panel for UUID_ENTRY
│   │   ├── VerificationMethodRezzable.tsx                            sub-panel for REZZABLE
│   │   ├── VerificationMethodSaleToBot.tsx                           sub-panel for SALE_TO_BOT
│   │   ├── CancelListingModal.tsx
│   │   ├── ListingStatusBadge.tsx
│   │   ├── ListingSummaryRow.tsx
│   │   ├── MyListingsTab.tsx
│   │   └── FilterChipsRow.tsx
│   └── ui/
│       ├── Stepper.tsx                                               NEW primitive
│       └── DropZone.tsx                                              NEW primitive
├── hooks/
│   ├── useListingDraft.ts
│   ├── useActivateAuction.ts
│   ├── useMyListings.ts
│   └── useListingFeeConfig.ts
├── lib/
│   ├── api/
│   │   ├── parcels.ts                                                lookup call
│   │   ├── auctions.ts                                               CRUD calls
│   │   ├── parcelTags.ts
│   │   ├── auctionPhotos.ts
│   │   └── config.ts                                                 listing-fee
│   └── listing/
│       ├── auctionStatus.ts                                          type guards + status grouping
│       ├── refundCalculation.ts                                      pure function
│       └── photoStaging.ts                                           client-side staging helpers
└── types/
    ├── auction.ts                                                    AuctionStatus, DTOs matching backend
    ├── parcel.ts
    └── parcelTag.ts
```

### Modified frontend files

- `frontend/src/app/dashboard/(verified)/listings/page.tsx` — replaces `<EmptyState>` with `<MyListingsTab />`.
- `frontend/src/components/ui/icons.ts` — Task 6 re-exports any new icons used (e.g., `ImagePlus`, `UploadCloud`, `AlertTriangle`, `Gavel`, `Building2`).

---

## Task index

1. Backend tweak: move `verificationMethod` from create to verify + `GroupLandRequiresSaleToBotException` + listing-fee config endpoint
2. Backend tweak: consolidate verification-failure transitions + `verificationNotes`
3. Backend tweak: add `currentHighBid` + `bidderCount` to DTOs + mapper
4. Backend Task 06 foundation: `SUSPENDED` + `Auction` fields + `FraudFlag` entity + config
5. Backend Task 06 workers: scheduler + async check task + `SuspensionService` + jitter initializer + dev admin endpoint + integration tests
6. Frontend UI primitives: `Stepper` + `DropZone` + icons
7. Frontend listing components: lookup/settings/tags/photos/preview/wizard layout
8. Frontend create + edit pages + `useListingDraft`
9. Frontend activate page + method-specific panels + `useActivateAuction` + `CancelListingModal`
10. Frontend My Listings tab + full-flow smoke test + README + FOOTGUNS + DEFERRED_WORK + PR

Each task has self-contained tests, builds on the prior tasks' outputs, and ends with a commit.

---

## Task 1 — Move `verificationMethod` from create to verify; add `GroupLandRequiresSaleToBotException`; add listing-fee config endpoint

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/Auction.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/dto/AuctionCreateRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/dto/AuctionVerifyRequest.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionService.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionVerificationService.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionController.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/exception/GroupLandRequiresSaleToBotException.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/exception/AuctionExceptionHandler.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/config/PublicConfigController.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/config/dto/ListingFeeConfigResponse.java`
- Modify: existing sub-spec 1 tests touching `AuctionCreateRequest.verificationMethod` and `PUT /verify`

- [ ] **Step 1.1: Make `Auction.verificationMethod` nullable on the entity**

Open `Auction.java` and change the field annotation:

```java
@Enumerated(EnumType.STRING)
@Column(name = "verification_method", nullable = true)   // was nullable = false
private VerificationMethod verificationMethod;
```

With `ddl-auto: update`, the column's NOT NULL constraint is relaxed on next boot.

- [ ] **Step 1.2: Remove `verificationMethod` from `AuctionCreateRequest`**

Delete the field and its validation annotation. The record/class now exposes only the seller's listing-time inputs (`parcelId`, pricing, duration, snipe, description, tags[]).

- [ ] **Step 1.3: Write failing test for create-without-method**

In `backend/src/test/java/.../auction/AuctionServiceTest.java`:

```java
@Test
void create_persistsAuctionWithNullVerificationMethod() {
    UUID parcelId = givenVerifiedParcelForSeller();
    AuctionCreateRequest req = AuctionCreateRequest.builder()
        .parcelId(parcelId)
        .startingBid(BigDecimal.valueOf(500))
        .durationHours(72)
        .snipeProtect(true)
        .snipeWindowMin(10)
        .sellerDesc("Test")
        .tags(List.of())
        .build();

    Auction created = auctionService.create(req, sellerId);

    assertThat(created.getStatus()).isEqualTo(AuctionStatus.DRAFT);
    assertThat(created.getVerificationMethod()).isNull();
}
```

- [ ] **Step 1.4: Run and verify it fails**

```bash
cd backend && ./mvnw test -Dtest=AuctionServiceTest#create_persistsAuctionWithNullVerificationMethod
```

Expected: compilation error (`AuctionCreateRequest` builder still has `.verificationMethod`) or test failure.

- [ ] **Step 1.5: Update `AuctionService.create` to not write verificationMethod**

Remove any line setting `auction.setVerificationMethod(...)` in the create path. Leave it null.

- [ ] **Step 1.6: Run the test and verify it passes**

```bash
./mvnw test -Dtest=AuctionServiceTest#create_persistsAuctionWithNullVerificationMethod
```

Expected: PASS.

- [ ] **Step 1.7: Create `AuctionVerifyRequest` DTO**

```java
package com.slparcelauctions.backend.auction.dto;

import com.slparcelauctions.backend.auction.VerificationMethod;
import jakarta.validation.constraints.NotNull;

public record AuctionVerifyRequest(
    @NotNull VerificationMethod method
) {}
```

- [ ] **Step 1.8: Create `GroupLandRequiresSaleToBotException`**

```java
package com.slparcelauctions.backend.auction.exception;

public class GroupLandRequiresSaleToBotException extends RuntimeException {
    public GroupLandRequiresSaleToBotException() {
        super("Group-owned land requires the Sale-to-bot verification method.");
    }
}
```

- [ ] **Step 1.9: Add handler in `AuctionExceptionHandler`**

```java
@ExceptionHandler(GroupLandRequiresSaleToBotException.class)
public ResponseEntity<ProblemDetail> handleGroupLand(GroupLandRequiresSaleToBotException ex) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(
        HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    pd.setTitle("Group-owned land requires Sale-to-bot");
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(pd);
}
```

- [ ] **Step 1.10: Write failing tests for verify-with-method body**

Add to `AuctionVerificationServiceTest.java`:

```java
@Test
void triggerVerification_acceptsMethodFromRequest_andPersistsIt() {
    Auction draftPaid = givenDraftPaidAuctionForIndividuallyOwnedParcel();
    Auction result = verificationService.triggerVerification(
        draftPaid.getId(), VerificationMethod.UUID_ENTRY, draftPaid.getSeller().getId());
    assertThat(result.getVerificationMethod()).isEqualTo(VerificationMethod.UUID_ENTRY);
}

@Test
void triggerVerification_groupOwned_nonSaleToBotMethod_throws422() {
    Auction draftPaid = givenDraftPaidAuctionForGroupOwnedParcel();
    assertThatThrownBy(() -> verificationService.triggerVerification(
        draftPaid.getId(), VerificationMethod.UUID_ENTRY, draftPaid.getSeller().getId()))
        .isInstanceOf(GroupLandRequiresSaleToBotException.class);
}

@Test
void triggerVerification_groupOwned_saleToBot_succeeds() {
    Auction draftPaid = givenDraftPaidAuctionForGroupOwnedParcel();
    Auction result = verificationService.triggerVerification(
        draftPaid.getId(), VerificationMethod.SALE_TO_BOT, draftPaid.getSeller().getId());
    assertThat(result.getStatus()).isEqualTo(AuctionStatus.VERIFICATION_PENDING);
}
```

- [ ] **Step 1.11: Run and verify they fail**

```bash
./mvnw test -Dtest=AuctionVerificationServiceTest#triggerVerification_acceptsMethodFromRequest_andPersistsIt
```

Expected: compilation error (signature mismatch — method param).

- [ ] **Step 1.12: Update `AuctionVerificationService.triggerVerification` signature**

Change signature from `triggerVerification(UUID auctionId, UUID sellerId)` to `triggerVerification(UUID auctionId, VerificationMethod method, UUID sellerId)`. Inside:

```java
public Auction triggerVerification(UUID auctionId, VerificationMethod method, UUID sellerId) {
    Auction auction = findAndAuthorize(auctionId, sellerId);
    assertStateAllowsVerify(auction);  // allows DRAFT_PAID or VERIFICATION_FAILED

    // Group-owned land gate
    if ("group".equalsIgnoreCase(auction.getParcel().getOwnerType())
        && method != VerificationMethod.SALE_TO_BOT) {
        throw new GroupLandRequiresSaleToBotException();
    }

    auction.setVerificationMethod(method);
    auction.setStatus(AuctionStatus.VERIFICATION_PENDING);
    auction.setVerificationNotes(null);   // clear stale failure notes on retry

    switch (method) {
        case UUID_ENTRY -> dispatchUuidEntry(auction);
        case REZZABLE   -> dispatchRezzable(auction);
        case SALE_TO_BOT -> dispatchSaleToBot(auction);
    }
    return auctionRepo.save(auction);
}
```

- [ ] **Step 1.13: Update controller to accept `AuctionVerifyRequest`**

In `AuctionController.java`:

```java
@PutMapping("/{id}/verify")
public SellerAuctionResponse triggerVerify(
        @PathVariable UUID id,
        @Valid @RequestBody AuctionVerifyRequest body,
        @AuthenticationPrincipal AuthenticatedUser user) {
    Auction auction = verificationService.triggerVerification(id, body.method(), user.getId());
    return mapper.toSeller(auction);
}
```

- [ ] **Step 1.14: Update existing sub-spec 1 tests that called verify without a body**

Update each `mockMvc.perform(put("/api/v1/auctions/" + id + "/verify"))` to include the request body:

```java
mockMvc.perform(put("/api/v1/auctions/" + id + "/verify")
    .contentType(MediaType.APPLICATION_JSON)
    .content("{\"method\":\"UUID_ENTRY\"}"))
    .andExpect(status().isOk());
```

Search for `/verify` in `AuctionControllerTest.java`, `AuctionVerificationServiceIntegrationTest.java`, `FullFlowSmokeTest.java`, etc.

- [ ] **Step 1.15: Run all verification-related tests**

```bash
./mvnw test -Dtest='*VerificationService*Test,AuctionControllerTest,FullFlowSmokeTest'
```

Expected: ALL PASS.

- [ ] **Step 1.16: Create `ListingFeeConfigResponse` DTO**

```java
package com.slparcelauctions.backend.config.dto;

public record ListingFeeConfigResponse(long amountLindens) {}
```

- [ ] **Step 1.17: Create `PublicConfigController`**

```java
package com.slparcelauctions.backend.config;

import com.slparcelauctions.backend.config.dto.ListingFeeConfigResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/config")
public class PublicConfigController {

    private final long listingFeeLindens;

    public PublicConfigController(@Value("${slpa.listing-fee.amount-lindens:100}") long listingFeeLindens) {
        this.listingFeeLindens = listingFeeLindens;
    }

    @GetMapping("/listing-fee")
    public ListingFeeConfigResponse listingFee() {
        return new ListingFeeConfigResponse(listingFeeLindens);
    }
}
```

- [ ] **Step 1.18: Ensure the endpoint is permitAll in security config**

Open `backend/src/main/java/.../config/SecurityConfig.java` (or equivalent). Add:

```java
.requestMatchers(HttpMethod.GET, "/api/v1/config/listing-fee").permitAll()
```

- [ ] **Step 1.19: Write controller test**

```java
@WebMvcTest(PublicConfigController.class)
class PublicConfigControllerTest {
    @Autowired MockMvc mvc;

    @Test
    void listingFee_returnsConfiguredAmount() throws Exception {
        mvc.perform(get("/api/v1/config/listing-fee"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.amountLindens").isNumber());
    }
}
```

- [ ] **Step 1.20: Run full backend suite**

```bash
./mvnw test
```

Expected: BUILD SUCCESS, test count = baseline + ~5 new tests.

- [ ] **Step 1.21: Commit**

```bash
git add backend/
git commit -m "feat(auction): move verificationMethod from create to verify trigger, add group-land gate, listing-fee config endpoint

- AuctionCreateRequest no longer accepts verificationMethod; set on
  PUT /auctions/{id}/verify instead via new AuctionVerifyRequest body
- new GroupLandRequiresSaleToBotException (422) when a non-SALE_TO_BOT
  method is chosen for group-owned land
- new GET /api/v1/config/listing-fee returning the configured amount
- Auction.verificationMethod relaxed to nullable (set at verify time)"
```

---

## Task 2 — Consolidate verification-failure transitions to `VERIFICATION_FAILED` with `verificationNotes`

**Files:**
- Modify: `backend/src/main/java/.../auction/scheduled/ParcelCodeExpiryJob.java`
- Modify: `backend/src/main/java/.../auction/scheduled/BotTaskTimeoutJob.java`
- Modify: `backend/src/main/java/.../auction/AuctionVerificationService.java` (confirm notes set)
- Modify: related tests

- [ ] **Step 2.1: Write failing test for `ParcelCodeExpiryJob` → VERIFICATION_FAILED**

In `ParcelCodeExpiryJobTest.java`:

```java
@Test
void run_whenRezzableCodeExpired_transitionsToVerificationFailed_withNotes() {
    Auction auction = givenRezzableAuctionWithExpiredCode();
    job.run();
    Auction reloaded = auctionRepo.findById(auction.getId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(AuctionStatus.VERIFICATION_FAILED);
    assertThat(reloaded.getVerificationNotes())
        .contains("code expired");
}
```

- [ ] **Step 2.2: Run test, verify it fails**

```bash
./mvnw test -Dtest=ParcelCodeExpiryJobTest#run_whenRezzableCodeExpired_transitionsToVerificationFailed_withNotes
```

Expected: FAIL — currently transitions to DRAFT_PAID.

- [ ] **Step 2.3: Update `ParcelCodeExpiryJob`**

Change the transition:

```java
auction.setStatus(AuctionStatus.VERIFICATION_FAILED);   // was DRAFT_PAID
auction.setVerificationNotes(
    "Method B code expired before the parcel terminal reported back. You can retry at no extra cost.");
```

Also remove any `ListingFeeRefund` creation in this path if present (spec §7.3).

- [ ] **Step 2.4: Run test, verify it passes**

Expected: PASS.

- [ ] **Step 2.5: Write failing test for `BotTaskTimeoutJob` notes**

```java
@Test
void run_botTaskOver48h_setsVerificationFailedWithNotes() {
    Auction auction = givenSaleToBotAuctionWithStaleBotTask();
    botTimeoutJob.run();
    Auction reloaded = auctionRepo.findById(auction.getId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(AuctionStatus.VERIFICATION_FAILED);
    assertThat(reloaded.getVerificationNotes())
        .contains("Sale-to-bot task timed out");
}
```

- [ ] **Step 2.6: Update `BotTaskTimeoutJob`**

```java
auction.setStatus(AuctionStatus.VERIFICATION_FAILED);
auction.setVerificationNotes(
    "Sale-to-bot task timed out after 48 hours without a match. You can retry at no extra cost.");
```

- [ ] **Step 2.7: Write failing test for Method A sync failure notes**

```java
@Test
void triggerVerification_uuidEntry_ownershipMismatch_setsNotes() {
    Auction draftPaid = givenDraftPaidAuctionForParcelOwnedBySomeoneElse();
    Auction result = verificationService.triggerVerification(
        draftPaid.getId(), VerificationMethod.UUID_ENTRY, draftPaid.getSeller().getId());
    assertThat(result.getStatus()).isEqualTo(AuctionStatus.VERIFICATION_FAILED);
    assertThat(result.getVerificationNotes())
        .contains("owner UUID doesn't match");
}
```

- [ ] **Step 2.8: Run, verify it fails (or passes if sub-spec 1 already set notes). Adjust accordingly**

```bash
./mvnw test -Dtest=AuctionVerificationServiceTest#triggerVerification_uuidEntry_ownershipMismatch_setsNotes
```

- [ ] **Step 2.9: In `AuctionVerificationService.dispatchUuidEntry` failure branch, ensure notes are set**

```java
// On ownership mismatch:
auction.setStatus(AuctionStatus.VERIFICATION_FAILED);
auction.setVerificationNotes(
    "Ownership check failed: the parcel's owner UUID doesn't match your avatar. Pick another method or correct the UUID.");
// No ListingFeeRefund creation here. Refund only happens via cancel.
```

- [ ] **Step 2.10: Assert no `ListingFeeRefund` rows are created on failure paths**

Add to the same three tests:

```java
assertThat(refundRepo.findByAuctionId(auction.getId())).isEmpty();
```

- [ ] **Step 2.11: Run related tests**

```bash
./mvnw test -Dtest='ParcelCodeExpiryJobTest,BotTaskTimeoutJobTest,AuctionVerificationServiceTest'
```

Expected: all PASS.

- [ ] **Step 2.12: Run full backend suite**

```bash
./mvnw test
```

Expected: BUILD SUCCESS.

- [ ] **Step 2.13: Commit**

```bash
git add backend/
git commit -m "fix(auction): consolidate verification failures to VERIFICATION_FAILED with retry-friendly notes

- ParcelCodeExpiryJob: now transitions REZZABLE expiries to
  VERIFICATION_FAILED (was DRAFT_PAID) with notes
- BotTaskTimeoutJob: populates verificationNotes for SALE_TO_BOT
  timeouts
- AuctionVerificationService: populates verificationNotes for
  synchronous UUID_ENTRY ownership-mismatch failures
- no failure path creates a ListingFeeRefund — refund happens only
  via explicit cancel"
```

---

## Task 3 — Add `currentHighBid` and `bidderCount` to auction DTOs

**Files:**
- Modify: `backend/src/main/java/.../auction/dto/SellerAuctionResponse.java`
- Modify: `backend/src/main/java/.../auction/dto/PublicAuctionResponse.java`
- Modify: `backend/src/main/java/.../auction/AuctionDtoMapper.java`
- Modify: existing DTO-shape tests

- [ ] **Step 3.1: Write failing test asserting new fields in `SellerAuctionResponse`**

In `AuctionDtoMapperTest.java`:

```java
@Test
void toSeller_includesBidSummaryFieldsZeroedWhenNoBids() {
    Auction auction = givenDraftAuction();
    SellerAuctionResponse dto = mapper.toSeller(auction);
    assertThat(dto.currentHighBid()).isNull();
    assertThat(dto.bidderCount()).isEqualTo(0L);
}
```

- [ ] **Step 3.2: Run, verify it fails (field doesn't exist)**

```bash
./mvnw test -Dtest=AuctionDtoMapperTest#toSeller_includesBidSummaryFieldsZeroedWhenNoBids
```

Expected: compilation error.

- [ ] **Step 3.3: Add fields to `SellerAuctionResponse`**

Add two fields to the record/class:

```java
BigDecimal currentHighBid,   // nullable
Long bidderCount             // default 0 via mapper
```

Keep the overall builder + factory order consistent with the existing DTO.

- [ ] **Step 3.4: Add same fields to `PublicAuctionResponse`**

Same two fields.

- [ ] **Step 3.5: Update `AuctionDtoMapper` to populate them**

In both `toSeller` and `toPublic`:

```java
.currentHighBid(auction.getCurrentBid())       // entity field exists per sub-spec 1
.bidderCount(auction.getBidCount() == null ? 0L : auction.getBidCount().longValue())
```

Null-safe — the sub-spec 1 entity has these as defaults, but defensive code protects against historical rows.

- [ ] **Step 3.6: Run mapper tests**

```bash
./mvnw test -Dtest=AuctionDtoMapperTest
```

Expected: PASS.

- [ ] **Step 3.7: Write public DTO test**

```java
@Test
void toPublic_active_includesBidSummary() {
    Auction auction = givenActiveAuctionWithNoBids();
    PublicAuctionResponse dto = mapper.toPublic(auction);
    assertThat(dto.currentHighBid()).isNull();
    assertThat(dto.bidderCount()).isEqualTo(0L);
}
```

- [ ] **Step 3.8: Run controller-level tests that assert DTO shape**

```bash
./mvnw test -Dtest='AuctionControllerTest,AuctionPublicViewTest'
```

Any failing tests that assert on JSON shape with strict matching need `currentHighBid` and `bidderCount` added. Expected: PASS after updates.

- [ ] **Step 3.9: Run full suite**

```bash
./mvnw test
```

Expected: BUILD SUCCESS.

- [ ] **Step 3.10: Commit**

```bash
git add backend/
git commit -m "feat(auction): add currentHighBid and bidderCount to Seller/Public auction DTOs

- both DTOs gain currentHighBid (nullable BigDecimal) and
  bidderCount (Long, default 0)
- mapper reads from the entity's existing currentBid and bidCount
- values remain null/0 until Epic 04 (Auction Engine) populates bids"
```

---

## Task 4 — Ownership-monitoring foundation: `SUSPENDED` status + `Auction` fields + `FraudFlag` entity + config

**Files:**
- Modify: `backend/src/main/java/.../auction/AuctionStatus.java`
- Modify: `backend/src/main/java/.../auction/Auction.java`
- Create: `backend/src/main/java/.../auction/fraud/FraudFlag.java`
- Create: `backend/src/main/java/.../auction/fraud/FraudFlagReason.java`
- Create: `backend/src/main/java/.../auction/fraud/FraudFlagRepository.java`
- Create: `backend/src/main/java/.../auction/monitoring/config/OwnershipMonitorProperties.java`
- Modify: `backend/src/main/resources/application.yml`

- [ ] **Step 4.1: Add `SUSPENDED` to `AuctionStatus` enum**

```java
public enum AuctionStatus {
    DRAFT, DRAFT_PAID,
    VERIFICATION_PENDING, VERIFICATION_FAILED,
    ACTIVE, ENDED,
    ESCROW_PENDING, ESCROW_FUNDED, TRANSFER_PENDING,
    COMPLETED, CANCELLED, EXPIRED, DISPUTED,
    SUSPENDED                                  // NEW
}
```

- [ ] **Step 4.2: Verify `AuctionStatusConstants.LOCKING_STATUSES` does NOT include SUSPENDED**

Open `AuctionStatusConstants.java`. The `LOCKING_STATUSES` set must remain:

```java
public static final Set<AuctionStatus> LOCKING_STATUSES = EnumSet.of(
    AuctionStatus.ACTIVE,
    AuctionStatus.ENDED,
    AuctionStatus.ESCROW_PENDING,
    AuctionStatus.ESCROW_FUNDED,
    AuctionStatus.TRANSFER_PENDING,
    AuctionStatus.DISPUTED
);
```

No change — suspension releases the parcel for re-listing.

- [ ] **Step 4.3: Add new columns to `Auction.java`**

```java
@Column(name = "last_ownership_check_at")
private Instant lastOwnershipCheckAt;

@Column(name = "consecutive_world_api_failures", nullable = false)
@Builder.Default
private Integer consecutiveWorldApiFailures = 0;
```

Hibernate ddl-auto adds both columns on next boot.

- [ ] **Step 4.4: Create `FraudFlagReason` enum**

```java
package com.slparcelauctions.backend.auction.fraud;

public enum FraudFlagReason {
    OWNERSHIP_CHANGED_TO_UNKNOWN,
    PARCEL_DELETED_OR_MERGED,
    WORLD_API_FAILURE_THRESHOLD
}
```

- [ ] **Step 4.5: Create `FraudFlag` entity**

```java
package com.slparcelauctions.backend.auction.fraud;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "fraud_flags")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FraudFlag {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "auction_id")
    private Auction auction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "parcel_id", nullable = false)
    private Parcel parcel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FraudFlagReason reason;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Column(name = "evidence_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> evidenceJson;

    @Column(nullable = false)
    @Builder.Default
    private boolean resolved = false;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_by_user_id")
    private User resolvedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
```

- [ ] **Step 4.6: Create `FraudFlagRepository`**

```java
package com.slparcelauctions.backend.auction.fraud;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface FraudFlagRepository extends JpaRepository<FraudFlag, UUID> {
    List<FraudFlag> findByAuctionId(UUID auctionId);
}
```

- [ ] **Step 4.7: Create `OwnershipMonitorProperties`**

```java
package com.slparcelauctions.backend.auction.monitoring.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import lombok.Getter;
import lombok.Setter;

import java.time.Duration;

@Configuration
@ConfigurationProperties(prefix = "slpa.ownership-monitor")
@Getter @Setter
public class OwnershipMonitorProperties {
    private boolean enabled = true;
    private int checkIntervalMinutes = 30;
    private Duration schedulerFrequency = Duration.ofSeconds(30);
    private int jitterMaxMinutes = 5;
}
```

- [ ] **Step 4.8: Add YAML block to `application.yml`**

```yaml
slpa:
  ownership-monitor:
    enabled: true
    check-interval-minutes: 30
    scheduler-frequency: PT30S
    jitter-max-minutes: 5
```

Place under the existing `slpa:` root.

- [ ] **Step 4.9: Write entity persistence test**

Create `FraudFlagRepositoryTest.java`:

```java
@DataJpaTest
@Testcontainers
class FraudFlagRepositoryTest {
    // ...standard Testcontainers Postgres setup matching other repo tests...

    @Autowired FraudFlagRepository repo;
    @Autowired TestEntityManager em;

    @Test
    void save_persistsWithJsonbEvidence() {
        Parcel parcel = TestFixtures.persistParcel(em);
        Auction auction = TestFixtures.persistDraftAuction(em, parcel);
        FraudFlag flag = FraudFlag.builder()
            .auction(auction)
            .parcel(parcel)
            .reason(FraudFlagReason.OWNERSHIP_CHANGED_TO_UNKNOWN)
            .detectedAt(Instant.now())
            .createdAt(Instant.now())
            .evidenceJson(Map.of("expected_owner", "abc", "detected_owner", "xyz"))
            .build();

        FraudFlag saved = repo.save(flag);
        em.flush();
        em.clear();

        FraudFlag loaded = repo.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getEvidenceJson()).containsEntry("expected_owner", "abc");
    }
}
```

- [ ] **Step 4.10: Run the test**

```bash
./mvnw test -Dtest=FraudFlagRepositoryTest
```

Expected: PASS.

- [ ] **Step 4.11: Boot the app to confirm schema updates cleanly**

```bash
./mvnw spring-boot:run
```

Wait for `Started BackendApplication`. Check logs for ddl-auto additions. Ctrl+C to stop.

- [ ] **Step 4.12: Run full backend suite**

```bash
./mvnw test
```

Expected: BUILD SUCCESS.

- [ ] **Step 4.13: Commit**

```bash
git add backend/
git commit -m "feat(monitoring): add SUSPENDED status, ownership-check Auction fields, FraudFlag entity

- AuctionStatus gains SUSPENDED (not in LOCKING_STATUSES — suspension
  releases the parcel)
- Auction gains lastOwnershipCheckAt and consecutiveWorldApiFailures
- new fraud_flag entity with reason enum, jsonb evidence column,
  resolution fields for Epic 10 admin dashboard
- OwnershipMonitorProperties + application.yml block"
```

---

## Task 5 — Ownership-monitoring workers: scheduler + async check task + `SuspensionService` + jitter initializer + dev endpoint + integration tests

**Files:**
- Create: `backend/src/main/java/.../auction/monitoring/OwnershipMonitorScheduler.java`
- Create: `backend/src/main/java/.../auction/monitoring/OwnershipCheckTask.java`
- Create: `backend/src/main/java/.../auction/monitoring/SuspensionService.java`
- Create: `backend/src/main/java/.../auction/monitoring/OwnershipCheckTimestampInitializer.java`
- Create: `backend/src/main/java/.../dev/DevOwnershipMonitorController.java`
- Modify: `backend/src/main/java/.../auction/AuctionRepository.java` (add `findDueForOwnershipCheck`)
- Modify: `backend/src/main/java/.../auction/AuctionVerificationService.java` (call initializer on ACTIVE transition)
- Modify: `backend/src/main/java/.../sl/SlParcelVerifyService.java` (call initializer on ACTIVE)
- Modify: `backend/src/main/java/.../bot/BotTaskService.java` (call initializer on ACTIVE)
- Modify: `backend/src/main/java/.../BackendApplication.java` (ensure `@EnableAsync` + `@EnableScheduling` present)

- [ ] **Step 5.1: Ensure `@EnableAsync` and `@EnableScheduling` are on the application class**

Open `BackendApplication.java`. If either annotation is missing, add it:

```java
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class BackendApplication { ... }
```

(Sub-spec 1 likely added `@EnableScheduling`; verify `@EnableAsync` is also there — async dispatch depends on it.)

- [ ] **Step 5.2: Add `findDueForOwnershipCheck` to `AuctionRepository`**

```java
@Query("""
  SELECT a.id FROM Auction a
  WHERE a.status = com.slparcelauctions.backend.auction.AuctionStatus.ACTIVE
    AND (a.lastOwnershipCheckAt IS NULL OR a.lastOwnershipCheckAt <= :cutoff)
  ORDER BY a.lastOwnershipCheckAt ASC NULLS FIRST
""")
List<UUID> findDueForOwnershipCheck(@Param("cutoff") Instant cutoff);
```

- [ ] **Step 5.3: Create `SuspensionService`**

```java
package com.slparcelauctions.backend.auction.monitoring;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.fraud.FraudFlag;
import com.slparcelauctions.backend.auction.fraud.FraudFlagReason;
import com.slparcelauctions.backend.auction.fraud.FraudFlagRepository;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SuspensionService {

    private final AuctionRepository auctionRepo;
    private final FraudFlagRepository fraudFlagRepo;

    @Transactional
    public void suspendForOwnershipChange(Auction auction, ParcelMetadata evidence) {
        auction.setStatus(AuctionStatus.SUSPENDED);
        auction.setLastOwnershipCheckAt(Instant.now());
        auctionRepo.save(auction);

        fraudFlagRepo.save(FraudFlag.builder()
            .auction(auction)
            .parcel(auction.getParcel())
            .reason(FraudFlagReason.OWNERSHIP_CHANGED_TO_UNKNOWN)
            .detectedAt(Instant.now())
            .createdAt(Instant.now())
            .evidenceJson(Map.of(
                "expected_owner", auction.getSeller().getSlAvatarUuid().toString(),
                "detected_owner", String.valueOf(evidence.ownerUuid()),
                "parcel_uuid", auction.getParcel().getSlParcelUuid().toString()))
            .build());
    }

    @Transactional
    public void suspendForDeletedParcel(Auction auction) {
        auction.setStatus(AuctionStatus.SUSPENDED);
        auction.setLastOwnershipCheckAt(Instant.now());
        auctionRepo.save(auction);

        fraudFlagRepo.save(FraudFlag.builder()
            .auction(auction)
            .parcel(auction.getParcel())
            .reason(FraudFlagReason.PARCEL_DELETED_OR_MERGED)
            .detectedAt(Instant.now())
            .createdAt(Instant.now())
            .evidenceJson(Map.of(
                "parcel_uuid", auction.getParcel().getSlParcelUuid().toString()))
            .build());
    }
}
```

Replace `ParcelMetadata` with the actual DTO that `SlWorldApiClient.fetchParcel(...)` returns in sub-spec 1. Verify the field name for owner UUID on that DTO (it may be `ownerUuid()` accessor or `ownerid`).

- [ ] **Step 5.4: Create `OwnershipCheckTask`**

```java
package com.slparcelauctions.backend.auction.monitoring;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
import com.slparcelauctions.backend.sl.exception.ExternalApiTimeoutException;
import com.slparcelauctions.backend.sl.exception.ParcelNotFoundInSlException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OwnershipCheckTask {

    private final AuctionRepository auctionRepo;
    private final SlWorldApiClient worldApi;
    private final SuspensionService suspensionService;

    @Async
    @Transactional
    public void checkOne(UUID auctionId) {
        Auction auction = auctionRepo.findById(auctionId).orElse(null);
        if (auction == null || auction.getStatus() != AuctionStatus.ACTIVE) return;

        try {
            ParcelMetadata result = worldApi.fetchParcel(auction.getParcel().getSlParcelUuid());
            String expectedOwner = auction.getSeller().getSlAvatarUuid().toString();
            String actualOwner = String.valueOf(result.ownerUuid());
            if (expectedOwner.equalsIgnoreCase(actualOwner)) {
                auction.setLastOwnershipCheckAt(Instant.now());
                auction.setConsecutiveWorldApiFailures(0);
                auctionRepo.save(auction);
            } else {
                suspensionService.suspendForOwnershipChange(auction, result);
            }
        } catch (ParcelNotFoundInSlException e) {
            suspensionService.suspendForDeletedParcel(auction);
        } catch (ExternalApiTimeoutException e) {
            auction.setConsecutiveWorldApiFailures(auction.getConsecutiveWorldApiFailures() + 1);
            auction.setLastOwnershipCheckAt(Instant.now());
            auctionRepo.save(auction);
            log.warn("World API timeout for auction {} (consecutive={})",
                auctionId, auction.getConsecutiveWorldApiFailures());
        } catch (Exception e) {
            log.error("Unexpected error checking auction {}: {}", auctionId, e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 5.5: Create `OwnershipMonitorScheduler`**

```java
package com.slparcelauctions.backend.auction.monitoring;

import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.monitoring.config.OwnershipMonitorProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@ConditionalOnProperty(value = "slpa.ownership-monitor.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class OwnershipMonitorScheduler {

    private final AuctionRepository auctionRepo;
    private final OwnershipCheckTask ownershipCheckTask;
    private final OwnershipMonitorProperties props;

    @Scheduled(fixedDelayString = "${slpa.ownership-monitor.scheduler-frequency:PT30S}")
    public void dispatchDueChecks() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(props.getCheckIntervalMinutes()));
        List<UUID> dueIds = auctionRepo.findDueForOwnershipCheck(cutoff);
        if (dueIds.isEmpty()) return;
        log.debug("Dispatching ownership checks for {} auctions", dueIds.size());
        for (UUID id : dueIds) {
            ownershipCheckTask.checkOne(id);
        }
    }
}
```

- [ ] **Step 5.6: Create `OwnershipCheckTimestampInitializer`**

```java
package com.slparcelauctions.backend.auction.monitoring;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.monitoring.config.OwnershipMonitorProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

@Component
@RequiredArgsConstructor
public class OwnershipCheckTimestampInitializer {

    private final OwnershipMonitorProperties props;

    public void onActivated(Auction auction) {
        int jitter = props.getJitterMaxMinutes();
        long offsetMinutes = jitter > 0 ? ThreadLocalRandom.current().nextLong(jitter) : 0;
        auction.setLastOwnershipCheckAt(Instant.now().minus(Duration.ofMinutes(offsetMinutes)));
    }
}
```

- [ ] **Step 5.7: Call the initializer from every ACTIVE transition**

In `AuctionVerificationService` (UUID_ENTRY sync success path): inject `OwnershipCheckTimestampInitializer initializer;` and call `initializer.onActivated(auction);` just before the final save that writes `ACTIVE`.

Same for `SlParcelVerifyService` (REZZABLE callback success path) and `BotTaskService` (SALE_TO_BOT SUCCESS path).

Example:

```java
auction.setStatus(AuctionStatus.ACTIVE);
auction.setVerificationTier(VerificationTier.SCRIPT);   // or BOT / OWNERSHIP_TRANSFER per method
initializer.onActivated(auction);                        // NEW
auctionRepo.save(auction);
```

- [ ] **Step 5.8: Create `DevOwnershipMonitorController`**

```java
package com.slparcelauctions.backend.dev;

import com.slparcelauctions.backend.auction.monitoring.OwnershipMonitorScheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dev/ownership-monitor")
@Profile("dev")
@RequiredArgsConstructor
public class DevOwnershipMonitorController {

    private final OwnershipMonitorScheduler scheduler;

    @PostMapping("/run")
    public ResponseEntity<Void> runNow() {
        scheduler.dispatchDueChecks();
        return ResponseEntity.accepted().build();
    }
}
```

- [ ] **Step 5.9: Write unit test for `OwnershipCheckTask` — owner-match branch**

```java
@ExtendWith(MockitoExtension.class)
class OwnershipCheckTaskTest {

    @Mock AuctionRepository auctionRepo;
    @Mock SlWorldApiClient worldApi;
    @Mock SuspensionService suspensionService;
    @InjectMocks OwnershipCheckTask task;

    @Test
    void checkOne_whenOwnerMatches_updatesTimestampAndResetsFailures() {
        Auction a = TestFixtures.activeAuction();
        a.setConsecutiveWorldApiFailures(3);
        when(auctionRepo.findById(a.getId())).thenReturn(Optional.of(a));
        when(worldApi.fetchParcel(a.getParcel().getSlParcelUuid()))
            .thenReturn(new ParcelMetadata(..., a.getSeller().getSlAvatarUuid(), ...));

        task.checkOne(a.getId());

        assertThat(a.getLastOwnershipCheckAt()).isCloseTo(Instant.now(), within(5, ChronoUnit.SECONDS));
        assertThat(a.getConsecutiveWorldApiFailures()).isZero();
        verify(auctionRepo).save(a);
        verifyNoInteractions(suspensionService);
    }
}
```

- [ ] **Step 5.10: Add branch tests**

```java
@Test
void checkOne_whenOwnerMismatch_callsSuspensionService() {
    Auction a = TestFixtures.activeAuction();
    when(auctionRepo.findById(a.getId())).thenReturn(Optional.of(a));
    ParcelMetadata mismatched = new ParcelMetadata(..., UUID.randomUUID(), ...);
    when(worldApi.fetchParcel(a.getParcel().getSlParcelUuid())).thenReturn(mismatched);

    task.checkOne(a.getId());

    verify(suspensionService).suspendForOwnershipChange(a, mismatched);
    verify(auctionRepo, never()).save(a);
}

@Test
void checkOne_whenParcelNotFound_callsSuspensionForDeleted() {
    Auction a = TestFixtures.activeAuction();
    when(auctionRepo.findById(a.getId())).thenReturn(Optional.of(a));
    when(worldApi.fetchParcel(any())).thenThrow(new ParcelNotFoundInSlException("..."));

    task.checkOne(a.getId());

    verify(suspensionService).suspendForDeletedParcel(a);
}

@Test
void checkOne_whenTimeout_incrementsCounterAndUpdatesTimestamp() {
    Auction a = TestFixtures.activeAuction();
    a.setConsecutiveWorldApiFailures(2);
    when(auctionRepo.findById(a.getId())).thenReturn(Optional.of(a));
    when(worldApi.fetchParcel(any())).thenThrow(new ExternalApiTimeoutException("..."));

    task.checkOne(a.getId());

    assertThat(a.getConsecutiveWorldApiFailures()).isEqualTo(3);
    assertThat(a.getLastOwnershipCheckAt()).isNotNull();
    verify(auctionRepo).save(a);
    verifyNoInteractions(suspensionService);
}

@Test
void checkOne_nonActiveAuction_isNoop() {
    Auction a = TestFixtures.auctionWithStatus(AuctionStatus.DRAFT);
    when(auctionRepo.findById(a.getId())).thenReturn(Optional.of(a));

    task.checkOne(a.getId());

    verifyNoInteractions(worldApi, suspensionService);
    verify(auctionRepo, never()).save(any());
}
```

- [ ] **Step 5.11: Run the unit tests**

```bash
./mvnw test -Dtest=OwnershipCheckTaskTest
```

Expected: PASS.

- [ ] **Step 5.12: Write `SuspensionService` tests**

```java
@Test
void suspendForOwnershipChange_setsStatusSuspendedAndCreatesFlag() {
    Auction a = TestFixtures.activeAuction();
    ParcelMetadata evidence = new ParcelMetadata(..., UUID.randomUUID(), ...);
    suspensionService.suspendForOwnershipChange(a, evidence);

    assertThat(a.getStatus()).isEqualTo(AuctionStatus.SUSPENDED);
    verify(auctionRepo).save(a);
    ArgumentCaptor<FraudFlag> flagCap = ArgumentCaptor.forClass(FraudFlag.class);
    verify(fraudFlagRepo).save(flagCap.capture());
    assertThat(flagCap.getValue().getReason()).isEqualTo(FraudFlagReason.OWNERSHIP_CHANGED_TO_UNKNOWN);
    assertThat(flagCap.getValue().getEvidenceJson()).containsKey("expected_owner");
}
```

Add a second test for `suspendForDeletedParcel`.

- [ ] **Step 5.13: Integration test with WireMock**

Create `OwnershipMonitorIntegrationTest.java` under `backend/src/test/java/.../auction/monitoring/`:

```java
@SpringBootTest
@Testcontainers
@AutoConfigureWireMock(port = 0)
class OwnershipMonitorIntegrationTest {

    @Autowired OwnershipMonitorScheduler scheduler;
    @Autowired AuctionRepository auctionRepo;
    @Autowired FraudFlagRepository fraudFlagRepo;

    @Test
    void activeAuctionWithChangedOwner_getsSuspended() throws Exception {
        Auction a = givenActiveAuction(UUID.fromString(...));
        a.setLastOwnershipCheckAt(Instant.now().minus(Duration.ofHours(1)));
        auctionRepo.saveAndFlush(a);
        stubWorldApiReturnsOwner(UUID.randomUUID().toString());

        scheduler.dispatchDueChecks();
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Auction reloaded = auctionRepo.findById(a.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(AuctionStatus.SUSPENDED);
            assertThat(fraudFlagRepo.findByAuctionId(a.getId())).isNotEmpty();
        });
    }
}
```

- [ ] **Step 5.14: Integration test — jitter distribution**

```java
@Test
void onActivated_setsTimestampWithinJitterWindow() {
    OwnershipCheckTimestampInitializer init = new OwnershipCheckTimestampInitializer(props);
    props.setJitterMaxMinutes(5);

    Set<Long> minutes = new HashSet<>();
    for (int i = 0; i < 100; i++) {
        Auction a = new Auction();
        init.onActivated(a);
        long delta = Duration.between(a.getLastOwnershipCheckAt(), Instant.now()).toMinutes();
        minutes.add(delta);
        assertThat(delta).isBetween(0L, 5L);
    }
    assertThat(minutes).hasSizeGreaterThan(1);   // not all identical
}
```

- [ ] **Step 5.15: Run all monitoring tests**

```bash
./mvnw test -Dtest='*Monitor*Test,*Suspension*Test,*OwnershipCheck*Test,*FraudFlag*Test'
```

Expected: all PASS.

- [ ] **Step 5.16: Run full backend suite**

```bash
./mvnw test
```

Expected: BUILD SUCCESS, test count = Task 4 baseline + ~12.

- [ ] **Step 5.17: Commit**

```bash
git add backend/
git commit -m "feat(monitoring): scheduled ownership check with async per-listing dispatch

- OwnershipMonitorScheduler @Scheduled every 30s, queries due auctions
- OwnershipCheckTask @Async per listing: World API lookup, owner match,
  mismatch -> suspend, 404 -> suspend-for-deleted, timeout -> counter
- SuspensionService transitions to SUSPENDED and writes fraud_flag row
- OwnershipCheckTimestampInitializer seeds last_ownership_check_at
  with jitter on first ACTIVE transition to spread load
- DevOwnershipMonitorController (@Profile dev) triggers a run on demand
- integration test via WireMock covers end-to-end suspend path"
```

---

## Task 6 — Frontend UI primitives: `Stepper`, `DropZone`, icon re-exports

**Files:**
- Create: `frontend/src/components/ui/Stepper.tsx`
- Create: `frontend/src/components/ui/__tests__/Stepper.test.tsx`
- Create: `frontend/src/components/ui/DropZone.tsx`
- Create: `frontend/src/components/ui/__tests__/DropZone.test.tsx`
- Modify: `frontend/src/components/ui/icons.ts`

- [ ] **Step 6.1: Re-export needed icons**

Open `frontend/src/components/ui/icons.ts` and add re-exports if missing:

```ts
export {
    UploadCloud, ImagePlus, AlertTriangle, CheckCircle2, Loader2, XCircle,
    Gavel, Building2, MapPin, Tag, Trash2, Copy, ExternalLink, ArrowRight,
    Edit, Plus, MoreHorizontal
} from 'lucide-react';
```

If some are already exported, leave them; only add the missing ones.

- [ ] **Step 6.2: Write failing `Stepper` test**

```tsx
import { render, screen } from '@testing-library/react';
import { Stepper } from '../Stepper';

describe('Stepper', () => {
  it('renders all step labels', () => {
    render(<Stepper steps={['Draft', 'Paid', 'Verifying', 'Active']} currentIndex={1} />);
    expect(screen.getByText('Draft')).toBeInTheDocument();
    expect(screen.getByText('Paid')).toBeInTheDocument();
    expect(screen.getByText('Verifying')).toBeInTheDocument();
    expect(screen.getByText('Active')).toBeInTheDocument();
  });

  it('marks the current step with aria-current', () => {
    render(<Stepper steps={['A', 'B', 'C']} currentIndex={1} />);
    const current = screen.getByText('B').closest('[aria-current]');
    expect(current).toHaveAttribute('aria-current', 'step');
  });

  it('marks completed steps visually', () => {
    render(<Stepper steps={['A', 'B', 'C']} currentIndex={2} />);
    const first = screen.getByText('A').closest('[data-state]');
    expect(first).toHaveAttribute('data-state', 'complete');
  });
});
```

- [ ] **Step 6.3: Run the failing test**

```bash
cd frontend && npm test -- --run Stepper
```

Expected: FAIL (module not found).

- [ ] **Step 6.4: Implement `Stepper`**

```tsx
import { clsx } from 'clsx';
import { CheckCircle2 } from '@/components/ui/icons';

export interface StepperProps {
  steps: string[];
  currentIndex: number;
  className?: string;
}

export function Stepper({ steps, currentIndex, className }: StepperProps) {
  return (
    <ol className={clsx('flex items-center gap-2 overflow-x-auto', className)}>
      {steps.map((label, idx) => {
        const state = idx < currentIndex ? 'complete' : idx === currentIndex ? 'current' : 'upcoming';
        return (
          <li
            key={label}
            data-state={state}
            aria-current={state === 'current' ? 'step' : undefined}
            className={clsx(
              'flex items-center gap-2 text-sm font-medium',
              state === 'complete' && 'text-emerald-500',
              state === 'current' && 'text-indigo-500',
              state === 'upcoming' && 'text-slate-400'
            )}
          >
            <span
              className={clsx(
                'inline-flex h-6 w-6 items-center justify-center rounded-full border',
                state === 'complete' && 'border-emerald-500 bg-emerald-500 text-white',
                state === 'current' && 'border-indigo-500 text-indigo-500',
                state === 'upcoming' && 'border-slate-300 text-slate-400'
              )}
            >
              {state === 'complete' ? <CheckCircle2 size={14} /> : idx + 1}
            </span>
            <span>{label}</span>
            {idx < steps.length - 1 && <span className="mx-1 h-px w-6 bg-slate-300" aria-hidden />}
          </li>
        );
      })}
    </ol>
  );
}
```

- [ ] **Step 6.5: Run test, verify it passes**

```bash
npm test -- --run Stepper
```

Expected: PASS.

- [ ] **Step 6.6: Write failing `DropZone` test**

```tsx
import { render, screen, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { DropZone } from '../DropZone';

describe('DropZone', () => {
  it('calls onFiles when files dropped', () => {
    const onFiles = vi.fn();
    render(<DropZone onFiles={onFiles} accept="image/*" />);
    const drop = screen.getByTestId('drop-zone');
    const file = new File(['x'], 'a.png', { type: 'image/png' });
    fireEvent.drop(drop, { dataTransfer: { files: [file] } });
    expect(onFiles).toHaveBeenCalledWith([file]);
  });

  it('calls onFiles when files selected via input', async () => {
    const onFiles = vi.fn();
    render(<DropZone onFiles={onFiles} accept="image/*" />);
    const input = screen.getByTestId('drop-zone-input') as HTMLInputElement;
    const file = new File(['x'], 'a.png', { type: 'image/png' });
    await userEvent.upload(input, file);
    expect(onFiles).toHaveBeenCalled();
  });

  it('is disabled when prop set', () => {
    render(<DropZone onFiles={vi.fn()} accept="image/*" disabled />);
    expect(screen.getByTestId('drop-zone-input')).toBeDisabled();
  });
});
```

- [ ] **Step 6.7: Implement `DropZone`**

```tsx
'use client';

import { useRef, useState, DragEvent } from 'react';
import { clsx } from 'clsx';
import { UploadCloud } from '@/components/ui/icons';

export interface DropZoneProps {
  onFiles: (files: File[]) => void;
  accept?: string;
  multiple?: boolean;
  disabled?: boolean;
  label?: string;
  className?: string;
}

export function DropZone({ onFiles, accept, multiple = true, disabled, label, className }: DropZoneProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [over, setOver] = useState(false);

  function handleDrop(e: DragEvent<HTMLDivElement>) {
    e.preventDefault();
    setOver(false);
    if (disabled) return;
    const files = Array.from(e.dataTransfer.files ?? []);
    if (files.length > 0) onFiles(files);
  }

  return (
    <div
      data-testid="drop-zone"
      className={clsx(
        'flex cursor-pointer flex-col items-center justify-center gap-2 rounded-lg border-2 border-dashed p-6 text-center transition-colors',
        over && !disabled ? 'border-indigo-500 bg-indigo-50/50' : 'border-slate-300',
        disabled && 'cursor-not-allowed opacity-60',
        className
      )}
      onClick={() => !disabled && inputRef.current?.click()}
      onDragOver={(e) => { e.preventDefault(); if (!disabled) setOver(true); }}
      onDragLeave={() => setOver(false)}
      onDrop={handleDrop}
      role="button"
      aria-disabled={disabled}
    >
      <UploadCloud className="text-slate-400" size={28} />
      <span className="text-sm text-slate-600">
        {label ?? 'Drag files here, or click to select'}
      </span>
      <input
        data-testid="drop-zone-input"
        ref={inputRef}
        type="file"
        accept={accept}
        multiple={multiple}
        disabled={disabled}
        className="sr-only"
        onChange={(e) => {
          const files = Array.from(e.target.files ?? []);
          if (files.length > 0) onFiles(files);
          e.target.value = '';
        }}
      />
    </div>
  );
}
```

- [ ] **Step 6.8: Run DropZone tests**

```bash
npm test -- --run DropZone
```

Expected: PASS.

- [ ] **Step 6.9: Run full frontend suite and lint**

```bash
npm test -- --run && npm run lint
```

Expected: all PASS, no lint errors.

- [ ] **Step 6.10: Commit**

```bash
git add frontend/
git commit -m "feat(ui): add Stepper and DropZone primitives + icon re-exports

Stepper renders labelled steps with complete/current/upcoming states
and aria-current. DropZone is a reusable drag-and-drop surface that
forwards files to a callback. Both get component tests. New icons
re-exported from icons.ts for use by listing components."
```

---

## Task 7 — Listing components: lookup, settings, tags, photos, preview, wizard layout

**Files:**
- Create: `frontend/src/types/auction.ts`
- Create: `frontend/src/types/parcel.ts`
- Create: `frontend/src/types/parcelTag.ts`
- Create: `frontend/src/lib/api/parcels.ts`
- Create: `frontend/src/lib/api/auctions.ts`
- Create: `frontend/src/lib/api/parcelTags.ts`
- Create: `frontend/src/lib/api/auctionPhotos.ts`
- Create: `frontend/src/lib/api/config.ts`
- Create: `frontend/src/lib/listing/auctionStatus.ts`
- Create: `frontend/src/lib/listing/refundCalculation.ts`
- Create: `frontend/src/lib/listing/photoStaging.ts`
- Create: `frontend/src/components/listing/ParcelLookupField.tsx`
- Create: `frontend/src/components/listing/ParcelLookupCard.tsx`
- Create: `frontend/src/components/listing/AuctionSettingsForm.tsx`
- Create: `frontend/src/components/listing/TagSelector.tsx`
- Create: `frontend/src/components/listing/PhotoUploader.tsx`
- Create: `frontend/src/components/listing/ListingPreviewCard.tsx`
- Create: `frontend/src/components/listing/ListingWizardLayout.tsx`
- Create: `frontend/src/components/listing/ListingStatusBadge.tsx`
- Create: `frontend/src/hooks/useListingFeeConfig.ts`
- Create: component tests under `frontend/src/components/listing/__tests__/`

- [ ] **Step 7.1: Write the TypeScript types**

`frontend/src/types/auction.ts`:

```ts
export type AuctionStatus =
  | 'DRAFT' | 'DRAFT_PAID'
  | 'VERIFICATION_PENDING' | 'VERIFICATION_FAILED'
  | 'ACTIVE' | 'ENDED'
  | 'ESCROW_PENDING' | 'ESCROW_FUNDED' | 'TRANSFER_PENDING'
  | 'COMPLETED' | 'CANCELLED' | 'EXPIRED' | 'DISPUTED' | 'SUSPENDED';

export type VerificationMethod = 'UUID_ENTRY' | 'REZZABLE' | 'SALE_TO_BOT';
export type VerificationTier = 'SCRIPT' | 'BOT' | 'OWNERSHIP_TRANSFER' | null;

export interface PendingVerification {
  method: VerificationMethod;
  code: string | null;
  codeExpiresAt: string | null;
  botTaskId: number | null;
  instructions: string | null;
}

export interface AuctionPhotoDto { id: string; sortOrder: number; bytesUrl: string; }

import type { ParcelDto } from './parcel';
import type { ParcelTagDto } from './parcelTag';
import type { UserPublicProfile } from './user';

export interface SellerAuctionResponse {
  id: string;
  seller: UserPublicProfile;
  parcel: ParcelDto;
  status: AuctionStatus;
  verificationMethod: VerificationMethod | null;
  verificationTier: VerificationTier;
  pendingVerification: PendingVerification | null;
  verificationNotes: string | null;
  startingBid: string;
  reservePrice: string | null;
  buyNowPrice: string | null;
  currentBid: string | null;
  currentHighBid: string | null;
  bidderCount: number;
  bidCount: number;
  winnerId: string | null;
  durationHours: number;
  snipeProtect: boolean;
  snipeWindowMin: number | null;
  startsAt: string | null;
  endsAt: string | null;
  originalEndsAt: string | null;
  sellerDesc: string | null;
  tags: ParcelTagDto[];
  photos: AuctionPhotoDto[];
  listingFeePaid: boolean;
  listingFeeAmt: string | null;
  listingFeeTxn: string | null;
  listingFeePaidAt: string | null;
  commissionRate: string;
  commissionAmt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface AuctionCreateRequest {
  parcelId: string;
  startingBid: number;
  reservePrice?: number | null;
  buyNowPrice?: number | null;
  durationHours: 24 | 48 | 72 | 168 | 336;
  snipeProtect: boolean;
  snipeWindowMin?: 5 | 10 | 15 | 30 | 60 | null;
  sellerDesc?: string;
  tags: string[];
}

export type AuctionUpdateRequest = Partial<Omit<AuctionCreateRequest, 'parcelId'>>;
export interface AuctionVerifyRequest { method: VerificationMethod }
export interface AuctionCancelRequest { reason?: string }
```

`frontend/src/types/parcel.ts`:

```ts
export interface ParcelDto {
  id: string;
  slParcelUuid: string;
  ownerUuid: string;
  ownerType: 'individual' | 'group';
  regionName: string;
  gridX: number;
  gridY: number;
  continentName: string | null;
  areaSqm: number;
  description: string | null;
  snapshotUrl: string | null;
  slurl: string;
  maturityRating: 'GENERAL' | 'MODERATE' | 'ADULT';
  verified: boolean;
  verifiedAt: string | null;
  lastChecked: string | null;
  createdAt: string;
}
```

`frontend/src/types/parcelTag.ts`:

```ts
export interface ParcelTagDto {
  code: string;
  label: string;
  description: string | null;
  sortOrder: number;
}

export interface ParcelTagGroupDto {
  category: string;
  tags: ParcelTagDto[];
}
```

- [ ] **Step 7.2: Write the API client modules**

`frontend/src/lib/api/parcels.ts`:

```ts
import { apiFetch } from '@/lib/api/http';
import type { ParcelDto } from '@/types/parcel';

export async function lookupParcel(slParcelUuid: string): Promise<ParcelDto> {
  return apiFetch<ParcelDto>('/api/v1/parcels/lookup', {
    method: 'POST',
    body: JSON.stringify({ slParcelUuid })
  });
}
```

`frontend/src/lib/api/auctions.ts`:

```ts
import { apiFetch } from '@/lib/api/http';
import type {
  AuctionCreateRequest, AuctionUpdateRequest, AuctionVerifyRequest,
  AuctionCancelRequest, SellerAuctionResponse
} from '@/types/auction';

export async function createAuction(body: AuctionCreateRequest) {
  return apiFetch<SellerAuctionResponse>('/api/v1/auctions', {
    method: 'POST', body: JSON.stringify(body),
  });
}
export async function updateAuction(id: string, body: AuctionUpdateRequest) {
  return apiFetch<SellerAuctionResponse>(`/api/v1/auctions/${id}`, {
    method: 'PUT', body: JSON.stringify(body),
  });
}
export async function getAuction(id: string) {
  return apiFetch<SellerAuctionResponse>(`/api/v1/auctions/${id}`);
}
export async function listMyAuctions(params: { status?: string; page?: number; size?: number }) {
  const q = new URLSearchParams();
  if (params.status) q.set('status', params.status);
  if (params.page !== undefined) q.set('page', String(params.page));
  if (params.size !== undefined) q.set('size', String(params.size));
  return apiFetch<SellerAuctionResponse[]>(`/api/v1/users/me/auctions?${q}`);
}
export async function triggerVerify(id: string, body: AuctionVerifyRequest) {
  return apiFetch<SellerAuctionResponse>(`/api/v1/auctions/${id}/verify`, {
    method: 'PUT', body: JSON.stringify(body),
  });
}
export async function cancelAuction(id: string, body: AuctionCancelRequest = {}) {
  return apiFetch<SellerAuctionResponse>(`/api/v1/auctions/${id}/cancel`, {
    method: 'PUT', body: JSON.stringify(body),
  });
}
```

`frontend/src/lib/api/parcelTags.ts`:

```ts
import { apiFetch } from '@/lib/api/http';
import type { ParcelTagGroupDto } from '@/types/parcelTag';

export async function listParcelTagGroups() {
  return apiFetch<ParcelTagGroupDto[]>('/api/v1/parcel-tags');
}
```

`frontend/src/lib/api/auctionPhotos.ts`:

```ts
import { apiFetch, apiFetchMultipart } from '@/lib/api/http';
import type { AuctionPhotoDto } from '@/types/auction';

export async function uploadPhoto(auctionId: string, file: File): Promise<AuctionPhotoDto> {
  const fd = new FormData();
  fd.append('file', file);
  return apiFetchMultipart<AuctionPhotoDto>(
    `/api/v1/auctions/${auctionId}/photos`, fd);
}
export async function deletePhoto(auctionId: string, photoId: string): Promise<void> {
  await apiFetch<void>(`/api/v1/auctions/${auctionId}/photos/${photoId}`, {
    method: 'DELETE',
  });
}
```

`frontend/src/lib/api/config.ts`:

```ts
import { apiFetch } from '@/lib/api/http';

export interface ListingFeeConfig { amountLindens: number }
export function getListingFeeConfig() {
  return apiFetch<ListingFeeConfig>('/api/v1/config/listing-fee');
}
```

If `apiFetchMultipart` doesn't exist in `http.ts`, add it — it omits the `Content-Type` header so the browser sets the multipart boundary:

```ts
export async function apiFetchMultipart<T>(path: string, body: FormData): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, {
    method: 'POST',
    credentials: 'include',
    body,
  });
  if (!res.ok) throw await toApiError(res);
  return (await res.json()) as T;
}
```

- [ ] **Step 7.3: Write pure helpers**

`frontend/src/lib/listing/auctionStatus.ts`:

```ts
import type { AuctionStatus } from '@/types/auction';

export const TERMINAL_STATUSES: AuctionStatus[] =
  ['ACTIVE', 'CANCELLED', 'SUSPENDED', 'ENDED', 'EXPIRED',
   'COMPLETED', 'DISPUTED', 'ESCROW_PENDING', 'ESCROW_FUNDED', 'TRANSFER_PENDING'];
export const PRE_ACTIVE: AuctionStatus[] =
  ['DRAFT', 'DRAFT_PAID', 'VERIFICATION_PENDING', 'VERIFICATION_FAILED'];
export const EDITABLE: AuctionStatus[] = ['DRAFT', 'DRAFT_PAID'];

export function isPreActive(s: AuctionStatus) { return PRE_ACTIVE.includes(s); }
export function isEditable(s: AuctionStatus) { return EDITABLE.includes(s); }
export function isTerminal(s: AuctionStatus) { return TERMINAL_STATUSES.includes(s); }

export const FILTER_GROUPS: Record<string, AuctionStatus[]> = {
  Active: ['ACTIVE'],
  Drafts: ['DRAFT', 'DRAFT_PAID', 'VERIFICATION_PENDING', 'VERIFICATION_FAILED'],
  Ended: ['ENDED', 'ESCROW_PENDING', 'ESCROW_FUNDED', 'TRANSFER_PENDING', 'COMPLETED', 'EXPIRED'],
  Cancelled: ['CANCELLED'],
  Suspended: ['SUSPENDED'],
};
```

`frontend/src/lib/listing/refundCalculation.ts`:

```ts
import type { AuctionStatus } from '@/types/auction';

export interface RefundInfo {
  kind: 'NONE' | 'FULL';
  amountLindens: string | null;
  copy: string;
}

export function computeRefund(status: AuctionStatus, listingFeeAmt: string | null): RefundInfo {
  switch (status) {
    case 'DRAFT':
      return { kind: 'NONE', amountLindens: null, copy: 'No refund — no fee was paid yet.' };
    case 'DRAFT_PAID':
    case 'VERIFICATION_PENDING':
    case 'VERIFICATION_FAILED':
      return { kind: 'FULL', amountLindens: listingFeeAmt ?? '0',
        copy: `Refund: L$${listingFeeAmt ?? '0'} (full refund, processed within 24 hours).` };
    case 'ACTIVE':
      return { kind: 'NONE', amountLindens: null,
        copy: 'No refund — cancelling an active listing does not refund the fee.' };
    default:
      return { kind: 'NONE', amountLindens: null, copy: 'This listing cannot be cancelled.' };
  }
}
```

`frontend/src/lib/listing/photoStaging.ts`:

```ts
export interface StagedPhoto {
  id: string;
  file: File;
  objectUrl: string;
  uploadedPhotoId: string | null;
  error: string | null;
}

export function stagePhoto(file: File): StagedPhoto {
  return {
    id: crypto.randomUUID(),
    file,
    objectUrl: URL.createObjectURL(file),
    uploadedPhotoId: null,
    error: null,
  };
}

export function revokeStagedPhoto(p: StagedPhoto) {
  URL.revokeObjectURL(p.objectUrl);
}

export function validateFile(file: File): string | null {
  if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type)) {
    return 'Only JPEG, PNG, or WebP images are accepted.';
  }
  if (file.size > 2 * 1024 * 1024) return 'Image must be 2 MB or smaller.';
  return null;
}
```

Add a test for `refundCalculation.ts`:

```ts
import { computeRefund } from '../refundCalculation';

describe('computeRefund', () => {
  it('returns NONE for DRAFT', () =>
    expect(computeRefund('DRAFT', null)).toMatchObject({ kind: 'NONE' }));
  it('returns FULL for DRAFT_PAID with listingFeeAmt', () =>
    expect(computeRefund('DRAFT_PAID', '100')).toMatchObject({ kind: 'FULL', amountLindens: '100' }));
  it('returns FULL for VERIFICATION_FAILED', () =>
    expect(computeRefund('VERIFICATION_FAILED', '100')).toMatchObject({ kind: 'FULL' }));
  it('returns NONE for ACTIVE', () =>
    expect(computeRefund('ACTIVE', '100')).toMatchObject({ kind: 'NONE' }));
});
```

Run `npm test -- --run refundCalculation` — expect PASS.

- [ ] **Step 7.4: Write `useListingFeeConfig` hook**

```ts
import { useQuery } from '@tanstack/react-query';
import { getListingFeeConfig } from '@/lib/api/config';

export function useListingFeeConfig() {
  return useQuery({
    queryKey: ['config', 'listing-fee'],
    queryFn: getListingFeeConfig,
    staleTime: 60 * 60 * 1000,
  });
}
```

- [ ] **Step 7.5: Build `ParcelLookupField` with failing test**

Test at `frontend/src/components/listing/__tests__/ParcelLookupField.test.tsx`:

```tsx
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/test/msw/server';
import { http, HttpResponse } from 'msw';
import { ParcelLookupField } from '../ParcelLookupField';

function wrap(ui: React.ReactNode) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={qc}>{ui}</QueryClientProvider>;
}

describe('ParcelLookupField', () => {
  it('calls lookupParcel and surfaces the result', async () => {
    server.use(http.post('*/api/v1/parcels/lookup', async () => HttpResponse.json({
      id: 'p1', slParcelUuid: '00000000-0000-0000-0000-000000000001',
      ownerUuid: 'o1', ownerType: 'individual',
      regionName: 'Heterocera', gridX: 1000, gridY: 1000, continentName: 'Heterocera',
      areaSqm: 1024, description: null, snapshotUrl: null,
      slurl: 'secondlife://Heterocera/128/128/25', maturityRating: 'GENERAL',
      verified: false, verifiedAt: null, lastChecked: null, createdAt: '2026-04-17T00:00:00Z',
    })));
    const onResolved = vi.fn();
    render(wrap(<ParcelLookupField onResolved={onResolved} />));
    const field = screen.getByLabelText(/Parcel UUID/i);
    await userEvent.type(field, '00000000-0000-0000-0000-000000000001');
    await userEvent.click(screen.getByRole('button', { name: /look up/i }));
    await screen.findByText(/Heterocera/);
    expect(onResolved).toHaveBeenCalled();
  });

  it('shows error for 404', async () => {
    server.use(http.post('*/api/v1/parcels/lookup', () =>
      HttpResponse.json({ title: 'Not found' }, { status: 404 })));
    render(wrap(<ParcelLookupField onResolved={vi.fn()} />));
    await userEvent.type(screen.getByLabelText(/Parcel UUID/i), '00000000-0000-0000-0000-000000000001');
    await userEvent.click(screen.getByRole('button', { name: /look up/i }));
    await screen.findByText(/couldn't find this parcel/i);
  });
});
```

Note: if `frontend/src/test/msw/server.ts` doesn't exist, add it — a lightweight MSW server bootstrap matching Epic 02 sub-spec 2b patterns.

- [ ] **Step 7.6: Implement `ParcelLookupField`**

```tsx
'use client';

import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { lookupParcel } from '@/lib/api/parcels';
import type { ParcelDto } from '@/types/parcel';
import { Input } from '@/components/ui/Input';
import { Button } from '@/components/ui/Button';
import { Loader2 } from '@/components/ui/icons';
import { ParcelLookupCard } from './ParcelLookupCard';

const UUID_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export interface ParcelLookupFieldProps {
  initialParcel?: ParcelDto | null;
  locked?: boolean;
  onResolved: (parcel: ParcelDto) => void;
}

export function ParcelLookupField({ initialParcel = null, locked, onResolved }: ParcelLookupFieldProps) {
  const [uuid, setUuid] = useState(initialParcel?.slParcelUuid ?? '');
  const [parcel, setParcel] = useState<ParcelDto | null>(initialParcel);
  const [clientError, setClientError] = useState<string | null>(null);
  const [serverError, setServerError] = useState<string | null>(null);

  const mutation = useMutation({
    mutationFn: (id: string) => lookupParcel(id),
    onSuccess: (data) => { setParcel(data); setServerError(null); onResolved(data); },
    onError: (e: Error & { status?: number }) => {
      const map: Record<number, string> = {
        400: "This doesn't look like a valid Second Life parcel UUID.",
        404: "We couldn't find this parcel in Second Life. Check the UUID or try again later.",
        422: "This parcel isn't on a Mainland continent. Phase 1 supports Mainland parcels only.",
        504: "Second Life's parcel service is slow or down right now. Try again in a moment.",
      };
      setServerError(map[e.status ?? 0] ?? (e.message ?? 'Lookup failed.'));
    },
  });

  function submit() {
    if (!UUID_REGEX.test(uuid.trim())) {
      setClientError('Enter a valid UUID like 00000000-0000-0000-0000-000000000000.');
      return;
    }
    setClientError(null);
    mutation.mutate(uuid.trim());
  }

  return (
    <div className="space-y-3">
      <label htmlFor="parcel-uuid" className="block text-sm font-medium text-slate-700">
        Parcel UUID
      </label>
      <div className="flex gap-2">
        <Input
          id="parcel-uuid"
          value={uuid}
          disabled={locked || mutation.isPending}
          onChange={(e) => setUuid(e.target.value)}
          placeholder="00000000-0000-0000-0000-000000000000"
        />
        {!locked && (
          <Button type="button" onClick={submit} disabled={mutation.isPending}>
            {mutation.isPending ? <Loader2 className="animate-spin" size={16} /> : 'Look up'}
          </Button>
        )}
      </div>
      {clientError && <p className="text-sm text-rose-600">{clientError}</p>}
      {serverError && <p className="text-sm text-rose-600">{serverError}</p>}
      {parcel && <ParcelLookupCard parcel={parcel} />}
    </div>
  );
}
```

- [ ] **Step 7.7: Implement `ParcelLookupCard`**

```tsx
import type { ParcelDto } from '@/types/parcel';
import { MapPin, ExternalLink } from '@/components/ui/icons';

export function ParcelLookupCard({ parcel }: { parcel: ParcelDto }) {
  return (
    <div className="flex gap-4 rounded-lg border border-slate-200 bg-white p-4">
      {parcel.snapshotUrl && (
        <img src={parcel.snapshotUrl} alt="" className="h-20 w-20 rounded object-cover" />
      )}
      <div className="flex-1 space-y-1">
        <p className="font-semibold">{parcel.description || '(unnamed parcel)'}</p>
        <p className="text-sm text-slate-600 flex items-center gap-1">
          <MapPin size={14} /> {parcel.regionName} · {parcel.areaSqm} m²
          {parcel.continentName && ` · ${parcel.continentName}`}
        </p>
        <p className="text-xs text-slate-500">
          Owner UUID: {parcel.ownerUuid} ({parcel.ownerType})
        </p>
        <a className="text-sm text-indigo-600 inline-flex items-center gap-1" href={parcel.slurl}>
          Visit in Second Life <ExternalLink size={12} />
        </a>
      </div>
    </div>
  );
}
```

- [ ] **Step 7.8: Implement `AuctionSettingsForm` with failing test**

Test basics:

```tsx
describe('AuctionSettingsForm', () => {
  it('rejects reserve below starting bid', async () => {
    const onChange = vi.fn();
    render(<AuctionSettingsForm value={{ startingBid: 500, reservePrice: null, buyNowPrice: null,
      durationHours: 72, snipeProtect: true, snipeWindowMin: 10 }} onChange={onChange} />);
    await userEvent.clear(screen.getByLabelText(/Reserve price/i));
    await userEvent.type(screen.getByLabelText(/Reserve price/i), '100');
    expect(await screen.findByText(/reserve must be at least/i)).toBeInTheDocument();
  });
});
```

Implementation:

```tsx
'use client';

import { Input } from '@/components/ui/Input';
import { Select } from '@/components/ui/Select';
import { Switch } from '@/components/ui/Switch';

export interface AuctionSettingsValue {
  startingBid: number;
  reservePrice: number | null;
  buyNowPrice: number | null;
  durationHours: 24 | 48 | 72 | 168 | 336;
  snipeProtect: boolean;
  snipeWindowMin: 5 | 10 | 15 | 30 | 60 | null;
}

export interface AuctionSettingsFormProps {
  value: AuctionSettingsValue;
  onChange: (value: AuctionSettingsValue) => void;
  errors?: Record<string, string>;
}

export function AuctionSettingsForm({ value, onChange, errors = {} }: AuctionSettingsFormProps) {
  const update = <K extends keyof AuctionSettingsValue>(k: K, v: AuctionSettingsValue[K]) =>
    onChange({ ...value, [k]: v });

  const reserveError = value.reservePrice != null && value.reservePrice < value.startingBid
    ? `Reserve must be at least the starting bid (L$${value.startingBid}).` : errors.reservePrice;
  const buyNowMin = Math.max(value.startingBid, value.reservePrice ?? 0);
  const buyNowError = value.buyNowPrice != null && value.buyNowPrice < buyNowMin
    ? `Buy-it-now must be at least L$${buyNowMin}.` : errors.buyNowPrice;

  return (
    <div className="space-y-4">
      <FieldRow label="Starting bid (L$)" error={errors.startingBid}>
        <Input type="number" min={1} value={value.startingBid}
          onChange={(e) => update('startingBid', Number(e.target.value))} />
      </FieldRow>
      <FieldRow label="Reserve price (L$)" hint="Optional. Minimum price for the sale to close."
        error={reserveError}>
        <Input type="number" min={0} value={value.reservePrice ?? ''}
          onChange={(e) => update('reservePrice', e.target.value ? Number(e.target.value) : null)} />
      </FieldRow>
      <FieldRow label="Buy-it-now price (L$)" hint="Optional. Any bidder can end the auction at this price."
        error={buyNowError}>
        <Input type="number" min={0} value={value.buyNowPrice ?? ''}
          onChange={(e) => update('buyNowPrice', e.target.value ? Number(e.target.value) : null)} />
      </FieldRow>
      <FieldRow label="Duration" error={errors.durationHours}>
        <Select value={String(value.durationHours)}
          onChange={(e) => update('durationHours', Number(e.target.value) as AuctionSettingsValue['durationHours'])}>
          <option value="24">24 hours</option><option value="48">48 hours</option>
          <option value="72">72 hours</option><option value="168">7 days</option>
          <option value="336">14 days</option>
        </Select>
      </FieldRow>
      <FieldRow label="Snipe protection">
        <Switch checked={value.snipeProtect} onCheckedChange={(b) => update('snipeProtect', b)} />
      </FieldRow>
      {value.snipeProtect && (
        <FieldRow label="Extension window" error={errors.snipeWindowMin}>
          <Select value={String(value.snipeWindowMin ?? 10)}
            onChange={(e) => update('snipeWindowMin', Number(e.target.value) as AuctionSettingsValue['snipeWindowMin'])}>
            <option value="5">5 minutes</option><option value="10">10 minutes</option>
            <option value="15">15 minutes</option><option value="30">30 minutes</option>
            <option value="60">60 minutes</option>
          </Select>
        </FieldRow>
      )}
    </div>
  );
}

function FieldRow({ label, hint, error, children }:
  { label: string; hint?: string; error?: string; children: React.ReactNode }) {
  return (
    <div>
      <label className="block text-sm font-medium text-slate-700">{label}</label>
      {hint && <p className="text-xs text-slate-500">{hint}</p>}
      <div className="mt-1">{children}</div>
      {error && <p className="mt-1 text-sm text-rose-600">{error}</p>}
    </div>
  );
}
```

- [ ] **Step 7.9: Implement `TagSelector`**

Test:

```tsx
describe('TagSelector', () => {
  it('renders categories collapsible', async () => {
    server.use(http.get('*/api/v1/parcel-tags', () => HttpResponse.json([
      { category: 'Terrain', tags: [{ code: 'beach', label: 'Beach', description: null, sortOrder: 1 }] },
      { category: 'Location', tags: [{ code: 'city', label: 'City', description: null, sortOrder: 1 }] },
    ])));
    render(wrap(<TagSelector value={[]} onChange={vi.fn()} />));
    await screen.findByText('Terrain'); await screen.findByText('Location');
  });
  it('toggles selection', async () => {
    server.use(http.get('*/api/v1/parcel-tags', () => HttpResponse.json([
      { category: 'Terrain', tags: [{ code: 'beach', label: 'Beach', description: null, sortOrder: 1 }] },
    ])));
    const onChange = vi.fn();
    render(wrap(<TagSelector value={[]} onChange={onChange} />));
    await userEvent.click(await screen.findByRole('button', { name: /Beach/i }));
    expect(onChange).toHaveBeenCalledWith(['beach']);
  });
});
```

Implementation:

```tsx
'use client';

import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { clsx } from 'clsx';
import { listParcelTagGroups } from '@/lib/api/parcelTags';

export interface TagSelectorProps {
  value: string[];
  onChange: (next: string[]) => void;
  maxSelections?: number;
}

export function TagSelector({ value, onChange, maxSelections = 10 }: TagSelectorProps) {
  const { data, isLoading } = useQuery({
    queryKey: ['parcel-tags'],
    queryFn: listParcelTagGroups,
    staleTime: 60 * 60 * 1000,
  });
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});

  if (isLoading) return <p className="text-sm text-slate-500">Loading tags…</p>;
  if (!data) return null;

  function toggle(code: string) {
    if (value.includes(code)) onChange(value.filter((c) => c !== code));
    else if (value.length < maxSelections) onChange([...value, code]);
  }

  return (
    <div className="space-y-3">
      {data.map((group) => {
        const open = !collapsed[group.category];
        return (
          <div key={group.category} className="rounded-lg border border-slate-200">
            <button type="button" className="flex w-full items-center justify-between px-3 py-2 text-sm font-medium"
              onClick={() => setCollapsed((s) => ({ ...s, [group.category]: open }))}>
              <span>{group.category}</span>
              <span className="text-xs text-slate-500">{open ? 'Hide' : 'Show'}</span>
            </button>
            {open && (
              <div className="flex flex-wrap gap-2 border-t border-slate-200 p-3">
                {group.tags.map((tag) => {
                  const selected = value.includes(tag.code);
                  return (
                    <button key={tag.code} type="button" onClick={() => toggle(tag.code)}
                      className={clsx('rounded-full border px-3 py-1 text-sm transition-colors',
                        selected ? 'border-indigo-500 bg-indigo-500 text-white'
                                 : 'border-slate-300 bg-white text-slate-700')}>
                      {tag.label}
                    </button>
                  );
                })}
              </div>
            )}
          </div>
        );
      })}
      <p className="text-xs text-slate-500">
        {value.length}/{maxSelections} selected
      </p>
    </div>
  );
}
```

- [ ] **Step 7.10: Implement `PhotoUploader`**

```tsx
'use client';

import { useEffect } from 'react';
import { DropZone } from '@/components/ui/DropZone';
import { Trash2, AlertTriangle } from '@/components/ui/icons';
import { stagePhoto, validateFile, revokeStagedPhoto, type StagedPhoto } from '@/lib/listing/photoStaging';

export interface PhotoUploaderProps {
  staged: StagedPhoto[];
  onStagedChange: (next: StagedPhoto[]) => void;
  disabled?: boolean;
  maxPhotos?: number;
}

export function PhotoUploader({ staged, onStagedChange, disabled, maxPhotos = 10 }: PhotoUploaderProps) {
  useEffect(() => () => { staged.forEach(revokeStagedPhoto); }, []);

  function addFiles(files: File[]) {
    const remaining = maxPhotos - staged.length;
    const take = files.slice(0, remaining);
    const newOnes: StagedPhoto[] = take.map((f) => {
      const err = validateFile(f);
      const p = stagePhoto(f);
      p.error = err;
      return p;
    });
    onStagedChange([...staged, ...newOnes]);
  }

  function remove(id: string) {
    const p = staged.find((s) => s.id === id);
    if (p) revokeStagedPhoto(p);
    onStagedChange(staged.filter((s) => s.id !== id));
  }

  return (
    <div className="space-y-3">
      <DropZone onFiles={addFiles} accept="image/jpeg,image/png,image/webp" multiple
        disabled={disabled || staged.length >= maxPhotos}
        label={staged.length >= maxPhotos ? `Maximum ${maxPhotos} photos` : 'Drag and drop photos, or click to pick'} />
      {staged.length > 0 && (
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
          {staged.map((p) => (
            <div key={p.id} className="relative rounded-lg border border-slate-200 overflow-hidden">
              <img src={p.objectUrl} alt="" className="h-24 w-full object-cover" />
              {p.error && (
                <div className="absolute inset-0 flex flex-col items-center justify-center bg-rose-500/70 p-2 text-center text-xs text-white">
                  <AlertTriangle size={16} /><span>{p.error}</span>
                </div>
              )}
              <button type="button" onClick={() => remove(p.id)}
                className="absolute top-1 right-1 rounded-full bg-white/80 p-1 text-rose-600 hover:bg-white"
                aria-label="Remove photo">
                <Trash2 size={14} />
              </button>
              {p.uploadedPhotoId ? null
                : <span className="absolute bottom-1 left-1 rounded bg-slate-800/70 px-1.5 text-[10px] text-white">staged</span>}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 7.11: Implement `ListingPreviewCard`**

```tsx
import type { SellerAuctionResponse } from '@/types/auction';
import { MapPin, Gavel, Tag as TagIcon } from '@/components/ui/icons';

export function ListingPreviewCard({ auction, isPreview }:
  { auction: Pick<SellerAuctionResponse, 'parcel' | 'startingBid' | 'reservePrice'
      | 'buyNowPrice' | 'durationHours' | 'tags' | 'photos' | 'sellerDesc'>;
    isPreview?: boolean }) {
  return (
    <article className="rounded-xl border border-slate-200 bg-white p-4 space-y-3">
      {isPreview && (
        <div className="rounded-md bg-indigo-50 px-3 py-2 text-sm text-indigo-700">
          Preview — this is how your listing will appear to buyers.
        </div>
      )}
      {auction.photos[0] && (
        <img src={auction.photos[0].bytesUrl} alt="" className="h-48 w-full rounded-lg object-cover" />
      )}
      <h2 className="text-xl font-semibold">{auction.parcel.description || '(unnamed parcel)'}</h2>
      <p className="text-sm text-slate-600 flex items-center gap-1">
        <MapPin size={14} /> {auction.parcel.regionName} · {auction.parcel.areaSqm} m²
      </p>
      <div className="grid grid-cols-3 gap-4 text-sm">
        <Stat label="Starting bid" value={`L$${auction.startingBid}`} />
        {auction.reservePrice && <Stat label="Reserve" value="set" />}
        {auction.buyNowPrice && <Stat label="Buy it now" value={`L$${auction.buyNowPrice}`} />}
      </div>
      {auction.sellerDesc && <p className="whitespace-pre-wrap text-sm">{auction.sellerDesc}</p>}
      {auction.tags.length > 0 && (
        <div className="flex flex-wrap gap-1">
          {auction.tags.map((t) => (
            <span key={t.code} className="rounded-full bg-slate-100 px-2 py-0.5 text-xs text-slate-700 inline-flex items-center gap-1">
              <TagIcon size={10} />{t.label}
            </span>
          ))}
        </div>
      )}
    </article>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <p className="text-xs uppercase text-slate-500">{label}</p>
      <p className="font-medium">{value}</p>
    </div>
  );
}
```

- [ ] **Step 7.12: Implement `ListingWizardLayout`**

```tsx
'use client';

import { Stepper } from '@/components/ui/Stepper';
import { clsx } from 'clsx';

export interface ListingWizardLayoutProps {
  steps: string[];
  currentIndex: number;
  title: string;
  footer: React.ReactNode;
  children: React.ReactNode;
  className?: string;
}

export function ListingWizardLayout({ steps, currentIndex, title, footer, children, className }: ListingWizardLayoutProps) {
  return (
    <div className={clsx('mx-auto max-w-3xl space-y-6 p-6', className)}>
      <Stepper steps={steps} currentIndex={currentIndex} />
      <h1 className="text-2xl font-semibold">{title}</h1>
      <section>{children}</section>
      <footer className="flex justify-end gap-3 border-t border-slate-200 pt-4">{footer}</footer>
    </div>
  );
}
```

- [ ] **Step 7.13: Implement `ListingStatusBadge`**

```tsx
import { clsx } from 'clsx';
import type { AuctionStatus } from '@/types/auction';

const MAP: Record<AuctionStatus, { label: string; cls: string }> = {
  DRAFT:                { label: 'Draft',          cls: 'bg-slate-500 text-white' },
  DRAFT_PAID:           { label: 'Paid',           cls: 'bg-amber-500 text-black' },
  VERIFICATION_PENDING: { label: 'Verifying',      cls: 'bg-blue-500 text-white' },
  VERIFICATION_FAILED:  { label: 'Verify failed',  cls: 'bg-amber-600 text-white' },
  ACTIVE:               { label: 'Active',         cls: 'bg-emerald-500 text-white' },
  ENDED:                { label: 'Ended',          cls: 'bg-purple-500 text-white' },
  ESCROW_PENDING:       { label: 'Escrow',         cls: 'bg-purple-600 text-white' },
  ESCROW_FUNDED:        { label: 'Escrow funded',  cls: 'bg-purple-600 text-white' },
  TRANSFER_PENDING:     { label: 'Transferring',   cls: 'bg-purple-600 text-white' },
  COMPLETED:            { label: 'Completed',      cls: 'bg-slate-500 text-white' },
  CANCELLED:            { label: 'Cancelled',      cls: 'bg-rose-500 text-white' },
  EXPIRED:              { label: 'Expired',        cls: 'bg-stone-500 text-white' },
  DISPUTED:             { label: 'Disputed',       cls: 'bg-orange-600 text-white' },
  SUSPENDED:            { label: 'Suspended',      cls: 'bg-red-600 text-white' },
};

export function ListingStatusBadge({ status }: { status: AuctionStatus }) {
  const entry = MAP[status];
  return (
    <span className={clsx('inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium', entry.cls)}>
      {entry.label}
    </span>
  );
}
```

Add test asserting every status maps:

```tsx
import type { AuctionStatus } from '@/types/auction';
const ALL: AuctionStatus[] = ['DRAFT','DRAFT_PAID','VERIFICATION_PENDING','VERIFICATION_FAILED',
  'ACTIVE','ENDED','ESCROW_PENDING','ESCROW_FUNDED','TRANSFER_PENDING','COMPLETED','CANCELLED',
  'EXPIRED','DISPUTED','SUSPENDED'];
describe('ListingStatusBadge', () => {
  it.each(ALL)('renders a label for %s', (s) => {
    render(<ListingStatusBadge status={s} />);
    expect(screen.getByText(/./)).toBeInTheDocument();
  });
});
```

- [ ] **Step 7.14: Run component tests + lint**

```bash
npm test -- --run listing && npm run lint
```

Expected: all PASS.

- [ ] **Step 7.15: Commit**

```bash
git add frontend/
git commit -m "feat(listing): reusable listing components (lookup, settings, tags, photos, preview, layout, badge)

Introduces the reusable listing component surface: ParcelLookupField,
ParcelLookupCard, AuctionSettingsForm with cross-field validation,
TagSelector (category-grouped, multi-select), PhotoUploader with
client-side staging, ListingPreviewCard (shared with Epic 04's public
listing page later), ListingWizardLayout, ListingStatusBadge covering
all 14 auction statuses. API clients for parcels, auctions,
parcel-tags, photos, and listing-fee config. Pure helpers for refund
calculation and photo staging, both with unit tests."
```

---

## Task 8 — Create + Edit pages + `useListingDraft` hook

**Files:**
- Create: `frontend/src/hooks/useListingDraft.ts`
- Create: `frontend/src/hooks/__tests__/useListingDraft.test.tsx`
- Create: `frontend/src/app/listings/create/page.tsx`
- Create: `frontend/src/app/listings/[id]/edit/page.tsx`
- Create: `frontend/src/components/listing/ListingWizardForm.tsx` (shared form body used by both routes)
- Create: `frontend/src/components/listing/__tests__/ListingWizardForm.test.tsx`

- [ ] **Step 8.1: Write the `useListingDraft` hook**

```ts
'use client';

import { useEffect, useMemo, useRef, useState, useCallback } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { createAuction, updateAuction, getAuction } from '@/lib/api/auctions';
import { uploadPhoto, deletePhoto } from '@/lib/api/auctionPhotos';
import type { AuctionCreateRequest, AuctionUpdateRequest, SellerAuctionResponse } from '@/types/auction';
import type { ParcelDto } from '@/types/parcel';
import { stagePhoto, revokeStagedPhoto, type StagedPhoto } from '@/lib/listing/photoStaging';

export interface DraftState {
  auctionId: string | null;
  parcel: ParcelDto | null;
  startingBid: number;
  reservePrice: number | null;
  buyNowPrice: number | null;
  durationHours: 24 | 48 | 72 | 168 | 336;
  snipeProtect: boolean;
  snipeWindowMin: 5 | 10 | 15 | 30 | 60 | null;
  sellerDesc: string;
  tags: string[];
  stagedPhotos: StagedPhoto[];
  uploadedPhotoIds: string[];     // already on server
  removedPhotoIds: string[];      // queued DELETE on next save
  dirty: boolean;
}

const EMPTY: DraftState = {
  auctionId: null, parcel: null,
  startingBid: 100, reservePrice: null, buyNowPrice: null,
  durationHours: 72, snipeProtect: true, snipeWindowMin: 10,
  sellerDesc: '', tags: [],
  stagedPhotos: [], uploadedPhotoIds: [], removedPhotoIds: [],
  dirty: false,
};

function storageKey(id: string | null) { return `slpa:draft:${id ?? 'new'}`; }

export function useListingDraft(options: { id?: string }) {
  const [state, setState] = useState<DraftState>(EMPTY);
  const qc = useQueryClient();
  const loadedFromSession = useRef(false);

  // Hydrate from session or fetch on mount
  const fetchQ = useQuery({
    queryKey: ['auction', options.id],
    queryFn: () => getAuction(options.id!),
    enabled: !!options.id,
  });

  useEffect(() => {
    if (loadedFromSession.current) return;
    const raw = typeof window !== 'undefined' ? sessionStorage.getItem(storageKey(options.id ?? null)) : null;
    if (raw) {
      try { setState({ ...JSON.parse(raw), stagedPhotos: [] }); loadedFromSession.current = true; return; } catch {}
    }
    if (fetchQ.data) hydrateFromServer(fetchQ.data);
  }, [fetchQ.data, options.id]);

  // Persist to sessionStorage on any state change
  useEffect(() => {
    const { stagedPhotos, ...rest } = state;
    sessionStorage.setItem(storageKey(state.auctionId ?? options.id ?? null), JSON.stringify(rest));
  }, [state, options.id]);

  function hydrateFromServer(a: SellerAuctionResponse) {
    setState({
      auctionId: a.id, parcel: a.parcel,
      startingBid: Number(a.startingBid),
      reservePrice: a.reservePrice ? Number(a.reservePrice) : null,
      buyNowPrice: a.buyNowPrice ? Number(a.buyNowPrice) : null,
      durationHours: a.durationHours as DraftState['durationHours'],
      snipeProtect: a.snipeProtect,
      snipeWindowMin: (a.snipeWindowMin ?? null) as DraftState['snipeWindowMin'],
      sellerDesc: a.sellerDesc ?? '',
      tags: a.tags.map((t) => t.code),
      stagedPhotos: [],
      uploadedPhotoIds: a.photos.map((p) => p.id),
      removedPhotoIds: [],
      dirty: false,
    });
  }

  const update = useCallback(<K extends keyof DraftState>(k: K, v: DraftState[K]) => {
    setState((s) => ({ ...s, [k]: v, dirty: true }));
  }, []);

  const setParcel = useCallback((p: ParcelDto) => update('parcel', p), [update]);

  const save = useCallback(async (): Promise<SellerAuctionResponse> => {
    if (!state.parcel) throw new Error('Cannot save without a parcel');
    const body: AuctionCreateRequest | AuctionUpdateRequest = {
      parcelId: state.parcel.id,
      startingBid: state.startingBid,
      reservePrice: state.reservePrice,
      buyNowPrice: state.buyNowPrice,
      durationHours: state.durationHours,
      snipeProtect: state.snipeProtect,
      snipeWindowMin: state.snipeWindowMin,
      sellerDesc: state.sellerDesc,
      tags: state.tags,
    };
    let auction: SellerAuctionResponse;
    if (state.auctionId) {
      const { parcelId, ...update } = body as AuctionCreateRequest;
      auction = await updateAuction(state.auctionId, update);
    } else {
      auction = await createAuction(body as AuctionCreateRequest);
    }

    // Queue DELETEs
    await Promise.all(state.removedPhotoIds.map((id) => deletePhoto(auction.id, id)));
    // Upload staged
    const uploaded: StagedPhoto[] = [];
    for (const p of state.stagedPhotos) {
      if (p.error) continue;
      try {
        const dto = await uploadPhoto(auction.id, p.file);
        uploaded.push({ ...p, uploadedPhotoId: dto.id, error: null });
      } catch (e: unknown) {
        uploaded.push({ ...p, error: (e as Error).message ?? 'Upload failed' });
      }
    }

    // Refetch to get canonical server state
    const refreshed = await getAuction(auction.id);
    setState((s) => ({
      ...s,
      auctionId: refreshed.id,
      stagedPhotos: uploaded.filter((p) => p.error),
      uploadedPhotoIds: refreshed.photos.map((p) => p.id),
      removedPhotoIds: [],
      dirty: false,
    }));
    qc.setQueryData(['auction', refreshed.id], refreshed);
    return refreshed;
  }, [state, qc]);

  const addStagedPhotos = useCallback((next: StagedPhoto[]) => {
    setState((s) => ({ ...s, stagedPhotos: next, dirty: true }));
  }, []);

  const removeUploadedPhoto = useCallback((id: string) => {
    setState((s) => ({
      ...s,
      uploadedPhotoIds: s.uploadedPhotoIds.filter((x) => x !== id),
      removedPhotoIds: [...s.removedPhotoIds, id],
      dirty: true,
    }));
  }, []);

  return useMemo(() => ({
    state, setParcel, update, addStagedPhotos, removeUploadedPhoto,
    save, isLoadingExisting: !!options.id && fetchQ.isLoading,
  }), [state, setParcel, update, addStagedPhotos, removeUploadedPhoto, save, options.id, fetchQ.isLoading]);
}
```

- [ ] **Step 8.2: Write hook test**

```tsx
describe('useListingDraft', () => {
  it('creates an auction on first save and PATCHes on second', async () => {
    let created = 0, updated = 0;
    server.use(
      http.post('*/api/v1/auctions', async () => { created++;
        return HttpResponse.json({ id: 'a1', /* minimum seller dto */ ... }); }),
      http.put('*/api/v1/auctions/a1', async () => { updated++;
        return HttpResponse.json({ id: 'a1', /* ... */ }); }),
      http.get('*/api/v1/auctions/a1', async () =>
        HttpResponse.json({ id: 'a1', /* ... */ })),
    );
    const { result } = renderHook(() => useListingDraft({}), { wrapper: withQueryClient });
    act(() => result.current.setParcel({ id: 'p1', /* ... */ } as ParcelDto));
    act(() => result.current.update('startingBid', 500));
    await act(async () => { await result.current.save(); });
    expect(created).toBe(1);
    await act(async () => { await result.current.save(); });
    expect(updated).toBe(1);
  });
});
```

- [ ] **Step 8.3: Implement `ListingWizardForm` (shared body)**

```tsx
'use client';

import { useState } from 'react';
import { Button } from '@/components/ui/Button';
import { Textarea } from '@/components/ui/Textarea';
import { ParcelLookupField } from './ParcelLookupField';
import { AuctionSettingsForm, type AuctionSettingsValue } from './AuctionSettingsForm';
import { TagSelector } from './TagSelector';
import { PhotoUploader } from './PhotoUploader';
import { ListingWizardLayout } from './ListingWizardLayout';
import { ListingPreviewCard } from './ListingPreviewCard';
import { useListingDraft } from '@/hooks/useListingDraft';
import { useRouter } from 'next/navigation';

export function ListingWizardForm({ id, mode }: { id?: string; mode: 'create' | 'edit' }) {
  const router = useRouter();
  const [step, setStep] = useState<'configure' | 'review'>('configure');
  const draft = useListingDraft({ id });
  const [saving, setSaving] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const settings: AuctionSettingsValue = {
    startingBid: draft.state.startingBid,
    reservePrice: draft.state.reservePrice,
    buyNowPrice: draft.state.buyNowPrice,
    durationHours: draft.state.durationHours,
    snipeProtect: draft.state.snipeProtect,
    snipeWindowMin: draft.state.snipeWindowMin,
  };

  async function handleSave() {
    setSaving(true); setError(null);
    try { await draft.save(); } catch (e: unknown) { setError((e as Error).message); }
    finally { setSaving(false); }
  }

  async function handleContinue() {
    await handleSave();
    if (!error) setStep('review');
  }

  async function handleSubmit() {
    setSubmitting(true); setError(null);
    try {
      const refreshed = await draft.save();
      router.push(`/listings/${refreshed.id}/activate`);
    } catch (e: unknown) {
      setError((e as Error).message); setSubmitting(false);
    }
  }

  const parcel = draft.state.parcel;
  const saveLabel = mode === 'edit' && id ? 'Save changes' : 'Save as Draft';

  if (step === 'configure') {
    return (
      <ListingWizardLayout steps={['Configure', 'Review & Submit']} currentIndex={0}
        title={mode === 'edit' ? 'Edit listing' : 'Create a listing'}
        footer={<>
          <Button variant="secondary" onClick={handleSave} disabled={saving || !parcel}>{saveLabel}</Button>
          <Button onClick={handleContinue} disabled={saving || !parcel}>Continue to Review</Button>
        </>}>
        <div className="space-y-6">
          {error && <div className="rounded bg-rose-50 p-3 text-sm text-rose-700">{error}</div>}
          <ParcelLookupField
            initialParcel={parcel}
            locked={mode === 'edit'}
            onResolved={(p) => draft.setParcel(p)}
          />
          {parcel && (
            <>
              <section>
                <h2 className="mb-3 text-lg font-medium">Auction settings</h2>
                <AuctionSettingsForm value={settings} onChange={(v) => {
                  draft.update('startingBid', v.startingBid);
                  draft.update('reservePrice', v.reservePrice);
                  draft.update('buyNowPrice', v.buyNowPrice);
                  draft.update('durationHours', v.durationHours);
                  draft.update('snipeProtect', v.snipeProtect);
                  draft.update('snipeWindowMin', v.snipeWindowMin);
                }} />
              </section>
              <section>
                <h2 className="mb-3 text-lg font-medium">Description</h2>
                <Textarea rows={5} maxLength={5000} value={draft.state.sellerDesc}
                  onChange={(e) => draft.update('sellerDesc', e.target.value)} />
                <p className="text-xs text-slate-500">{draft.state.sellerDesc.length}/5000</p>
              </section>
              <section>
                <h2 className="mb-3 text-lg font-medium">Tags</h2>
                <TagSelector value={draft.state.tags} onChange={(t) => draft.update('tags', t)} />
              </section>
              <section>
                <h2 className="mb-3 text-lg font-medium">Photos</h2>
                <PhotoUploader staged={draft.state.stagedPhotos}
                  onStagedChange={(next) => draft.addStagedPhotos(next)} />
              </section>
            </>
          )}
        </div>
      </ListingWizardLayout>
    );
  }

  // Review step
  return (
    <ListingWizardLayout steps={['Configure', 'Review & Submit']} currentIndex={1} title="Review & Submit"
      footer={<>
        <Button variant="secondary" onClick={() => setStep('configure')}>Back to edit</Button>
        <Button onClick={handleSubmit} disabled={submitting}>
          {submitting ? 'Submitting…' : 'Submit'}
        </Button>
      </>}>
      {error && <div className="rounded bg-rose-50 p-3 text-sm text-rose-700">{error}</div>}
      {parcel && (
        <ListingPreviewCard isPreview auction={{
          parcel,
          startingBid: String(draft.state.startingBid),
          reservePrice: draft.state.reservePrice ? String(draft.state.reservePrice) : null,
          buyNowPrice: draft.state.buyNowPrice ? String(draft.state.buyNowPrice) : null,
          durationHours: draft.state.durationHours,
          tags: [],
          photos: draft.state.stagedPhotos.map((p) => ({ id: p.id, sortOrder: 0, bytesUrl: p.objectUrl })),
          sellerDesc: draft.state.sellerDesc || null,
        }} />
      )}
    </ListingWizardLayout>
  );
}
```

- [ ] **Step 8.4: Implement the create page**

`frontend/src/app/listings/create/page.tsx`:

```tsx
import { ListingWizardForm } from '@/components/listing/ListingWizardForm';

export default function CreateListingPage() {
  return <ListingWizardForm mode="create" />;
}
```

- [ ] **Step 8.5: Implement the edit page**

`frontend/src/app/listings/[id]/edit/page.tsx`:

```tsx
import { ListingWizardForm } from '@/components/listing/ListingWizardForm';

export default function EditListingPage({ params }: { params: { id: string } }) {
  return <ListingWizardForm mode="edit" id={params.id} />;
}
```

Note: Next.js 16 App Router — verify whether `params` is awaited per framework docs. If so, make the page async and `await params`.

- [ ] **Step 8.6: Integration test — full create flow happy path**

```tsx
describe('Create listing flow', () => {
  it('lookup -> settings -> tags -> review -> submit -> navigate to activate', async () => {
    const createdAuctionId = 'a1';
    server.use(
      http.post('*/api/v1/parcels/lookup', () => HttpResponse.json({
        id: 'p1', slParcelUuid: '00000000-0000-0000-0000-000000000001',
        ownerUuid: 'owner', ownerType: 'individual', regionName: 'R', gridX: 1, gridY: 1,
        continentName: 'Heterocera', areaSqm: 512, description: 'Nice parcel',
        snapshotUrl: null, slurl: 'secondlife://R/1/1/1', maturityRating: 'GENERAL',
        verified: true, verifiedAt: null, lastChecked: null, createdAt: '2026-04-17T00:00:00Z'
      })),
      http.get('*/api/v1/parcel-tags', () => HttpResponse.json([
        { category: 'Terrain', tags: [{ code: 'beach', label: 'Beach', description: null, sortOrder: 1 }] }
      ])),
      http.post('*/api/v1/auctions', () => HttpResponse.json({ id: createdAuctionId, /* seller dto */ })),
      http.put(`*/api/v1/auctions/${createdAuctionId}`, () => HttpResponse.json({ id: createdAuctionId })),
      http.get(`*/api/v1/auctions/${createdAuctionId}`, () => HttpResponse.json({
        id: createdAuctionId, photos: [], tags: [], /* rest of DTO fields */
      })),
    );

    const router = { push: vi.fn() };
    vi.mock('next/navigation', () => ({ useRouter: () => router }));

    render(wrapWithProviders(<CreateListingPage />));
    await userEvent.type(screen.getByLabelText(/Parcel UUID/i), '00000000-0000-0000-0000-000000000001');
    await userEvent.click(screen.getByRole('button', { name: /look up/i }));
    await screen.findByText(/Heterocera/);
    await userEvent.clear(screen.getByLabelText(/Starting bid/i));
    await userEvent.type(screen.getByLabelText(/Starting bid/i), '500');
    await userEvent.click(screen.getByRole('button', { name: /Continue to Review/i }));
    await screen.findByText(/Preview/i);
    await userEvent.click(screen.getByRole('button', { name: /^Submit$/ }));
    await waitFor(() => expect(router.push).toHaveBeenCalledWith(`/listings/${createdAuctionId}/activate`));
  });
});
```

- [ ] **Step 8.7: Run frontend tests + lint**

```bash
npm test -- --run && npm run lint && npm run build
```

Expected: all PASS; build succeeds.

- [ ] **Step 8.8: Commit**

```bash
git add frontend/
git commit -m "feat(listing): create + edit wizard pages with shared ListingWizardForm

useListingDraft hook owns the form state plus photo staging and
sessionStorage persistence; syncs to the server only on explicit
Save as Draft / Continue to Review / Submit. UUID field is locked
in edit mode. Submit navigates to /listings/[id]/activate."
```

---

## Task 9 — Activate page + method-specific panels + `useActivateAuction` hook + `CancelListingModal`

**Files:**
- Create: `frontend/src/hooks/useActivateAuction.ts`
- Create: `frontend/src/app/listings/[id]/activate/page.tsx`
- Create: `frontend/src/components/listing/ActivateStatusStepper.tsx`
- Create: `frontend/src/components/listing/FeePaymentInstructions.tsx`
- Create: `frontend/src/components/listing/VerificationMethodPicker.tsx`
- Create: `frontend/src/components/listing/VerificationInProgressPanel.tsx`
- Create: `frontend/src/components/listing/VerificationMethodUuidEntry.tsx`
- Create: `frontend/src/components/listing/VerificationMethodRezzable.tsx`
- Create: `frontend/src/components/listing/VerificationMethodSaleToBot.tsx`
- Create: `frontend/src/components/listing/CancelListingModal.tsx`
- Create: component tests

- [ ] **Step 9.1: Implement `useActivateAuction`**

```ts
'use client';

import { useQuery } from '@tanstack/react-query';
import { getAuction } from '@/lib/api/auctions';
import { isTerminal } from '@/lib/listing/auctionStatus';

export function useActivateAuction(id: string) {
  return useQuery({
    queryKey: ['auction', id, 'activate'],
    queryFn: () => getAuction(id),
    refetchInterval: (q) => q.state.data && isTerminal(q.state.data.status) ? false : 5000,
    refetchIntervalInBackground: false,
    refetchOnWindowFocus: true,
  });
}
```

- [ ] **Step 9.2: Implement `ActivateStatusStepper`**

```tsx
import { Stepper } from '@/components/ui/Stepper';
import type { AuctionStatus } from '@/types/auction';

const LABELS = ['Draft', 'Paid', 'Verifying', 'Active'];

export function ActivateStatusStepper({ status }: { status: AuctionStatus }) {
  const idx =
    status === 'DRAFT' ? 0 :
    status === 'DRAFT_PAID' || status === 'VERIFICATION_FAILED' ? 1 :
    status === 'VERIFICATION_PENDING' ? 2 :
    3;
  return <Stepper steps={LABELS} currentIndex={idx} />;
}
```

- [ ] **Step 9.3: Implement `FeePaymentInstructions`**

```tsx
'use client';

import { useListingFeeConfig } from '@/hooks/useListingFeeConfig';

export function FeePaymentInstructions({ auctionId }: { auctionId: string }) {
  const { data } = useListingFeeConfig();
  const fee = data?.amountLindens ?? null;
  const shortId = auctionId.slice(0, 8);

  return (
    <div className="rounded-lg border border-slate-200 bg-white p-6 space-y-4">
      <h2 className="text-xl font-semibold">Pay the listing fee</h2>
      <p className="text-sm text-slate-700">
        Head to an SLPA terminal in-world and pay{' '}
        <strong>L${fee ?? '…'}</strong> with reference code{' '}
        <code className="rounded bg-slate-100 px-1 py-0.5">LISTING-{shortId}</code>.
        Once the platform detects your payment, this page advances automatically.
      </p>
      <p className="text-xs text-slate-500">
        In-world payment terminals roll out in a later epic. Dev environments can use the staging endpoint
        to advance this listing.
      </p>
    </div>
  );
}
```

- [ ] **Step 9.4: Implement `VerificationMethodPicker`**

```tsx
'use client';

import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Button } from '@/components/ui/Button';
import { AlertTriangle } from '@/components/ui/icons';
import { triggerVerify } from '@/lib/api/auctions';
import type { VerificationMethod, SellerAuctionResponse } from '@/types/auction';

interface MethodCard { key: VerificationMethod; title: string; body: string; }

const METHODS: MethodCard[] = [
  { key: 'UUID_ENTRY', title: 'Manual UUID check',
    body: "Fastest. We check your avatar UUID against the parcel's owner via the SL World API. Works for individually-owned land only." },
  { key: 'REZZABLE', title: 'Rezzable terminal',
    body: 'We give you a one-time code. Rez an SLPA parcel terminal on your land and it verifies on your behalf. Works for individually-owned land.' },
  { key: 'SALE_TO_BOT', title: 'Sale-to-bot',
    body: 'Set your land for sale to SLPAEscrow Resident at L$999,999,999. Our bot detects the sale. Required for group-owned land.' },
];

export function VerificationMethodPicker({ auctionId, lastFailureNotes }:
  { auctionId: string; lastFailureNotes?: string | null }) {
  const qc = useQueryClient();
  const [error, setError] = useState<string | null>(null);

  const m = useMutation({
    mutationFn: (method: VerificationMethod) => triggerVerify(auctionId, { method }),
    onSuccess: (a: SellerAuctionResponse) => qc.setQueryData(['auction', auctionId, 'activate'], a),
    onError: (e: Error & { status?: number }) => setError(
      e.status === 422
        ? 'This method doesn\'t work for group-owned land. Pick Sale-to-bot instead.'
        : e.message ?? 'Verification could not be started.'
    ),
  });

  return (
    <div className="space-y-4">
      {lastFailureNotes && (
        <div className="flex items-start gap-2 rounded-lg border border-amber-300 bg-amber-50 p-3">
          <AlertTriangle className="text-amber-600 shrink-0" size={18} />
          <div>
            <p className="font-medium text-amber-900">Your last attempt failed</p>
            <p className="text-sm text-amber-800">{lastFailureNotes}</p>
            <p className="mt-1 text-xs text-amber-800">Pick a method to try again — no additional fee needed.</p>
          </div>
        </div>
      )}
      {error && <div className="rounded bg-rose-50 p-3 text-sm text-rose-700">{error}</div>}
      <div className="grid gap-3 md:grid-cols-3">
        {METHODS.map((method) => (
          <div key={method.key} className="rounded-lg border border-slate-200 bg-white p-4 space-y-2">
            <h3 className="font-semibold">{method.title}</h3>
            <p className="text-sm text-slate-600">{method.body}</p>
            <Button onClick={() => m.mutate(method.key)} disabled={m.isPending}>
              {m.isPending ? 'Starting…' : 'Use this method'}
            </Button>
          </div>
        ))}
      </div>
    </div>
  );
}
```

- [ ] **Step 9.5: Implement method-specific in-progress sub-panels**

`VerificationMethodUuidEntry.tsx`:

```tsx
'use client';

import { useEffect, useState } from 'react';
import { Loader2 } from '@/components/ui/icons';

export function VerificationMethodUuidEntry() {
  const [slow, setSlow] = useState(false);
  useEffect(() => { const t = setTimeout(() => setSlow(true), 10_000); return () => clearTimeout(t); }, []);
  return (
    <div className="rounded-lg border border-slate-200 bg-white p-6 space-y-3 text-center">
      <Loader2 className="mx-auto animate-spin text-indigo-500" size={28} />
      <p className="font-medium">Checking ownership with the Second Life World API…</p>
      {slow && <p className="text-sm text-slate-500">This is taking longer than usual.</p>}
    </div>
  );
}
```

`VerificationMethodRezzable.tsx`:

```tsx
'use client';

import { useMutation } from '@tanstack/react-query';
import { CodeDisplay } from '@/components/ui/CodeDisplay';
import { CountdownTimer } from '@/components/ui/CountdownTimer';
import { Button } from '@/components/ui/Button';
import { triggerVerify } from '@/lib/api/auctions';
import type { PendingVerification } from '@/types/auction';

export function VerificationMethodRezzable({ auctionId, pending }:
  { auctionId: string; pending: PendingVerification }) {
  const regen = useMutation({
    mutationFn: () => triggerVerify(auctionId, { method: 'REZZABLE' }),
  });
  const expired = pending.codeExpiresAt ? new Date(pending.codeExpiresAt) < new Date() : false;

  return (
    <div className="space-y-4">
      {pending.code && (
        <div className="rounded-lg border border-slate-200 bg-white p-6 space-y-3 text-center">
          <p className="text-sm uppercase tracking-wide text-slate-500">Parcel verification code</p>
          <CodeDisplay code={pending.code} />
          {pending.codeExpiresAt && !expired && (
            <p className="text-sm text-slate-500">
              Expires in <CountdownTimer to={pending.codeExpiresAt} />
            </p>
          )}
          {expired && <Button onClick={() => regen.mutate()} disabled={regen.isPending}>Regenerate code</Button>}
        </div>
      )}
      <ol className="rounded-lg border border-slate-200 bg-white p-6 space-y-2 text-sm list-decimal list-inside">
        <li>Open your SL inventory and find the SLPA Parcel Terminal object.</li>
        <li>Rez it on the parcel you're listing.</li>
        <li>The terminal automatically verifies ownership and advances your listing.</li>
      </ol>
    </div>
  );
}
```

`VerificationMethodSaleToBot.tsx`:

```tsx
import type { PendingVerification } from '@/types/auction';

export function VerificationMethodSaleToBot({ pending }: { pending: PendingVerification }) {
  return (
    <div className="space-y-4">
      <div className="rounded-lg border border-slate-200 bg-white p-6 space-y-2">
        <h3 className="font-semibold">Set your land for sale to SLPAEscrow Resident</h3>
        <ol className="list-decimal list-inside space-y-1 text-sm">
          <li>Open the SL Land menu.</li>
          <li>Find the parcel you're listing.</li>
          <li>Choose <em>Set Land for Sale…</em></li>
          <li>Buyer: <strong>SLPAEscrow Resident</strong></li>
          <li>Price: <strong>L$999,999,999</strong></li>
          <li>Click <em>Sell</em> to confirm.</li>
        </ol>
        <p className="text-xs text-slate-500">
          The bot will detect the sale within a few minutes. You do not need to keep this page open.
        </p>
      </div>
      {pending.instructions && (
        <div className="rounded bg-slate-50 p-3 text-sm text-slate-700">{pending.instructions}</div>
      )}
    </div>
  );
}
```

- [ ] **Step 9.6: Implement `VerificationInProgressPanel` dispatcher**

```tsx
import type { SellerAuctionResponse } from '@/types/auction';
import { VerificationMethodUuidEntry } from './VerificationMethodUuidEntry';
import { VerificationMethodRezzable } from './VerificationMethodRezzable';
import { VerificationMethodSaleToBot } from './VerificationMethodSaleToBot';

export function VerificationInProgressPanel({ auction }: { auction: SellerAuctionResponse }) {
  switch (auction.verificationMethod) {
    case 'UUID_ENTRY': return <VerificationMethodUuidEntry />;
    case 'REZZABLE':   return auction.pendingVerification
        ? <VerificationMethodRezzable auctionId={auction.id} pending={auction.pendingVerification} />
        : null;
    case 'SALE_TO_BOT': return auction.pendingVerification
        ? <VerificationMethodSaleToBot pending={auction.pendingVerification} />
        : null;
    default: return null;
  }
}
```

- [ ] **Step 9.7: Implement `CancelListingModal`**

```tsx
'use client';

import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Modal } from '@/components/ui/Modal';
import { Button } from '@/components/ui/Button';
import { Textarea } from '@/components/ui/Textarea';
import { cancelAuction } from '@/lib/api/auctions';
import { computeRefund } from '@/lib/listing/refundCalculation';
import type { SellerAuctionResponse } from '@/types/auction';
import { useRouter } from 'next/navigation';

export function CancelListingModal({ open, onClose, auction }:
  { open: boolean; onClose: () => void; auction: SellerAuctionResponse }) {
  const [reason, setReason] = useState('');
  const qc = useQueryClient();
  const router = useRouter();
  const refund = computeRefund(auction.status, auction.listingFeeAmt);

  const m = useMutation({
    mutationFn: () => cancelAuction(auction.id, { reason: reason.trim() || undefined }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['my-listings'] });
      router.push('/dashboard/listings');
    },
  });

  return (
    <Modal open={open} onClose={onClose}>
      <div className="p-6 space-y-4">
        <h2 className="text-lg font-semibold">Cancel this listing?</h2>
        <p className="text-sm">
          <strong>{auction.parcel.description || '(unnamed parcel)'}</strong> — {auction.status}
        </p>
        <p className="text-sm">{refund.copy}</p>
        <Textarea rows={3} placeholder="Reason (optional)" value={reason}
          onChange={(e) => setReason(e.target.value)} />
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose}>Keep listing</Button>
          <Button variant="destructive" onClick={() => m.mutate()} disabled={m.isPending}>
            {m.isPending ? 'Cancelling…' : 'Cancel listing'}
          </Button>
        </div>
      </div>
    </Modal>
  );
}
```

- [ ] **Step 9.8: Implement the activate page**

```tsx
'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { useActivateAuction } from '@/hooks/useActivateAuction';
import { ActivateStatusStepper } from '@/components/listing/ActivateStatusStepper';
import { FeePaymentInstructions } from '@/components/listing/FeePaymentInstructions';
import { VerificationMethodPicker } from '@/components/listing/VerificationMethodPicker';
import { VerificationInProgressPanel } from '@/components/listing/VerificationInProgressPanel';
import { CancelListingModal } from '@/components/listing/CancelListingModal';
import { Button } from '@/components/ui/Button';
import { CheckCircle2 } from '@/components/ui/icons';

export default function ActivateListingPage({ params }: { params: { id: string } }) {
  const { data: auction, isLoading, error } = useActivateAuction(params.id);
  const [cancelOpen, setCancelOpen] = useState(false);
  const router = useRouter();

  if (isLoading) return <p className="p-6">Loading…</p>;
  if (error || !auction) return <p className="p-6 text-rose-600">Could not load listing.</p>;

  if (auction.status === 'ACTIVE') {
    return (
      <div className="mx-auto max-w-3xl space-y-6 p-6 text-center">
        <CheckCircle2 className="mx-auto text-emerald-500" size={48} />
        <h1 className="text-2xl font-semibold">Your listing is live.</h1>
        <div className="flex justify-center gap-3">
          <Button variant="secondary" onClick={() => router.push('/dashboard/listings')}>Back to My Listings</Button>
          <Button onClick={() => router.push(`/auction/${auction.id}`)}>View public listing</Button>
        </div>
      </div>
    );
  }
  if (['CANCELLED', 'SUSPENDED', 'ENDED', 'EXPIRED', 'COMPLETED', 'ESCROW_PENDING',
       'ESCROW_FUNDED', 'TRANSFER_PENDING', 'DISPUTED'].includes(auction.status)) {
    router.replace('/dashboard/listings');
    return null;
  }

  return (
    <div className="mx-auto max-w-3xl space-y-6 p-6">
      <ActivateStatusStepper status={auction.status} />
      {auction.status === 'DRAFT' && <FeePaymentInstructions auctionId={auction.id} />}
      {(auction.status === 'DRAFT_PAID' || auction.status === 'VERIFICATION_FAILED') && (
        <VerificationMethodPicker auctionId={auction.id}
          lastFailureNotes={auction.status === 'VERIFICATION_FAILED' ? auction.verificationNotes : null} />
      )}
      {auction.status === 'VERIFICATION_PENDING' && <VerificationInProgressPanel auction={auction} />}
      <div className="border-t border-slate-200 pt-4">
        <button type="button" className="text-sm text-rose-600 underline" onClick={() => setCancelOpen(true)}>
          Cancel this listing
        </button>
      </div>
      <CancelListingModal open={cancelOpen} onClose={() => setCancelOpen(false)} auction={auction} />
    </div>
  );
}
```

- [ ] **Step 9.9: Integration test — DRAFT_PAID → method pick → VERIFICATION_PENDING**

```tsx
describe('Activate page', () => {
  it('DRAFT_PAID renders method picker and triggers verify on click', async () => {
    const base = { id: 'a1', status: 'DRAFT_PAID', verificationMethod: null, pendingVerification: null,
      parcel: { /* ... */ }, verificationNotes: null, listingFeeAmt: '100', /* minimum */ };
    let calls = 0;
    server.use(
      http.get('*/api/v1/auctions/a1', () => { calls++;
        return HttpResponse.json(calls < 2 ? base
          : { ...base, status: 'VERIFICATION_PENDING',
              verificationMethod: 'UUID_ENTRY', pendingVerification: { method: 'UUID_ENTRY', code: null,
                codeExpiresAt: null, botTaskId: null, instructions: null } }); }),
      http.put('*/api/v1/auctions/a1/verify', () => HttpResponse.json({ ...base, status: 'VERIFICATION_PENDING' })),
    );
    render(wrap(<ActivateListingPage params={{ id: 'a1' }} />));
    const pickerBtn = await screen.findAllByText(/Use this method/i);
    await userEvent.click(pickerBtn[0]);
    await screen.findByText(/Checking ownership/i);
  });

  it('VERIFICATION_FAILED shows retry banner', async () => {
    server.use(http.get('*/api/v1/auctions/a1', () => HttpResponse.json({
      id: 'a1', status: 'VERIFICATION_FAILED', verificationNotes: 'Ownership check failed',
      verificationMethod: 'UUID_ENTRY', pendingVerification: null, listingFeeAmt: '100',
      parcel: { /* ... */ } })));
    render(wrap(<ActivateListingPage params={{ id: 'a1' }} />));
    await screen.findByText(/Ownership check failed/i);
  });
});
```

- [ ] **Step 9.10: Run tests and lint**

```bash
npm test -- --run && npm run lint && npm run build
```

Expected: PASS.

- [ ] **Step 9.11: Commit**

```bash
git add frontend/
git commit -m "feat(listing): activate page with fee/method/verify state machine

/listings/[id]/activate polls with visibility-aware refetch and
renders state-specific panels: FeePaymentInstructions for DRAFT,
VerificationMethodPicker for DRAFT_PAID and VERIFICATION_FAILED
(with retry banner), VerificationInProgressPanel for
VERIFICATION_PENDING (dispatches to UUID/Rezzable/SaleToBot
sub-panels), success screen for ACTIVE. CancelListingModal
computes the correct refund amount per status and calls the
cancel endpoint."
```

---

## Task 10 — My Listings tab + full-flow smoke test + README + FOOTGUNS + DEFERRED_WORK + PR

**Files:**
- Create: `frontend/src/hooks/useMyListings.ts`
- Create: `frontend/src/components/listing/MyListingsTab.tsx`
- Create: `frontend/src/components/listing/ListingSummaryRow.tsx`
- Create: `frontend/src/components/listing/FilterChipsRow.tsx`
- Modify: `frontend/src/app/dashboard/(verified)/listings/page.tsx`
- Modify: `README.md`
- Modify: `docs/implementation/FOOTGUNS.md`
- Modify: `docs/implementation/DEFERRED_WORK.md`

- [ ] **Step 10.1: Implement `useMyListings`**

```ts
'use client';

import { useQuery } from '@tanstack/react-query';
import { listMyAuctions } from '@/lib/api/auctions';
import { FILTER_GROUPS } from '@/lib/listing/auctionStatus';

export type ListFilter = 'All' | keyof typeof FILTER_GROUPS;

export function useMyListings({ filter = 'All', page = 0, size = 20 }:
  { filter?: ListFilter; page?: number; size?: number }) {
  const statuses = filter === 'All' ? undefined : FILTER_GROUPS[filter].join(',');
  return useQuery({
    queryKey: ['my-listings', { filter, page, size }],
    queryFn: () => listMyAuctions({ status: statuses, page, size }),
    refetchInterval: 30_000,
    refetchIntervalInBackground: false,
  });
}

export function useMyListingsSuspendedCount() {
  return useQuery({
    queryKey: ['my-listings', 'suspended-count'],
    queryFn: () => listMyAuctions({ status: 'SUSPENDED', page: 0, size: 1 }),
    staleTime: 5 * 60 * 1000,
  });
}
```

- [ ] **Step 10.2: Implement `FilterChipsRow`**

```tsx
'use client';

import { clsx } from 'clsx';
import type { ListFilter } from '@/hooks/useMyListings';

export interface FilterChipsRowProps {
  value: ListFilter;
  onChange: (v: ListFilter) => void;
  showSuspended: boolean;
}

export function FilterChipsRow({ value, onChange, showSuspended }: FilterChipsRowProps) {
  const options: ListFilter[] = ['All', 'Active', 'Drafts', 'Ended', 'Cancelled'];
  if (showSuspended) options.push('Suspended');
  return (
    <div className="flex flex-wrap gap-2">
      {options.map((opt) => (
        <button key={opt} onClick={() => onChange(opt)}
          className={clsx('rounded-full border px-3 py-1 text-sm',
            value === opt ? 'border-indigo-500 bg-indigo-500 text-white'
                          : 'border-slate-300 bg-white text-slate-700')}>
          {opt}
        </button>
      ))}
    </div>
  );
}
```

- [ ] **Step 10.3: Implement `ListingSummaryRow`**

```tsx
'use client';

import Link from 'next/link';
import { useState } from 'react';
import type { SellerAuctionResponse } from '@/types/auction';
import { ListingStatusBadge } from './ListingStatusBadge';
import { CancelListingModal } from './CancelListingModal';
import { Button } from '@/components/ui/Button';
import { MoreHorizontal, AlertTriangle } from '@/components/ui/icons';
import { isEditable, isPreActive } from '@/lib/listing/auctionStatus';

function thumbUrl(a: SellerAuctionResponse): string | null {
  if (a.photos[0]) return a.photos[0].bytesUrl;
  if (a.parcel.snapshotUrl) return a.parcel.snapshotUrl;
  return null;
}

export function ListingSummaryRow({ auction }: { auction: SellerAuctionResponse }) {
  const [cancelOpen, setCancelOpen] = useState(false);
  const canCancel = isPreActive(auction.status) || auction.status === 'ACTIVE';
  const thumb = thumbUrl(auction);

  const currentBid = auction.currentHighBid ? `L$${auction.currentHighBid}` : '—';
  const bidders = `${auction.bidderCount} bidder${auction.bidderCount === 1 ? '' : 's'}`;

  return (
    <div className="rounded-lg border border-slate-200 bg-white p-3 space-y-2">
      <div className="flex gap-3">
        {thumb ? <img src={thumb} alt="" className="h-20 w-20 rounded object-cover" /> :
          <div className="h-20 w-20 rounded bg-slate-100" />}
        <div className="flex-1 min-w-0">
          <div className="flex items-center justify-between gap-2">
            <p className="truncate font-medium">{auction.parcel.description || '(unnamed parcel)'}</p>
            <ListingStatusBadge status={auction.status} />
          </div>
          <p className="text-xs text-slate-500">{auction.parcel.regionName} · {auction.parcel.areaSqm} m²</p>
          <p className="text-xs text-slate-600">
            L${auction.startingBid} start · Bid {currentBid} · {bidders}
          </p>
        </div>
        <div className="flex flex-col items-end gap-1">
          {isEditable(auction.status) && (
            <Link href={`/listings/${auction.id}/edit`}>
              <Button size="sm" variant="secondary">Edit</Button>
            </Link>
          )}
          {isPreActive(auction.status) && (
            <Link href={`/listings/${auction.id}/activate`}>
              <Button size="sm">Continue</Button>
            </Link>
          )}
          {auction.status === 'ACTIVE' && (
            <Link href={`/auction/${auction.id}`}>
              <Button size="sm" variant="secondary">View listing</Button>
            </Link>
          )}
          {canCancel && (
            <button aria-label="More actions"
              className="rounded p-1 text-slate-500 hover:bg-slate-100"
              onClick={() => setCancelOpen(true)}>
              <MoreHorizontal size={16} />
            </button>
          )}
        </div>
      </div>
      {auction.status === 'SUSPENDED' && (
        <div className="flex items-start gap-2 rounded-md bg-red-50 p-2 text-sm text-red-800">
          <AlertTriangle size={16} className="shrink-0 mt-0.5" />
          <p>Listing suspended. Contact support if you believe this is a mistake.</p>
        </div>
      )}
      {canCancel && <CancelListingModal open={cancelOpen} onClose={() => setCancelOpen(false)} auction={auction} />}
    </div>
  );
}
```

- [ ] **Step 10.4: Implement `MyListingsTab`**

```tsx
'use client';

import Link from 'next/link';
import { useState } from 'react';
import { useMyListings, useMyListingsSuspendedCount, type ListFilter } from '@/hooks/useMyListings';
import { FilterChipsRow } from './FilterChipsRow';
import { ListingSummaryRow } from './ListingSummaryRow';
import { Button } from '@/components/ui/Button';
import { EmptyState } from '@/components/ui/EmptyState';
import { Plus, Gavel } from '@/components/ui/icons';

export function MyListingsTab() {
  const [filter, setFilter] = useState<ListFilter>('All');
  const list = useMyListings({ filter });
  const suspended = useMyListingsSuspendedCount();
  const showSuspended = (suspended.data?.length ?? 0) > 0;

  return (
    <div className="space-y-4 p-4">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold">My Listings</h1>
        <Link href="/listings/create">
          <Button><Plus size={16} /> Create new listing</Button>
        </Link>
      </div>
      <FilterChipsRow value={filter} onChange={setFilter} showSuspended={showSuspended} />
      {list.isLoading && <p className="text-sm text-slate-500">Loading…</p>}
      {list.data && list.data.length === 0 && (
        <EmptyState icon={Gavel}
          title="No listings yet"
          description="Create your first listing to get started."
          action={<Link href="/listings/create"><Button>Create a listing</Button></Link>} />
      )}
      {list.data && list.data.length > 0 && (
        <div className="space-y-2">
          {list.data.map((a) => <ListingSummaryRow key={a.id} auction={a} />)}
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 10.5: Wire the dashboard tab**

Replace the contents of `frontend/src/app/dashboard/(verified)/listings/page.tsx`:

```tsx
import { MyListingsTab } from '@/components/listing/MyListingsTab';

export default function MyListingsPage() {
  return <MyListingsTab />;
}
```

- [ ] **Step 10.6: Integration test — My Listings happy path**

```tsx
describe('MyListingsTab', () => {
  it('lists auctions with status-specific actions', async () => {
    server.use(
      http.get('*/api/v1/users/me/auctions*', () => HttpResponse.json([
        { id: 'a1', status: 'DRAFT', parcel: { description: 'Beach 1', regionName: 'R', areaSqm: 512,
          snapshotUrl: null }, photos: [], tags: [], startingBid: '500',
          currentHighBid: null, bidderCount: 0, listingFeeAmt: null, /* ... */ },
        { id: 'a2', status: 'ACTIVE', parcel: { description: 'Plaza', regionName: 'R2', areaSqm: 1024,
          snapshotUrl: null }, photos: [], tags: [], startingBid: '1000',
          currentHighBid: '1500', bidderCount: 3, listingFeeAmt: '100', /* ... */ },
      ])),
    );
    render(wrap(<MyListingsTab />));
    await screen.findByText('Beach 1');
    await screen.findByText('Plaza');
    // DRAFT shows Edit + Continue
    expect(screen.getAllByRole('link', { name: /Edit/i }).length).toBeGreaterThan(0);
    expect(screen.getAllByRole('link', { name: /Continue/i }).length).toBeGreaterThan(0);
    // ACTIVE shows View listing
    expect(screen.getAllByRole('link', { name: /View listing/i }).length).toBeGreaterThan(0);
    // Bid summary rendering
    expect(screen.getByText(/Bid L\$1500 · 3 bidders/)).toBeInTheDocument();
    expect(screen.getByText(/Bid — · 0 bidders/)).toBeInTheDocument();
  });
});
```

- [ ] **Step 10.7: Full-flow smoke test (optional but valuable)**

Add `frontend/src/e2e/listing-full-flow.test.tsx` (or under a smoke folder) that exercises create → save → continue → submit → activate with MSW:

```tsx
describe('Listing full flow (smoke)', () => {
  it('creates a listing, goes through fee pay (dev), picks method, lands ACTIVE', async () => {
    // stub parcel lookup, tags, auctions create/get/put, verify trigger, dev/pay
    // render CreateListingPage, then simulate redirect to Activate
    // advance via mocked status transitions
    // assert final success screen
  });
});
```

Because this test spans multiple routes, compose the rendering with Next.js's recommended test wrapper (or use `@testing-library/react` with a router mock). If it becomes unwieldy, leave it as a plan-level TODO note and ensure the per-page integration tests cover the flow — skip without checking in a broken smoke test.

- [ ] **Step 10.8: Run full frontend suite + backend suite**

```bash
cd frontend && npm test -- --run && npm run lint && npm run build
cd ../backend && ./mvnw test
```

Expected: all PASS.

- [ ] **Step 10.9: Update `README.md`**

Sweep the root README. Update:
- The "What works today" or equivalent status section to mention listing create/edit, activate flow, My Listings tab, ownership monitoring.
- Any test count badges (if present) to the new totals (backend and frontend).
- Screenshot/section for the new pages if screenshots are part of the project's README pattern — otherwise just text.

- [ ] **Step 10.10: Update `docs/implementation/FOOTGUNS.md`**

Append entries for gotchas encountered during implementation. Examples to watch for:
- Session-storage hydration races when `useListingDraft` mounts mid-hydration
- Polling still firing on a terminal status if `refetchInterval` isn't set to `false`
- Photo upload failure after auction creation — handle retry without duplicating auction creation
- `ddl-auto: update` doesn't drop columns; removing `verificationMethod` from create leaves the column in place (nullable)
- `@Async` without `@EnableAsync` silently runs synchronously — verify `BackendApplication` has the annotation

- [ ] **Step 10.11: Update `docs/implementation/DEFERRED_WORK.md`**

Add entries per spec §14:

```markdown
### Public listing page target for "View public listing" link
- **From:** Epic 03 sub-spec 2 (Task 03-05)
- **Why:** My Listings and the activate-success screen link to `/auction/[id]` — that route lands in Epic 04.
- **When:** Epic 04 (Auction Engine)
- **Notes:** The link currently 404s. Activate page works end-to-end regardless.

### Real in-world listing-fee terminal
- **From:** Epic 03 sub-spec 2 (Task 03-05 activate page DRAFT state)
- **Why:** The activate page shows production copy ("pay at an SLPA terminal"). The real terminal ships in Epic 05. Dev testing uses POST /api/v1/dev/auctions/{id}/pay.
- **When:** Epic 05 (Payments & Escrow)
- **Notes:** UI needs no change once Epic 05 lands — the polling picks up DRAFT_PAID automatically.

### Notifications for suspension events
- **From:** Epic 03 sub-spec 2 (Task 03-06)
- **Why:** Suspension writes a fraud_flag and sets auction status but does not notify the seller. Notification delivery ships in Epic 09.
- **When:** Epic 09 (Notifications)
- **Notes:** Seller sees the suspension via the dashboard banner today.

### Admin dashboard for fraud_flag resolution
- **From:** Epic 03 sub-spec 2 (Task 03-06)
- **Why:** fraud_flag rows accumulate but there's no admin UI to review/resolve them.
- **When:** Epic 10 (Admin & Moderation)
- **Notes:** The schema already has resolved, resolved_at, resolved_by_user_id fields ready for Epic 10.

### Non-dev admin endpoint to trigger ownership monitor
- **From:** Epic 03 sub-spec 2 (Task 03-06)
- **Why:** Dev-only POST /api/v1/dev/ownership-monitor/run exists; a role-guarded version for production admin use is Epic 10.
- **When:** Epic 10
- **Notes:** Same implementation, gated on ADMIN role instead of dev profile.
```

- [ ] **Step 10.12: Commit docs updates**

```bash
git add README.md docs/
git commit -m "docs: sub-spec 2 README sweep, FOOTGUNS updates, DEFERRED_WORK entries"
```

- [ ] **Step 10.13: Rebase onto `dev` if PR #17 merged meanwhile**

```bash
git fetch origin
if git log origin/dev --oneline | grep -q 'Epic 03 sub-spec 1'; then
  git rebase origin/dev
fi
```

Resolve any conflicts (likely in `AuctionStatus.java`, `AuctionVerificationService.java`, `Auction.java`, or test files).

- [ ] **Step 10.14: Push and open PR**

```bash
git push -u origin task/03-sub-2-listing-ui-ownership-monitoring

gh pr create --base dev --title "Epic 03 sub-spec 2: listing UI + ownership monitoring" --body "$(cat <<'EOF'
## Summary
- Listing creation flow: two-page wizard (Configure with progressive disclosure, Review & Submit) at `/listings/create`, edit at `/listings/[id]/edit`
- Dedicated activate page at `/listings/[id]/activate` covering fee payment, verification method selection, and per-method verification progress (UUID_ENTRY / REZZABLE / SALE_TO_BOT) with retry on failure
- My Listings dashboard tab wired with status-filtered chips, bid summary, and per-status actions
- Ongoing ownership monitoring: queue-style scheduler + `@Async` per-listing World API checks, new `SUSPENDED` status, `fraud_flag` entity
- Backend tweaks: `verificationMethod` moves from `POST /auctions` to `PUT /auctions/{id}/verify`, verification-failure transitions consolidate on `VERIFICATION_FAILED` (no auto-refund), new `GET /api/v1/config/listing-fee`, `currentHighBid` and `bidderCount` on auction DTOs

## Test plan
- [ ] Backend: `./mvnw test` green (baseline 413 + new tests for monitoring, DTO fields, failure consolidation)
- [ ] Frontend: `npm test -- --run`, `npm run lint`, `npm run build` all green
- [ ] Manual: create Method A listing end-to-end with dev pay endpoint and World API mock
- [ ] Manual: Method B — verify PARCEL code flow via Postman LSL callback
- [ ] Manual: Method C — verify SLPAEscrow bot task completion via dev endpoint
- [ ] Manual: cancel from DRAFT (no refund) and from DRAFT_PAID (full refund)
- [ ] Manual: ownership monitor via `POST /api/v1/dev/ownership-monitor/run` — an ACTIVE auction whose World API stub reports a different owner is transitioned to SUSPENDED with a fraud_flag row
EOF
)"
```

- [ ] **Step 10.15: Final sanity check**

```bash
gh pr view --web
```

Confirm the PR lists all commits from Tasks 1-10, base is `dev`, title matches, and the CI checks (if any) are green.

---

## Self-review checklist (post-plan, pre-execution)

- Spec §2-§8 coverage: Tasks 1-3 cover §7 tweaks, Tasks 4-5 cover §8, Tasks 6-10 cover §4-§6. ✓
- Spec §11 component inventory maps to Task 6 (primitives), Task 7 (listing components), Task 9 (activate panels), Task 10 (dashboard components). ✓
- Spec §12 testing strategy reflected in per-task test steps; integration + unit tests distributed appropriately. ✓
- No placeholders; every step has runnable commands or concrete code. ✓
- Type names consistent: `SellerAuctionResponse`, `AuctionStatus`, `VerificationMethod`, `FraudFlag*` used identically in every task. ✓
- Commit messages free of AI/tool attribution. ✓
- Branch strategy accounts for PR #17 still being open. ✓

