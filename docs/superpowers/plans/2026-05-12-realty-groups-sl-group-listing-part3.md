# Realty Groups E — Implementation Plan — Part 3 (Tasks 21–30)

Tasks in this file: ownership monitor case-3, member-departure reassignment split, dissolution gate extension, broker cancel, frontend, LSL changes, docs sweep + PR.

Read Parts 1 and 2 first.

---

## Task 21: `OwnershipCheckTask` — case-3 expected-owner

Implements spec §8.4.

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/monitoring/OwnershipCheckTask.java`
- Test: extend `OwnershipCheckTaskTest`

- [ ] **Step 1: Compute expected owner per-case**

In the task's per-auction loop, replace the existing "expected owner = seller.slAvatarUuid" line with:

```java
        java.util.UUID expectedOwner;
        if (auction.getRealtyGroupSlGroupId() != null) {
            // Case 3: parcel must remain owned by the registered SL group.
            com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroup reg =
                    slGroupRepo.findById(auction.getRealtyGroupSlGroupId()).orElse(null);
            expectedOwner = (reg == null) ? null : reg.getSlGroupUuid();
        } else {
            // Cases 1, individual: seller's avatar.
            expectedOwner = auction.getSeller() == null ? null : auction.getSeller().getSlAvatarUuid();
        }

        if (expectedOwner == null) {
            // Skip with a warning; this auction's owner-of-record can't be resolved.
            log.warn("OwnershipCheck skipping auction {} -- expected owner unresolvable", auction.getId());
            continue;
        }
```

Update the subsequent ownership-mismatch flag-and-suspend code to compare against `expectedOwner`.

Inject `RealtyGroupSlGroupRepository slGroupRepo` via the existing constructor field list.

- [ ] **Step 2: Test**

Add to `OwnershipCheckTaskTest`:

- `runOnce_case3_parcelOwnerMatchesRegistration_passes`
- `runOnce_case3_parcelOwnerNoLongerMatches_flagsAndSuspends`
- `runOnce_case3_registrationDeleted_skipsWithWarning`

- [ ] **Step 3: Run tests**

Run: `cd backend && ./mvnw test -Dtest=OwnershipCheckTaskTest`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/monitoring/OwnershipCheckTask.java \
        backend/src/test/java/com/slparcelauctions/backend/auction/monitoring/OwnershipCheckTaskTest.java
git commit -m "feat(realty-slgroup): case-3 expected-owner in OwnershipCheckTask"
git push
```

---

## Task 22: Member-departure reassignment split

Implements spec §10.

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionRepository.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/service/RealtyGroupMembershipService.java`
- Test: extend `RealtyGroupMembershipServiceTest`

- [ ] **Step 1: Repository queries**

Add to `AuctionRepository`:

```java
    /**
     * Sub-project E §10.2 — case-3 reassignment. Updates {@code seller_id} to the leader for
     * the departing member's active/draft/pending-verification listings under the given group.
     * {@code listing_agent_id} stays stable (commission attribution).
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("""
        UPDATE Auction a
           SET a.seller.id = :leaderId
         WHERE a.seller.id = :oldUserId
           AND a.realtyGroupSlGroupId IS NOT NULL
           AND a.realtyGroupId = :groupId
           AND a.status IN ('DRAFT','DRAFT_PAID','VERIFICATION_PENDING','VERIFICATION_FAILED','ACTIVE')
        """)
    int reassignSellerToLeaderForCase3(
            @org.springframework.data.repository.query.Param("oldUserId") Long oldUserId,
            @org.springframework.data.repository.query.Param("groupId") Long groupId,
            @org.springframework.data.repository.query.Param("leaderId") Long leaderId);

    /**
     * Case-1 legacy reassignment (existing C behavior, scoped to non-case-3 only).
     * {@code listing_agent_id} moves to the leader.
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("""
        UPDATE Auction a
           SET a.listingAgent.id = :leaderId
         WHERE a.listingAgent.id = :oldUserId
           AND a.realtyGroupSlGroupId IS NULL
           AND a.realtyGroupId = :groupId
           AND a.status IN ('DRAFT','DRAFT_PAID','VERIFICATION_PENDING','VERIFICATION_FAILED','ACTIVE')
        """)
    int reassignListingAgentToLeaderForCase1(
            @org.springframework.data.repository.query.Param("oldUserId") Long oldUserId,
            @org.springframework.data.repository.query.Param("groupId") Long groupId,
            @org.springframework.data.repository.query.Param("leaderId") Long leaderId);
```

The existing C-era reassignment query (which updated `listing_agent_id` unconditionally for the group) should be replaced/renamed so callers only reach the new split. Grep for the old method name and update call sites.

- [ ] **Step 2: Update `RealtyGroupMembershipService.leave / removeMember`**

Where the existing code calls the single reassignment query, replace with:

```java
        // E §10: case-3 reassigns seller_id; case-1 legacy keeps reassigning listing_agent_id.
        auctionRepo.reassignSellerToLeaderForCase3(departingUserId, group.getId(), group.getLeaderId());
        auctionRepo.reassignListingAgentToLeaderForCase1(departingUserId, group.getId(), group.getLeaderId());
```

- [ ] **Step 3: Tests**

Add to `RealtyGroupMembershipServiceTest`:

- `leave_case3Listings_seller_idReassignedToLeader_listingAgent_idStable`
- `leave_case1LegacyListings_listingAgent_idReassignedToLeader_seller_idUnchanged`
- `leave_individualListings_unaffected`

- [ ] **Step 4: Run tests**

Run: `cd backend && ./mvnw test -Dtest=RealtyGroupMembershipServiceTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/AuctionRepository.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/service/RealtyGroupMembershipService.java \
        backend/src/test/java/com/slparcelauctions/backend/realty/service/RealtyGroupMembershipServiceTest.java
git commit -m "feat(realty-slgroup): split member-departure reassignment (case-3 seller_id; case-1 listing_agent_id)"
git push
```

---

## Task 23: Dissolution gate extension

Implements spec §12.

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/service/RealtyGroupService.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/exception/RealtyExceptionHandler.java` (already updated in Task 13 if it included `SlGroupRegisteredBlocksDissolveException`)
- Test: extend `RealtyGroupServiceDissolveTest`

- [ ] **Step 1: Extend `dissolve()`**

In `RealtyGroupService.dissolve(...)`, after the existing C+D gates (active listings, wallet balance, in-flight escrows), add:

```java
        long slGroupCount = slGroupRepo.countByRealtyGroupId(group.getId());
        if (slGroupCount > 0) {
            throw new com.slparcelauctions.backend.realty.exception
                    .SlGroupRegisteredBlocksDissolveException(group.getPublicId(), slGroupCount);
        }
```

Inject `RealtyGroupSlGroupRepository slGroupRepo` via the field list.

- [ ] **Step 2: Tests**

Add to `RealtyGroupServiceDissolveTest`:

- `dissolve_blocksWhenVerifiedSlGroupExists`
- `dissolve_blocksWhenPendingSlGroupExists`
- `dissolve_succeedsAfterAllSlGroupsUnregistered`

- [ ] **Step 3: Run tests**

Run: `cd backend && ./mvnw test -Dtest=RealtyGroupServiceDissolveTest`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/service/RealtyGroupService.java \
        backend/src/test/java/com/slparcelauctions/backend/realty/service/RealtyGroupServiceDissolveTest.java
git commit -m "feat(realty-slgroup): extend dissolution gate with SL-group registrations"
git push
```

---

## Task 24: `CancellationOffenseKind.BROKER_CANCEL` + `CancellationService.brokerCancel`

Implements spec §11.

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/CancellationOffenseKind.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/CancellationLog.java` (add actor + group fields)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/CancellationLogRepository.java` (exclude BROKER_CANCEL from prior-offense count)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/CancellationService.java` (add `brokerCancel`)
- Test: `backend/src/test/java/com/slparcelauctions/backend/auction/CancellationServiceBrokerCancelTest.java`

- [ ] **Step 1: Add enum value**

```java
public enum CancellationOffenseKind {
    WARNING, PENALTY, PENALTY_AND_30D, PERMANENT_BAN,
    /** E §11.4 -- broker cancellation; not counted in countPriorOffensesWithBids. */
    BROKER_CANCEL
}
```

If `cancellation_logs.kind` has a CHECK constraint, add the new value via a follow-up SQL block in `V27` (or a small `V28`). Re-run `BackendApplicationTests.contextLoads` to confirm.

- [ ] **Step 2: Extend `CancellationLog`**

Add fields:

```java
    /** E §11.3 — broker user when kind = BROKER_CANCEL; null for seller-initiated cancels. */
    @Column(name = "actor_user_id")
    private Long actorUserId;

    /** E §11.3 — realty group context for broker cancels; null for seller-initiated cancels. */
    @Column(name = "realty_group_id")
    private Long realtyGroupId;
```

Plus migration columns in `V27` (or V28):

```sql
ALTER TABLE cancellation_logs
  ADD COLUMN actor_user_id BIGINT NULL,
  ADD COLUMN realty_group_id BIGINT NULL;
```

- [ ] **Step 3: Update `countPriorOffensesWithBids`**

In `CancellationLogRepository.countPriorOffensesWithBids`, ensure the query filter excludes `BROKER_CANCEL`:

```java
    @Query("""
        SELECT COUNT(c) FROM CancellationLog c
         WHERE c.sellerId = :sellerId
           AND c.cancelledFromActive = true
           AND c.kind IN ('WARNING','PENALTY','PENALTY_AND_30D','PERMANENT_BAN')
        """)
    long countPriorOffensesWithBids(@Param("sellerId") Long sellerId);
```

(If the existing query was `c.kind <> 'BROKER_CANCEL'`-shaped, either form works; the inclusion list is more explicit and harder to drift later.)

- [ ] **Step 4: Add `brokerCancel(...)` to `CancellationService`**

```java
    @Transactional
    public Auction brokerCancel(Long brokerUserId, Long auctionId, String reason, String ipAddress) {
        Auction a = auctionRepo.findByIdForUpdate(auctionId)
                .orElseThrow(() -> new AuctionNotFoundException(auctionId));
        if (!CANCELLABLE.contains(a.getStatus())) {
            throw new InvalidAuctionStateException(a.getId(), a.getStatus(), "BROKER_CANCEL");
        }
        if (a.getStatus() == AuctionStatus.ACTIVE && a.getEndsAt() != null
                && OffsetDateTime.now(clock).isAfter(a.getEndsAt())) {
            throw new InvalidAuctionStateException(a.getId(), a.getStatus(), "BROKER_CANCEL_AFTER_END");
        }
        if (a.getRealtyGroupSlGroupId() == null) {
            throw new com.slparcelauctions.backend.auction.exception
                    .BrokerCancelNotApplicableException(a.getPublicId(),
                            "Broker-cancel only applies to case-3 (SL-group-owned) listings.");
        }

        Long groupId = a.getRealtyGroupId();
        authorizer.assertCan(brokerUserId, groupId,
                com.slparcelauctions.backend.realty.permission.RealtyGroupPermission.MANAGE_ALL_LISTINGS);

        AuctionStatus from = a.getStatus();
        boolean hadBids = a.getBidCount() != null && a.getBidCount() > 0;
        boolean activeWithBids = from == AuctionStatus.ACTIVE && hadBids;

        // Skip the seller penalty ladder. No seller-row lock, no kind/index switch.
        CancellationLog log = CancellationLog.builder()
                .auctionId(a.getId())
                .sellerId(a.getSeller().getId())
                .kind(CancellationOffenseKind.BROKER_CANCEL)
                .amount(null)
                .cancelledFromActive(from == AuctionStatus.ACTIVE)
                .reason(reason)
                .ipAddress(ipAddress)
                .actorUserId(brokerUserId)
                .realtyGroupId(groupId)
                .build();
        logRepo.save(log);

        a.setStatus(AuctionStatus.CANCELLED);
        Auction saved = auctionRepo.save(a);

        // Listing-fee refund: D's existing ListingFeeRefundProcessorTask routes by originating
        // ledger row, so for case-3 the refund credits back to the group wallet.
        if (Boolean.TRUE.equals(a.getListingFeePaid())) {
            refundRepo.save(ListingFeeRefund.builder()
                    .auction(saved)
                    .status(ListingFeeRefundStatus.PENDING)
                    .build());
        }

        // Bid broadcast: same broadcaster as seller cancel.
        broadcastPublisher.cancelled(new AuctionCancelledEnvelope(saved.getPublicId(), reason));
        notificationPublisher.brokerCancelled(saved.getListingAgent().getId(), saved.getId(),
                saved.getTitle(), brokerUserId, reason);

        // Hook for downstream monitors (BotMonitorLifecycleService etc.) — mirror the existing
        // cancel() path.
        monitorLifecycle.onAuctionCancelled(saved);

        return saved;
    }
```

Add `notificationPublisher.brokerCancelled(...)` to the `NotificationPublisher` interface + `NotificationPublisherImpl` (stub log-only line).

- [ ] **Step 5: Test**

`CancellationServiceBrokerCancelTest.java`:

- `brokerCancel_happyPath_noPenaltyLadderHit`
- `brokerCancel_authorizesViaManageAllListings`
- `brokerCancel_individualAuction_throws_BrokerCancelNotApplicable`
- `brokerCancel_case1Auction_throws_BrokerCancelNotApplicable`
- `brokerCancel_alreadyEnded_throws_InvalidState`
- `brokerCancel_refundCreatedForCase3WithListingFeePaid`

- [ ] **Step 6: Run tests**

Run: `cd backend && ./mvnw test -Dtest=CancellationServiceBrokerCancelTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/CancellationOffenseKind.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/CancellationLog.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/CancellationLogRepository.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/CancellationService.java \
        backend/src/main/java/com/slparcelauctions/backend/notification/ \
        backend/src/main/resources/db/migration/V27__realty_group_sl_groups.sql \
        backend/src/test/java/com/slparcelauctions/backend/auction/CancellationServiceBrokerCancelTest.java
git commit -m "feat(realty-slgroup): broker cancel service + BROKER_CANCEL ledger kind"
git push
```

---

## Task 25: Broker-cancel endpoint

Implements spec §5.2.

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionController.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/dto/BrokerCancelRequest.java`
- Test: extend `AuctionControllerTest`

- [ ] **Step 1: Request DTO**

```java
package com.slparcelauctions.backend.auction.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BrokerCancelRequest(
        @NotBlank @Size(max = 500) String reason
) {}
```

- [ ] **Step 2: Endpoint**

In `AuctionController.java`, add:

```java
    @PostMapping("/auctions/{publicId}/broker-cancel")
    @org.springframework.transaction.annotation.Transactional
    public SellerAuctionResponse brokerCancel(
            @PathVariable UUID publicId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody com.slparcelauctions.backend.auction.dto.BrokerCancelRequest req,
            HttpServletRequest httpRequest) {
        requireVerified(principal.userId());
        // Resolve the auction via a non-locking load — the service re-fetches under
        // a pessimistic write lock.
        Auction existing = auctionService.loadAnyByPublicId(publicId);   // new helper; not seller-scoped
        String ip = httpRequest.getRemoteAddr();
        Auction cancelled = cancellationService.brokerCancel(
                principal.userId(), existing.getId(), req.reason(), ip);
        return mapper.toSellerResponse(cancelled, null);
    }
```

Add `AuctionService.loadAnyByPublicId(UUID)` — a non-seller-scoped lookup. This breaks the C-era assumption that auctions are only loaded by their seller; the broker isn't the seller. The new method does NOT filter on `seller_id` and returns the auction or throws `AuctionNotFoundException`.

- [ ] **Step 3: Test**

Add to `AuctionControllerTest`:

- `brokerCancel_200OnHappyPath`
- `brokerCancel_403WithoutManageAllListings`
- `brokerCancel_422OnNonCase3`
- `brokerCancel_404OnUnknownAuction`

- [ ] **Step 4: Run tests**

Run: `cd backend && ./mvnw test -Dtest=AuctionControllerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/AuctionController.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/dto/BrokerCancelRequest.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/AuctionService.java \
        backend/src/test/java/com/slparcelauctions/backend/auction/AuctionControllerTest.java
git commit -m "feat(realty-slgroup): POST /auctions/{publicId}/broker-cancel endpoint"
git push
```

---

## Task 26: Frontend types, API client, hooks

Implements spec §6.

**Files:**
- Modify: `frontend/src/types/realty.ts`
- Create: `frontend/src/lib/api/realtySlGroup.ts`
- Create: `frontend/src/hooks/realty/useRealtyGroupSlGroups.ts`
- Create: `frontend/src/hooks/realty/useRegisterSlGroup.ts`
- Modify: `frontend/src/lib/api/realtyGroupListing.ts` (slParcelUuid param)
- Modify: `frontend/src/test/msw/handlers.ts` (handlers for new endpoints)

- [ ] **Step 1: Types**

Append to `frontend/src/types/realty.ts`:

```typescript
export type SlGroupVerifyMethod = "ABOUT_TEXT" | "FOUNDER_TERMINAL";

export interface SlGroupPending {
  verificationCode: string;
  verificationCodeExpiresAt: string;       // ISO-8601
  lastPolledAt: string | null;
  pollAttempts: number;
}

export interface RealtyGroupSlGroup {
  publicId: string;
  slGroupUuid: string;
  slGroupName: string | null;
  verified: boolean;
  verifiedAt: string | null;
  verifiedVia: SlGroupVerifyMethod | null;
  pending: SlGroupPending | null;
  founderAvatarUuid: string | null;
}

export interface RegisterSlGroupRequest {
  slGroupUuid: string;
}

// Extend RealtyGroupMember (existing type) to include agentCommissionRate
```

- [ ] **Step 2: API client**

`frontend/src/lib/api/realtySlGroup.ts`:

```typescript
import { api } from "./client";
import type {
  RealtyGroupSlGroup,
  RegisterSlGroupRequest,
} from "@/types/realty";

export const realtySlGroupApi = {
  list: (groupPublicId: string) =>
    api.get<RealtyGroupSlGroup[]>(
      `/api/v1/realty/groups/${groupPublicId}/sl-groups`,
    ),

  register: (groupPublicId: string, req: RegisterSlGroupRequest) =>
    api.post<RealtyGroupSlGroup, RegisterSlGroupRequest>(
      `/api/v1/realty/groups/${groupPublicId}/sl-groups`,
      req,
    ),

  unregister: (groupPublicId: string, slGroupPublicId: string) =>
    api.delete<void>(
      `/api/v1/realty/groups/${groupPublicId}/sl-groups/${slGroupPublicId}`,
    ),

  recheck: (groupPublicId: string, slGroupPublicId: string) =>
    api.post<RealtyGroupSlGroup, undefined>(
      `/api/v1/realty/groups/${groupPublicId}/sl-groups/${slGroupPublicId}/recheck`,
      undefined,
    ),
};
```

(Adapt `api.get/post/delete` signatures to whatever the existing client exposes.)

- [ ] **Step 3: Hooks**

`frontend/src/hooks/realty/useRealtyGroupSlGroups.ts`:

```typescript
import { useQuery } from "@tanstack/react-query";
import { realtySlGroupApi } from "@/lib/api/realtySlGroup";

export function useRealtyGroupSlGroups(groupPublicId: string) {
  return useQuery({
    queryKey: ["realty", "sl-groups", groupPublicId],
    queryFn: () => realtySlGroupApi.list(groupPublicId),
    enabled: !!groupPublicId,
  });
}
```

`frontend/src/hooks/realty/useRegisterSlGroup.ts`:

```typescript
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { realtySlGroupApi } from "@/lib/api/realtySlGroup";

export function useRegisterSlGroup(groupPublicId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (slGroupUuid: string) =>
      realtySlGroupApi.register(groupPublicId, { slGroupUuid }),
    onSuccess: () =>
      qc.invalidateQueries({ queryKey: ["realty", "sl-groups", groupPublicId] }),
  });
}
```

Add sibling hooks for `unregister` and `recheck`.

- [ ] **Step 4: Listing-eligible-groups API change**

In `frontend/src/lib/api/realtyGroupListing.ts`, change the existing `getListingEligibleGroups()` to take `slParcelUuid` and pass it as a query param.

- [ ] **Step 5: MSW handlers**

In `frontend/src/test/msw/handlers.ts`, add handler factories for:
- `GET /api/v1/realty/groups/:publicId/sl-groups`
- `POST /api/v1/realty/groups/:publicId/sl-groups`
- `DELETE /api/v1/realty/groups/:publicId/sl-groups/:slGroupPublicId`
- `POST /api/v1/realty/groups/:publicId/sl-groups/:slGroupPublicId/recheck`
- `POST /api/v1/auctions/:publicId/broker-cancel`
- Updated `GET /api/v1/realty/me/listing-eligible-groups?slParcelUuid=...`

Each handler returns the canonical happy-path response and named variants (e.g., `realtySlGroupHandlers.listEmpty`, `realtySlGroupHandlers.registerAlreadyRegistered`).

- [ ] **Step 6: Run frontend tests**

```bash
cd frontend && npm test -- --run --reporter=basic
```

Expected: existing tests still pass; no test added yet (those land in Tasks 27-28).

- [ ] **Step 7: Commit**

```bash
git add frontend/src/types/realty.ts \
        frontend/src/lib/api/realtySlGroup.ts \
        frontend/src/lib/api/realtyGroupListing.ts \
        frontend/src/hooks/realty/ \
        frontend/src/test/msw/handlers.ts
git commit -m "feat(realty-slgroup): frontend types + API client + hooks + MSW handlers"
git push
```

---

## Task 27: Frontend — SL group registration page + components

Implements spec §6.2.

**Files:**
- Create: `frontend/src/app/realty/groups/[publicId]/sl-groups/page.tsx`
- Create: `frontend/src/components/realty/slgroup/SlGroupsPage.tsx`
- Create: `frontend/src/components/realty/slgroup/RegisterSlGroupModal.tsx`
- Create: `frontend/src/components/realty/slgroup/SlGroupListRow.tsx`
- Create: `frontend/src/components/realty/slgroup/SlGroupVerificationInstructionsCard.tsx`
- Tests: `frontend/src/components/realty/slgroup/__tests__/SlGroupsPage.test.tsx` (and per-component)

- [ ] **Step 1: Next.js page**

```typescript
// frontend/src/app/realty/groups/[publicId]/sl-groups/page.tsx
import { SlGroupsPage } from "@/components/realty/slgroup/SlGroupsPage";

export const dynamic = "force-dynamic";

export default function Page({ params }: { params: { publicId: string } }) {
  return <SlGroupsPage groupPublicId={params.publicId} />;
}
```

- [ ] **Step 2: Page component**

`SlGroupsPage.tsx` renders:
- Page header + "Register new SL group" button (opens `RegisterSlGroupModal`)
- Table of registered SL groups using `SlGroupListRow` per row
- For pending rows: show verification code + countdown + recheck button
- For verified rows: show verified-via label + verified-at timestamp + unregister button

Use TanStack `useRealtyGroupSlGroups(publicId)` for the data + `useRegisterSlGroup` / unregister / recheck mutation hooks.

- [ ] **Step 3: Register modal**

`RegisterSlGroupModal.tsx` — form with single SL group UUID input. On submit, calls the register mutation. On success, shows the verification code + dual instructions (about-text and founder-terminal) inline using `SlGroupVerificationInstructionsCard`. Modal stays open until dismissed.

- [ ] **Step 4: Instructions card**

`SlGroupVerificationInstructionsCard.tsx` renders:

> **Option 1 — About text.** Set your SL group's About text to include `SLPA-XXXXXXXXXXXX`. Backend rechecks every 5 minutes. Click **Check now** to poll immediately.
>
> **Option 2 — Founder via terminal.** Hand `SLPA-XXXXXXXXXXXX` to your SL group's founder. They step onto any SLPA terminal, choose **SL Group Verify**, and type the code.

With a "Check now" button that calls `recheck`. Use plain text + lucide icons (no emojis per project convention).

- [ ] **Step 5: Tests**

Each component gets a Vitest + RTL test exercising the happy path + at least one error variant.

- [ ] **Step 6: Run tests + verify**

```bash
cd frontend && npm test -- --run && npm run verify
```

Expected: all pass.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/realty/groups/ \
        frontend/src/components/realty/slgroup/
git commit -m "feat(realty-slgroup): SL group registration page + 4 sub-components"
git push
```

---

## Task 28: Frontend — listing wizard + member commission rate + case-3 display + broker cancel

Implements spec §6.1, §6.3, §6.4, §6.5.

**Files:**
- Modify: `frontend/src/components/listing/ListingWizardForm.tsx`
- Modify: `frontend/src/components/listing/AgentFeePreview.tsx` (or create `AgentCommissionPreview.tsx`)
- Modify: `frontend/src/components/realty/group/MemberEditDrawer.tsx`
- Modify: `frontend/src/components/realty/group/InviteMemberModal.tsx`
- Modify: `frontend/src/components/auction/AuctionDetailHeader.tsx`
- Create: `frontend/src/components/listing/BrokerCancelButton.tsx`
- Create: `frontend/src/components/listing/BrokerCancelModal.tsx`
- Tests: component-level Vitest + RTL

- [ ] **Step 1: Listing wizard becomes parcel-driven**

In `ListingWizardForm.tsx`, after the parcel lookup result lands:

- Call `getListingEligibleGroups({ slParcelUuid })` from Task 26.
- If the parcel is agent-owned by the user (`parcel.ownerType === "agent"` and matches user's SL avatar UUID): hide the picker; the listing creates as Individual.
- If the parcel is SL-group-owned and the eligible-groups response has 1+ items: show a `<RadioGroup>` with one option per group (no Individual option).
- Otherwise: render an inline message ("This parcel cannot be listed: it is owned by an SL group not registered to one of your realty groups, or by another avatar.") and disable Submit.

Fee-math preview switches to:

> If this lists at L$ {{startingBid}}, you'll receive **L$ {agentSlice}** (your {commissionRate * 100}% commission of earnings after platform commission). **{Realty Group Name}** earns **L$ {groupSlice}**.

Math:

```javascript
const platformCommission = Math.floor(startingBid * 0.05);
const earnings = startingBid - platformCommission;
const agentSlice = Math.floor(earnings * agentCommissionRate);
const groupSlice = earnings - agentSlice;
```

- [ ] **Step 2: Member commission rate input**

In `MemberEditDrawer.tsx` and `InviteMemberModal.tsx`, add a percentage input below the permissions checklist. Field name `agentCommissionRate`. Value persists to the API request body. Leader-edit-only on member-edit (read-only when caller is not the leader).

- [ ] **Step 3: Case-3 display**

In `AuctionDetailHeader.tsx`, when `auction.realtyGroup && auction.realtyGroupSlGroupId`:

```tsx
<h1>Sold by {auction.realtyGroup.name}</h1>
<p className="text-sm text-muted">
  Listed by <Link href={`/users/${auction.listingAgent.publicId}`}>
    {auction.listingAgent.displayName}
  </Link> of {auction.realtyGroup.name}
</p>
```

For individual or legacy case-1: existing display unchanged.

- [ ] **Step 4: Broker cancel button + modal**

`BrokerCancelButton.tsx` — renders in the listing detail actions strip when:
- Current user holds `MANAGE_ALL_LISTINGS` for the auction's realty group (frontend permission check using existing membership data; backend is source of truth).
- Current user is NOT the auction's seller.
- Auction is in a `CANCELLABLE` status (DRAFT through ACTIVE; pre-ENDED).

`BrokerCancelModal.tsx` — confirmation modal with:
- Auction title
- Active-bid state (bid count + current highest bid if any)
- Listing-fee refund destination ("Group wallet")
- Required reason textarea
- Confirm button disabled until reason is non-empty

On confirm: POST `/auctions/{publicId}/broker-cancel`, invalidate listing queries, close modal.

- [ ] **Step 5: Tests**

Tests for each new component + extension tests for the wizard:

- `ListingWizardForm: personalLand_showsIndividualOnly`
- `ListingWizardForm: slGroupOwned_showsGroupOptions`
- `ListingWizardForm: case3FeePreview`
- `MemberEditDrawer: leaderCanEditCommissionRate`
- `MemberEditDrawer: non-leaderSeesReadOnly`
- `AuctionDetailHeader: case3ShowsRealtyGroup`
- `BrokerCancelButton: showsForBrokerOnly`
- `BrokerCancelModal: disabledUntilReasonProvided`

- [ ] **Step 6: Run tests + verify**

```bash
cd frontend && npm test -- --run && npm run verify
```

Expected: all pass; the four `verify` guards all green.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/components/listing/ \
        frontend/src/components/realty/group/ \
        frontend/src/components/auction/AuctionDetailHeader.tsx
git commit -m "feat(realty-slgroup): listing wizard parcel-driven + commission rate UI + case-3 display + broker cancel"
git push
```

---

## Task 29: LSL — `slpa-terminal` SL Group Verify menu

Implements spec §7.3, §13.3.

**Files:**
- Modify: `lsl-scripts/slpa-terminal/<main script>.lsl` (the existing terminal LSL — locate via `lsl-scripts/slpa-terminal/README.md`)
- Modify: `lsl-scripts/slpa-terminal/README.md`

- [ ] **Step 1: Audit the existing terminal script**

Read `lsl-scripts/slpa-terminal/README.md` and the main script file. Note:
- How menus are presented (`llDialog`, `llListen`, listener handles)
- How `llTextBox` prompts are dispatched
- How HTTP POSTs are issued (`llHTTPRequest` with shared-secret HMAC)
- Existing menu items and the dispatch pattern

- [ ] **Step 2: Add "SL Group Verify" menu item**

Extend the main menu list to include `"SL Group Verify"` alongside existing items. Wire the dispatch:

```lsl
else if (message == "SL Group Verify") {
    state sl_group_verify;
}
```

State `sl_group_verify` (or inline if the script avoids states):

```lsl
state sl_group_verify
{
    state_entry()
    {
        llTextBox(touched_avatar, "Type your SL group verification code (e.g., SLPA-ABCDEFGHJKMN):", channel);
    }

    listen(integer chan, string name, key id, string message)
    {
        if (id != touched_avatar) return;
        string code = llStringTrim(message, STRING_TRIM);
        string body = "verificationCode=" + llEscapeURL(code)
                    + "&founderAvatarUuid=" + llEscapeURL((string)touched_avatar);
        llHTTPRequest(BACKEND_BASE_URL + "/api/v1/sl/sl-group/verify",
            [HTTP_METHOD, "POST",
             HTTP_MIMETYPE, "application/x-www-form-urlencoded",
             HTTP_CUSTOM_HEADER, "X-SLPA-Signature", sign(body),
             HTTP_CUSTOM_HEADER, "X-SecondLife-Owner-Key", (string)llGetOwner()],
            body);
        // ... wait for http_response, owner-say OK or error message
        state default;
    }
}
```

(Adapt to the actual auth pattern + JSON-vs-form-encoded body shape used by other `slpa-terminal` HTTP calls. If the script uses JSON, send a JSON body instead.)

- [ ] **Step 3: README update**

In `lsl-scripts/slpa-terminal/README.md`, add a new "SL Group Verify" section documenting:
- Menu path: Main menu → "SL Group Verify"
- Input: 12-character `SLPA-` verification code from the SL Parcels web UI
- Effect: registers the toucher as the SL group founder; backend cross-checks via World API
- Errors: invalid/expired code → terminal owner-says; founder mismatch → terminal owner-says

Update the "Deployment" section to mention the new menu item (no new permissions or env vars needed).

- [ ] **Step 4: Validate the script syntactically**

LSL doesn't have a CI test harness; the implementer pastes the script into the SL viewer's script editor to confirm it compiles. Note the validation step in the commit message.

- [ ] **Step 5: Commit**

```bash
git add lsl-scripts/slpa-terminal/
git commit -m "feat(realty-slgroup): slpa-terminal SL Group Verify menu + README"
git push
```

---

## Task 30: Final sweep — README, DEFERRED_WORK, Postman, PR

Implements spec §13.3, §14 + writing-plans skill's "self-review note" checklist.

**Files:**
- Modify: `README.md`
- Modify: `docs/implementation/DEFERRED_WORK.md`
- Update: SLPA Postman collection (new endpoints with variable-chaining tests)
- Open: PR from `feat/realty-groups-sl-group-listing` into `dev`

- [ ] **Step 1: Backend test sweep**

```bash
cd backend && ./mvnw test
```

Expected: all green. Address any breakages from the cumulative changes (esp. the reassignment split, the broker-cancel branch, the listing-eligible-groups param change). Pre-existing flakes from D (`ReconciliationServiceTest.staleBalanceRecordsErrorStatus`) carry over — document them in DEFERRED_WORK if still failing.

- [ ] **Step 2: Frontend test + verify sweep**

```bash
cd frontend && npm test -- --run && npm run verify
```

Expected: all green; all four verify guards pass.

- [ ] **Step 3: README update**

Append to `README.md` in the realty-groups section:

```markdown
### Sub-project E — SL-group-owned listings

E builds the case-3 listing flow: realty groups sell land deeded to an SL group, with
per-listing commission rates for the listing agent. New surfaces:

- SL group registration page (`/realty/groups/{slug}/sl-groups`) — leader registers an
  SL group via about-text verification (backend polls `world.secondlife.com/group/{uuid}`
  every 5 minutes for a verification code) or founder-via-terminal (founder steps onto
  any SLPA terminal, types the code, backend cross-checks the founder UUID via the
  World API).
- Listing wizard becomes parcel-driven: personal land lists as Individual; SL-group-
  owned land (with a verified registration) lists under the realty group, no other path.
- New `RealtyGroupPermission.REGISTER_SL_GROUP` (leader-implicit; delegable).
  `MANAGE_OWN_LISTING` removed; `MANAGE_ALL_LISTINGS` wired for new `POST
  /auctions/{publicId}/broker-cancel` endpoint that skips the seller penalty ladder.
- Per-member `agent_commission_rate` set at invite time by the leader; snapshotted onto
  each case-3 auction at create-time. Listing agent earns `floor(earnings * rate)`;
  realty group wallet earns the residual.
- C-era case-1 listing creation is now unreachable; cleanup tracked in
  [Realty Groups: G (#246)](https://github.com/TheCodeLlama/slparcelauctions/issues/246).
```

- [ ] **Step 4: DEFERRED_WORK update**

In `docs/implementation/DEFERRED_WORK.md`:

- Remove items now resolved by E (e.g., "MANAGE_OWN_LISTING wiring" from C's deferred section, since the perm is gone).
- Add an E section:

```markdown
### Sub-project E (realty groups: SL-group-owned listings) — deferred

- Admin re-verify of an SL group registration (after drift) — moved to F (#240).
- Admin force-unregister of an SL group (compliance) — moved to F (#240).
- Re-verify cadence for verified SL groups — moved to F (#240).
- Bulk per-member commission-rate edit — UX polish; later.
- Per-member commission-rate analytics dashboard — F or later.
- C-era code/column cleanup (AgentFeeDistributor removal, agent_fee_rate/split column drops)
  — moved to G (#246).
- Postman: verify new endpoints have variable-chaining test scripts in the SLPA collection.
```

- [ ] **Step 5: Postman update**

In the SLPA Postman collection (workspace `SLPA`, collection id `8070328-cf3e1190-cb71-4af3-8e2e-70c82dfee288`), add requests for:

- `POST /realty/groups/{publicId}/sl-groups` (with test script chaining `slGroupPublicId` and `verificationCode` for downstream calls)
- `GET /realty/groups/{publicId}/sl-groups`
- `DELETE /realty/groups/{publicId}/sl-groups/{slGroupPublicId}`
- `POST /realty/groups/{publicId}/sl-groups/{slGroupPublicId}/recheck`
- `POST /sl/sl-group/verify` (LSL flow simulation — pre-request script sets HMAC headers)
- `POST /auctions/{publicId}/broker-cancel`
- Updated `GET /realty/me/listing-eligible-groups?slParcelUuid={{slParcelUuid}}`

Test scripts should set environment variables for the next request in the chain.

- [ ] **Step 6: Spec coverage sweep**

Re-read `docs/superpowers/specs/2026-05-12-realty-groups-sl-group-listing-design.md` end-to-end. For each numbered section, verify it has been implemented by a task above. Any gap → write a follow-up task on this branch, do not open the PR.

- [ ] **Step 7: Final commit + push**

```bash
git add README.md docs/implementation/DEFERRED_WORK.md
git commit -m "docs(realty-slgroup): README + DEFERRED_WORK sweep for sub-project E"
git push
```

- [ ] **Step 8: Open PR into dev**

```bash
gh pr create --repo TheCodeLlama/slparcelauctions \
  --base dev \
  --head feat/realty-groups-sl-group-listing \
  --title "feat(realty-groups): sub-project E — SL-group-owned listings" \
  --body "$(cat <<'EOF'
## Summary

Implements [sub-project E (#239)](https://github.com/TheCodeLlama/slparcelauctions/issues/239) of the realty groups feature. Realty groups now sell SL-group-deeded land via verified SL-group registrations; listing agents earn per-listing commissions; brokers with MANAGE_ALL_LISTINGS can cancel any of the group's listings without penalizing the original lister.

- New `realty_group_sl_groups` table + about-text + founder-via-terminal verification flows.
- Per-member `agent_commission_rate` set at invite, snapshotted onto each case-3 auction.
- Listing wizard becomes parcel-driven; case-1 (personal land under group) closed off.
- `MANAGE_OWN_LISTING` removed; `MANAGE_ALL_LISTINGS` + `REGISTER_SL_GROUP` wired.
- Broker-cancel endpoint skips the seller penalty ladder.
- Dissolution gate extended: SL-group registrations block dissolve.
- C-era cleanup tracked in [Realty Groups: G (#246)](https://github.com/TheCodeLlama/slparcelauctions/issues/246).

## Test plan

- [ ] Register an SL group via about-text; About text matches → verified within poll cycle.
- [ ] Register an SL group via founder-terminal; founder types code → verified.
- [ ] List a parcel owned by the registered SL group → case-3 listing created; agent slice + group slice routed correctly on SOLD close.
- [ ] List a personal parcel under a realty group → 422 (case-1 closed off).
- [ ] Broker cancels a case-3 listing → no penalty ladder hit on seller; listing fee refunded to group wallet.
- [ ] Member departs → seller_id reassigned to leader; listing_agent_id stable; commission still credits original member at SOLD close.
- [ ] Dissolve with registered SL group → 409; dissolve succeeds after unregister.
EOF
)"
```

Note the URL output by `gh pr create`. Wait for the user's explicit authorization before merging the dev→main PR (the user's standing authorization is for dev only).

---

## End of Part 3 / End of Plan
