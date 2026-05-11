# Realty Groups Listing Integration (Sub-project C) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a member of a realty group list an auction under that group's name, snapshot the fee terms at create and the fee amount at SOLD close, surface a "Listed by X of Group" badge, block group members from bidding on their own group's listings, reassign `listing_agent_id` to the leader when an agent leaves, and tighten the dissolution gate to "no active listings."

**Architecture:** Cross-cutting band across four existing domains (auction, escrow, realty, frontend wizard). Introduces no new entities — adds one column to `auctions`, three enum values to `RealtyGroupPermission`, two new services (`RealtyGroupListingService`, `BidEligibilityService`), two new domain exceptions, and a single Flyway migration. The spec is at `docs/superpowers/specs/2026-05-11-realty-groups-listing-integration-design.md` (committed at `987424de`).

**Tech Stack:** Java 26 / Spring Boot 4 / JPA / PostgreSQL / Flyway / Lombok / Vitest / TanStack Query / Next.js 16 / React 19.

---

## File Structure

### Backend — new files

| Path | Responsibility |
|---|---|
| `backend/src/main/resources/db/migration/V25__realty_groups_listing.sql` | One-line migration adding `auctions.agent_fee_split DECIMAL(5,4) NULL`. |
| `backend/src/main/java/com/slparcelauctions/backend/auction/BidEligibilityService.java` | Consolidates seller-COI + group-member-COI rules behind `assertCanBid(auction, bidder)`. |
| `backend/src/main/java/com/slparcelauctions/backend/auction/exception/GroupMemberCannotBidException.java` | 403 thrown by `BidEligibilityService` when bidder is a current group member of the auction's `realty_group_id`. |
| `backend/src/main/java/com/slparcelauctions/backend/realty/exception/ActiveListingsBlockDissolveException.java` | 409 thrown by `RealtyGroupService.dissolveGroup` when active listings exist (leader path only). |
| `backend/src/main/java/com/slparcelauctions/backend/realty/listing/RealtyGroupListingService.java` | Wraps `AuctionService.create` with permission check + rate-snapshot before delegating. |
| `backend/src/main/java/com/slparcelauctions/backend/realty/listing/RealtyGroupListingController.java` | `GET /api/v1/realty/me/listing-eligible-groups` + `GET /api/v1/realty/groups/{publicId}/listings`. |
| `backend/src/main/java/com/slparcelauctions/backend/realty/listing/ListingEligibleGroupDto.java` | Read-only DTO for the picker. |

### Backend — modified files

| Path | Change |
|---|---|
| `backend/src/main/java/com/slparcelauctions/backend/auction/Auction.java` | Add `agentFeeSplit: BigDecimal` field mapped to `agent_fee_split`. |
| `backend/src/main/java/com/slparcelauctions/backend/realty/permission/RealtyGroupPermission.java` | Append `CREATE_LISTING`, `MANAGE_OWN_LISTING`, `MANAGE_ALL_LISTINGS`. |
| `backend/src/main/java/com/slparcelauctions/backend/auction/dto/AuctionCreateRequest.java` | Add optional `listAsGroupPublicId: UUID` field. |
| `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionController.java` | Branch in `create` on `listAsGroupPublicId`. |
| `backend/src/main/java/com/slparcelauctions/backend/auction/BidService.java` | Replace inline seller check with `bidEligibilityService.assertCanBid(...)`. |
| `backend/src/main/java/com/slparcelauctions/backend/auction/ProxyBidService.java` | Same replacement. |
| `backend/src/main/java/com/slparcelauctions/backend/auction/exception/AuctionExceptionHandler.java` | Map `GroupMemberCannotBidException` → 403. |
| `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionRepository.java` | Add `reassignListingAgentForGroup(...)` + `existsActiveListingsByGroupId(...)`. |
| `backend/src/main/java/com/slparcelauctions/backend/auction/auctionend/AuctionEndTask.java` | Compute + set `agent_fee_amt` on SOLD outcome inside the existing transaction. |
| `backend/src/main/java/com/slparcelauctions/backend/realty/service/RealtyGroupMembershipService.java` | After `members.deleteByGroupIdAndUserId` in both `leave` and `removeMember`, call the auction-repo reassignment hook. |
| `backend/src/main/java/com/slparcelauctions/backend/realty/service/RealtyGroupService.java` | Add active-listings guard to `dissolveGroup` (leader path only; admin path unchanged). |
| `backend/src/main/java/com/slparcelauctions/backend/realty/exception/RealtyExceptionHandler.java` | Map `ActiveListingsBlockDissolveException` → 409. |
| `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionDtoMapper.java` | Populate the new fields on `PublicAuctionResponse` + `SellerAuctionResponse`. |
| `backend/src/main/java/com/slparcelauctions/backend/auction/dto/PublicAuctionResponse.java` | Add `realtyGroup`, `listingAgent`, `agentFeeRate` optional fields. |
| `backend/src/main/java/com/slparcelauctions/backend/auction/dto/SellerAuctionResponse.java` | Same. |

### Frontend — new files

| Path | Responsibility |
|---|---|
| `frontend/src/lib/api/realtyGroupListing.ts` | Fetch helpers for `/realty/me/listing-eligible-groups` and `/realty/groups/{publicId}/listings`. |
| `frontend/src/hooks/realty/useListingEligibleGroups.ts` | TanStack Query hook. |
| `frontend/src/components/listing/ListAsGroupPicker.tsx` | RadioGroup picker for Individual / group options. |
| `frontend/src/components/listing/AgentFeePreview.tsx` | Inline preview "you'll receive ~L$X after fees". |
| `frontend/src/components/auction/GroupAttributionLine.tsx` | "Listed by X of Group" line on auction detail. |

### Frontend — modified files

| Path | Change |
|---|---|
| `frontend/src/types/realty.ts` | Add `ListingEligibleGroup` type. |
| `frontend/src/types/auction.ts` | Add `realtyGroup?`, `listingAgent?`, `agentFeeRate?` optional fields to auction types. |
| `frontend/src/components/listing/ListingWizardForm.tsx` | Wire picker + preview between parcel lookup and AuctionSettingsForm. Add `listAsGroupPublicId` to submit payload. |
| `frontend/src/components/auction/ListingCard.tsx` | Render `<GroupChip>` above seller line when group attribution exists. |
| `frontend/src/app/auction/[publicId]/page.tsx` (or its render component) | Render `<GroupAttributionLine>` near header. |
| `frontend/src/components/auction/BidForm.tsx` (or equivalent bid widget) | Disable with COI message when current user is a member of the auction's group. |

### Test files

Each backend service / handler gets a dedicated `*Test.java` next to it. Frontend components get `*.test.tsx` siblings. Names are stated under each task.

---

## Task Dependency Order

```
1 (migration) → 2 (Auction field) ─┐
                                   ├──→ 16 (AuctionEndTask) → tests
3 (enum) ─┐                        │
4 (exception class) ──┐            │
5 (handler mapping) ──┤            │
6 (BidEligibilityService) ────┐    │
7 (BidService wires it) ──────┤    │
8 (ProxyBidService wires it) ─┤    │
                              │    │
9 (Auction repo queries) ─────┤    │
10 (Dissolve exception) ──────┤    │
11 (Dissolve guard) ──────────┤    │
12 (Leave reassignment) ──────┤    │
13 (Remove reassignment) ─────┤    │
                              │    │
14 (CreateRequest field) ────┐│    │
15 (RealtyGroupListingService)│    │
16 (AuctionController routing)│    │
17 (DTO field additions) ─────┤    │
18 (Listing-eligible-groups) ─┤    │
19 (Group's listings) ────────┘    │
                                   │
20 (Frontend types) ────────────┐  │
21 (API client + hook) ─────────┤  │
22 (AgentFeePreview) ───────────┤  │
23 (ListAsGroupPicker) ─────────┤  │
24 (Wizard integration) ────────┤  │
25 (ListingCard chip) ──────────┤  │
26 (Detail page attribution) ───┤  │
27 (BidForm COI) ───────────────┘  │
                                   │
28 (Postman collection) ───────────┘
29 (README sweep + final QA)
```

Each task ends with a commit. Run the full test suite at the milestones marked `*FULL SUITE*` (tasks 8, 19, 27, 29).

---

## Task 1: V25 Flyway migration — add `agent_fee_split` column

**Files:**
- Create: `backend/src/main/resources/db/migration/V25__realty_groups_listing.sql`

- [ ] **Step 1: Create the migration file**

```sql
-- V25__realty_groups_listing.sql
-- Sub-project C of realty groups (issue #237).
-- Snapshot the group's agent_fee_split onto the auction at listing-create time so the
-- listing's fee terms are a frozen contract even if the group rebalances split later.
-- NULL for individual listings (auctions.realty_group_id IS NULL).

ALTER TABLE auctions
  ADD COLUMN agent_fee_split DECIMAL(5,4) NULL;
```

- [ ] **Step 2: Verify migration applies cleanly**

Run from `backend/`:

```bash
./mvnw -q -DskipTests compile
docker compose restart backend
docker compose logs backend --tail=80 | findstr /i "V25 Migration Schema"
```

Expected: a line like `Migrating schema "public" to version "25 - realty groups listing"` followed by `Successfully applied 1 migration`.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/resources/db/migration/V25__realty_groups_listing.sql
git commit -m "feat(realty-listing): V25 add auctions.agent_fee_split"
```

---

## Task 2: Auction entity — add `agentFeeSplit` field

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/Auction.java` (near the existing `agentFeeRate` / `agentFeeAmt` columns around line 236-239)

- [ ] **Step 1: Add the field**

Open `Auction.java`. Locate the existing `agentFeeRate` and `agentFeeAmt` declarations:

```java
@Column(name = "agent_fee_rate", precision = 5, scale = 4)
private BigDecimal agentFeeRate;

@Column(name = "agent_fee_amt")
private Long agentFeeAmt;
```

Insert directly after `agentFeeRate`:

```java
@Column(name = "agent_fee_split", precision = 5, scale = 4)
private BigDecimal agentFeeSplit;
```

- [ ] **Step 2: Compile**

```bash
cd backend
./mvnw -q -DskipTests compile
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/Auction.java
git commit -m "feat(realty-listing): map auctions.agent_fee_split to Auction.agentFeeSplit"
```

---

## Task 3: Extend `RealtyGroupPermission` enum

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/permission/RealtyGroupPermission.java`

- [ ] **Step 1: Append the three new values**

Replace the existing enum constants block (keeping the existing four):

```java
public enum RealtyGroupPermission {
    /** Issue + revoke invitations on this group. */
    INVITE_AGENTS,

    /** Remove non-leader members from this group. */
    REMOVE_AGENTS,

    /** Edit the group's profile (name, description, website, logo, cover). Rename remains
     *  gated by the 30-day cooldown for non-admins regardless of this flag. */
    EDIT_GROUP_PROFILE,

    /** Edit the group's {@code agent_fee_rate} and {@code agent_fee_split}. */
    CONFIGURE_FEES,

    /** Create an auction listing under this group. Snapshot of the group's fee terms is
     *  written onto the auction at create time; consumed at SOLD close. */
    CREATE_LISTING,

    /** Manage (edit/pause/cancel) listings the holder personally created on the group's
     *  behalf when they are not the seller. Defined here so the enum is whole; wired by
     *  sub-project E when case-2 (member-owned parcel) ships. No-op in sub-project C. */
    MANAGE_OWN_LISTING,

    /** Broker-level: manage (pause/cancel) any of the group's listings regardless of who
     *  created them. Defined here so the enum is whole; wired by sub-project E. No-op in
     *  sub-project C. */
    MANAGE_ALL_LISTINGS;
}
```

- [ ] **Step 2: Compile**

```bash
cd backend
./mvnw -q -DskipTests compile
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/permission/RealtyGroupPermission.java
git commit -m "feat(realty-listing): add CREATE_LISTING + MANAGE_*_LISTING permissions"
```

---

## Task 4: `GroupMemberCannotBidException` class

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/exception/GroupMemberCannotBidException.java`

- [ ] **Step 1: Create the exception class**

```java
package com.slparcelauctions.backend.auction.exception;

/**
 * Raised by {@code BidEligibilityService.assertCanBid} when the bidder is a current member
 * of the auction's {@code realty_group_id}. Mapped to {@code 403 GROUP_MEMBER_CANNOT_BID}
 * by {@code AuctionExceptionHandler}. Sibling to {@link SellerCannotBidException} — same
 * shape, distinct code so the frontend can render a group-aware message.
 */
public class GroupMemberCannotBidException extends RuntimeException {

    public GroupMemberCannotBidException() {
        super("Group members cannot bid on their group's listings.");
    }
}
```

- [ ] **Step 2: Compile**

```bash
cd backend
./mvnw -q -DskipTests compile
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/exception/GroupMemberCannotBidException.java
git commit -m "feat(realty-listing): add GroupMemberCannotBidException"
```

---

## Task 5: Handler mapping for `GroupMemberCannotBidException`

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/exception/AuctionExceptionHandler.java`

- [ ] **Step 1: Add the handler**

Locate the `@ExceptionHandler(SellerCannotBidException.class)` method around line 60. Insert directly after its closing brace (around line 68):

```java
@ExceptionHandler(GroupMemberCannotBidException.class)
public ProblemDetail handleGroupMemberCannotBid(
        GroupMemberCannotBidException e, HttpServletRequest req) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.FORBIDDEN, e.getMessage());
    pd.setTitle("Group Member Cannot Bid");
    pd.setInstance(URI.create(req.getRequestURI()));
    pd.setProperty("code", "GROUP_MEMBER_CANNOT_BID");
    return pd;
}
```

- [ ] **Step 2: Compile**

```bash
cd backend
./mvnw -q -DskipTests compile
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/exception/AuctionExceptionHandler.java
git commit -m "feat(realty-listing): map GroupMemberCannotBidException to 403"
```

---

## Task 6: `BidEligibilityService` (TDD)

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/BidEligibilityService.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auction/BidEligibilityServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.auction.exception.GroupMemberCannotBidException;
import com.slparcelauctions.backend.auction.exception.SellerCannotBidException;
import com.slparcelauctions.backend.realty.RealtyGroupMemberRepository;
import com.slparcelauctions.backend.user.User;

@ExtendWith(MockitoExtension.class)
class BidEligibilityServiceTest {

    @Mock RealtyGroupMemberRepository members;
    @InjectMocks BidEligibilityService service;

    private static User user(long id) {
        User u = new User();
        u.setId(id);
        return u;
    }

    private static Auction individualAuction(long sellerId) {
        Auction a = new Auction();
        a.setSeller(user(sellerId));
        a.setRealtyGroupId(null);
        return a;
    }

    private static Auction groupAuction(long sellerId, long groupId) {
        Auction a = new Auction();
        a.setSeller(user(sellerId));
        a.setRealtyGroupId(groupId);
        return a;
    }

    @Test
    void seller_cannot_bid_on_own_auction() {
        Auction a = individualAuction(1L);
        assertThatThrownBy(() -> service.assertCanBid(a, user(1L)))
                .isInstanceOf(SellerCannotBidException.class);
    }

    @Test
    void unrelated_user_can_bid_on_individual_auction() {
        Auction a = individualAuction(1L);
        assertThatCode(() -> service.assertCanBid(a, user(2L))).doesNotThrowAnyException();
    }

    @Test
    void group_member_cannot_bid_on_group_auction() {
        Auction a = groupAuction(1L, 99L);
        when(members.existsByGroupIdAndUserId(99L, 2L)).thenReturn(true);
        assertThatThrownBy(() -> service.assertCanBid(a, user(2L)))
                .isInstanceOf(GroupMemberCannotBidException.class);
    }

    @Test
    void non_member_can_bid_on_group_auction() {
        Auction a = groupAuction(1L, 99L);
        when(members.existsByGroupIdAndUserId(99L, 2L)).thenReturn(false);
        assertThatCode(() -> service.assertCanBid(a, user(2L))).doesNotThrowAnyException();
    }

    @Test
    void seller_check_short_circuits_before_db_lookup() {
        Auction a = groupAuction(1L, 99L);
        assertThatThrownBy(() -> service.assertCanBid(a, user(1L)))
                .isInstanceOf(SellerCannotBidException.class);
        org.mockito.Mockito.verify(members, org.mockito.Mockito.never())
                .existsByGroupIdAndUserId(anyLong(), anyLong());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend
./mvnw -q test -Dtest=BidEligibilityServiceTest
```

Expected: COMPILATION FAILURE (class `BidEligibilityService` not yet defined).

- [ ] **Step 3: Implement the service**

```java
package com.slparcelauctions.backend.auction;

import org.springframework.stereotype.Service;

import com.slparcelauctions.backend.auction.exception.GroupMemberCannotBidException;
import com.slparcelauctions.backend.auction.exception.SellerCannotBidException;
import com.slparcelauctions.backend.realty.RealtyGroupMemberRepository;
import com.slparcelauctions.backend.user.User;

import lombok.RequiredArgsConstructor;

/**
 * COI gate for the bid path. Two rules today:
 * <ol>
 *   <li>Bidder == auction.seller → {@link SellerCannotBidException} (sibling to the inline
 *       check that existed before C — moved here for consolidation).</li>
 *   <li>Bidder is a current member of the auction's realty group → {@link
 *       GroupMemberCannotBidException}.</li>
 * </ol>
 * Order matters: seller check is free (no DB hit) and the most common refusal in practice,
 * so it short-circuits before the member lookup.
 */
@Service
@RequiredArgsConstructor
public class BidEligibilityService {

    private final RealtyGroupMemberRepository memberRepo;

    public void assertCanBid(Auction auction, User bidder) {
        if (bidder.getId().equals(auction.getSeller().getId())) {
            throw new SellerCannotBidException();
        }
        Long groupId = auction.getRealtyGroupId();
        if (groupId != null
                && memberRepo.existsByGroupIdAndUserId(groupId, bidder.getId())) {
            throw new GroupMemberCannotBidException();
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd backend
./mvnw -q test -Dtest=BidEligibilityServiceTest
```

Expected: `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/BidEligibilityService.java backend/src/test/java/com/slparcelauctions/backend/auction/BidEligibilityServiceTest.java
git commit -m "feat(realty-listing): BidEligibilityService consolidates seller + group COI"
```

---

## Task 7: Wire `BidEligibilityService` into `BidService.placeBid`

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/BidService.java` (around line 148)

- [ ] **Step 1: Add the dependency injection**

Find the existing `@RequiredArgsConstructor` final-field block. Add (alphabetical order if the existing block is alphabetized; otherwise after `bidRepo`):

```java
private final BidEligibilityService bidEligibilityService;
```

- [ ] **Step 2: Replace the inline seller check**

Locate `BidService.java:148`:

```java
if (bidder.getId().equals(auction.getSeller().getId())) {
    throw new SellerCannotBidException();
}
```

Replace with:

```java
bidEligibilityService.assertCanBid(auction, bidder);
```

Remove the now-unused `SellerCannotBidException` import if Spring's IDE doesn't auto-clean.

- [ ] **Step 3: Run the bid service test suite to verify no regressions**

```bash
cd backend
./mvnw -q test -Dtest=BidServiceTest,BidServicePlaceBidTest -DfailIfNoTests=false
```

Expected: all existing tests pass. If any test was relying on the inline check, it should still pass because `BidEligibilityService` throws the same `SellerCannotBidException` on the seller-bid path.

- [ ] **Step 4: Add a regression test for the group-COI rule**

Append to `BidServiceTest.java` (or the equivalent test that exercises `placeBid` end-to-end):

```java
@Test
void placeBid_rejects_group_member_with_403_code() {
    // Setup: auction with realtyGroupId = G, bidder is a member of G
    // Expected: 403 with code=GROUP_MEMBER_CANNOT_BID
    // ... use the existing fixture pattern in BidServiceTest. The key wiring is:
    // when(memberRepo.existsByGroupIdAndUserId(G, bidderId)).thenReturn(true);
    // assertThatThrownBy(() -> bidService.placeBid(auctionId, bidderId, amount, ip))
    //     .isInstanceOf(GroupMemberCannotBidException.class);
}
```

> Note: The exact test signature depends on what `BidServiceTest` already mocks. If the existing test class uses a `@MockBean RealtyGroupMemberRepository` plus a builder-style `withAuction()` fixture, mirror it. If `BidServiceTest` does not exist or does not mock dependencies, this test belongs in `BidEligibilityServiceTest` (already covered in Task 6) and the integration-level test belongs in Task 8's full-suite check.

- [ ] **Step 5: Compile and run**

```bash
cd backend
./mvnw -q -DskipTests compile
./mvnw -q test -Dtest=BidServiceTest -DfailIfNoTests=false
```

Expected: BUILD SUCCESS + tests pass.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/BidService.java backend/src/test/java/com/slparcelauctions/backend/auction/BidServiceTest.java
git commit -m "feat(realty-listing): wire BidEligibilityService into BidService.placeBid"
```

---

## Task 8: Wire `BidEligibilityService` into `ProxyBidService` (and run full backend suite)

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/ProxyBidService.java`

- [ ] **Step 1: Find the existing seller-check in `ProxyBidService`**

```bash
cd backend
grep -n "SellerCannotBidException\|seller.*equals.*bidder\|bidder.*equals.*seller" src/main/java/com/slparcelauctions/backend/auction/ProxyBidService.java
```

Locate the inline seller check (mirrors the `BidService` pattern). It will be on the create-proxy path before any reservation work.

- [ ] **Step 2: Add the field and replace the check**

Inject:

```java
private final BidEligibilityService bidEligibilityService;
```

Replace the existing seller-check block with:

```java
bidEligibilityService.assertCanBid(auction, bidder);
```

- [ ] **Step 3: Run the proxy-bid tests**

```bash
cd backend
./mvnw -q test -Dtest=ProxyBidServiceTest -DfailIfNoTests=false
```

Expected: tests pass. The seller-bid case is preserved (eligibility service throws the same exception).

- [ ] **Step 4: Run the FULL backend suite (*FULL SUITE* checkpoint)**

```bash
cd backend
./mvnw test
```

Expected: all green. The bid path is heavily covered; this is the canary for the `placeBid` + `setProxy` refactors.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/ProxyBidService.java
git commit -m "feat(realty-listing): wire BidEligibilityService into ProxyBidService"
```

---

## Task 9: `AuctionRepository` queries — reassignment + active-listings check

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionRepository.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auction/AuctionRepositoryRealtyTest.java`

- [ ] **Step 1: Write the failing repository test**

```java
package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({}) // any test-support beans needed; @DataJpaTest brings in the JPA layer
class AuctionRepositoryRealtyTest {

    @Autowired AuctionRepository auctions;
    @Autowired RealtyGroupRepository groups;
    @Autowired UserRepository users;

    @Test
    void existsActiveListingsByGroupId_true_when_active() {
        User seller = users.save(buildVerifiedUser("seller-1"));
        RealtyGroup g = groups.save(buildGroup(seller.getId()));
        Auction a = auctions.save(buildActiveAuction(seller, g.getId()));

        assertThat(auctions.existsActiveListingsByGroupId(g.getId())).isTrue();
    }

    @Test
    void existsActiveListingsByGroupId_false_when_only_ended() {
        User seller = users.save(buildVerifiedUser("seller-2"));
        RealtyGroup g = groups.save(buildGroup(seller.getId()));
        Auction a = buildActiveAuction(seller, g.getId());
        a.setStatus(AuctionStatus.ENDED);
        auctions.save(a);

        assertThat(auctions.existsActiveListingsByGroupId(g.getId())).isFalse();
    }

    @Test
    void existsActiveListingsByGroupId_true_for_draft_and_pending_verification() {
        User seller = users.save(buildVerifiedUser("seller-3"));
        RealtyGroup g = groups.save(buildGroup(seller.getId()));
        Auction draft = buildActiveAuction(seller, g.getId());
        draft.setStatus(AuctionStatus.DRAFT);
        auctions.save(draft);

        assertThat(auctions.existsActiveListingsByGroupId(g.getId())).isTrue();
    }

    @Test
    void reassignListingAgentForGroup_updates_active_only() {
        User leader = users.save(buildVerifiedUser("leader-1"));
        User agent = users.save(buildVerifiedUser("agent-1"));
        RealtyGroup g = groups.save(buildGroup(leader.getId()));
        Auction active = buildActiveAuction(agent, g.getId());
        active.setListingAgentId(agent.getId());
        active = auctions.save(active);
        Auction ended = buildActiveAuction(agent, g.getId());
        ended.setListingAgentId(agent.getId());
        ended.setStatus(AuctionStatus.ENDED);
        ended = auctions.save(ended);

        int rows = auctions.reassignListingAgentForGroup(g.getId(), agent.getId(), leader.getId());

        assertThat(rows).isEqualTo(1);
        assertThat(auctions.findById(active.getId()).orElseThrow().getListingAgentId())
                .isEqualTo(leader.getId());
        assertThat(auctions.findById(ended.getId()).orElseThrow().getListingAgentId())
                .isEqualTo(agent.getId());
    }

    // --- builders ----------------------------------------------------------
    private static User buildVerifiedUser(String slUuidSuffix) {
        // Use the existing test-fixture pattern in the project. The two key fields are:
        // username, verified=true, slAvatarUuid (some non-null UUID).
        // The repo has a builder pattern from A+B tests — mirror it.
        // ... details fill in from RealtyGroupServiceDissolveTest fixture.
        throw new UnsupportedOperationException("fill from existing test-fixture helper");
    }

    private static RealtyGroup buildGroup(Long leaderId) {
        return RealtyGroup.builder().name("Test Group").slug("test-group").leaderId(leaderId).build();
    }

    private static Auction buildActiveAuction(User seller, Long realtyGroupId) {
        return Auction.builder()
                .seller(seller)
                .realtyGroupId(realtyGroupId)
                .status(AuctionStatus.ACTIVE)
                .title("Test")
                .startingBid(100L)
                .durationHours(24)
                .snipeProtect(false)
                .endsAt(OffsetDateTime.now().plusDays(1))
                .build();
    }
}
```

> Note on fixtures: the existing test suite already has User / RealtyGroup builders used by A+B's `RealtyGroupServiceDissolveTest`. Copy that builder pattern verbatim — do not invent a new helper. The two test-only builders above are placeholders; the implementer should pull the concrete fixture code from `backend/src/test/java/com/slparcelauctions/backend/realty/service/RealtyGroupServiceDissolveTest.java` (or whichever sibling test already constructs `RealtyGroup` and `User` via repo saves).

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend
./mvnw -q test -Dtest=AuctionRepositoryRealtyTest
```

Expected: COMPILATION FAILURE (`existsActiveListingsByGroupId` / `reassignListingAgentForGroup` not defined).

- [ ] **Step 3: Add the repository methods**

Open `AuctionRepository.java`. Add imports for `@Modifying`, `@Param`, `@Query`. Append to the interface:

```java
@Query("""
    SELECT (COUNT(a) > 0) FROM Auction a
     WHERE a.realtyGroupId = :groupId
       AND a.status IN (
            com.slparcelauctions.backend.auction.AuctionStatus.DRAFT,
            com.slparcelauctions.backend.auction.AuctionStatus.VERIFICATION_PENDING,
            com.slparcelauctions.backend.auction.AuctionStatus.ACTIVE)
    """)
boolean existsActiveListingsByGroupId(@Param("groupId") Long groupId);

@org.springframework.data.jpa.repository.Modifying
@Query("""
    UPDATE Auction a
       SET a.listingAgentId = :newAgentId
     WHERE a.realtyGroupId = :groupId
       AND a.listingAgentId = :oldAgentId
       AND a.status IN (
            com.slparcelauctions.backend.auction.AuctionStatus.DRAFT,
            com.slparcelauctions.backend.auction.AuctionStatus.VERIFICATION_PENDING,
            com.slparcelauctions.backend.auction.AuctionStatus.ACTIVE)
    """)
int reassignListingAgentForGroup(
        @Param("groupId") Long groupId,
        @Param("oldAgentId") Long oldAgentId,
        @Param("newAgentId") Long newAgentId);
```

> Spec naming check: The spec used `PENDING_VERIFICATION` in §9 and §10, but the actual enum value (verify by reading `AuctionStatus.java`) is `VERIFICATION_PENDING`. Use the real enum name. The other two (`DRAFT`, `ACTIVE`) match the spec.

- [ ] **Step 4: Re-run test**

```bash
cd backend
./mvnw -q test -Dtest=AuctionRepositoryRealtyTest
```

Expected: `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/AuctionRepository.java backend/src/test/java/com/slparcelauctions/backend/auction/AuctionRepositoryRealtyTest.java
git commit -m "feat(realty-listing): AuctionRepository queries for reassign + active-check"
```

---

## Task 10: `ActiveListingsBlockDissolveException` + handler mapping

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/exception/ActiveListingsBlockDissolveException.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/exception/RealtyExceptionHandler.java`

- [ ] **Step 1: Create the exception**

```java
package com.slparcelauctions.backend.realty.exception;

/**
 * Raised by {@code RealtyGroupService.dissolveGroup} when the leader attempts to dissolve a
 * group that has at least one auction in DRAFT / VERIFICATION_PENDING / ACTIVE status.
 * Admin force-dissolve bypasses this guard — see {@code dissolveGroupAsAdmin}.
 */
public class ActiveListingsBlockDissolveException extends RuntimeException {

    public ActiveListingsBlockDissolveException() {
        super("Cannot dissolve a realty group while it has active listings.");
    }
}
```

- [ ] **Step 2: Map it in `RealtyExceptionHandler`**

Insert directly after the `GroupDissolvedException` handler (around line 139):

```java
@ExceptionHandler(ActiveListingsBlockDissolveException.class)
public ProblemDetail handleActiveListingsBlock(
        ActiveListingsBlockDissolveException e, HttpServletRequest req) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    pd.setTitle("Group Has Active Listings");
    pd.setInstance(URI.create(req.getRequestURI()));
    pd.setProperty("code", "GROUP_HAS_ACTIVE_LISTINGS");
    return pd;
}
```

- [ ] **Step 3: Compile**

```bash
cd backend
./mvnw -q -DskipTests compile
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/exception/ActiveListingsBlockDissolveException.java backend/src/main/java/com/slparcelauctions/backend/realty/exception/RealtyExceptionHandler.java
git commit -m "feat(realty-listing): add ActiveListingsBlockDissolveException + 409 mapping"
```

---

## Task 11: Tighten `RealtyGroupService.dissolveGroup` with active-listings guard (TDD)

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/service/RealtyGroupService.java`
- Modify (or create): `backend/src/test/java/com/slparcelauctions/backend/realty/service/RealtyGroupServiceDissolveTest.java`

- [ ] **Step 1: Write the failing test**

Open the existing `RealtyGroupServiceDissolveTest` (from A+B). Add:

```java
@Test
void leader_dissolve_blocked_by_active_listing() {
    RealtyGroup g = givenLeaderOwnedGroup();
    givenActiveListing(g.getId(), g.getLeaderId());

    assertThatThrownBy(() -> service.dissolveGroup(g.getPublicId(), g.getLeaderId()))
            .isInstanceOf(ActiveListingsBlockDissolveException.class);
}

@Test
void admin_force_dissolve_bypasses_active_listings_guard() {
    RealtyGroup g = givenLeaderOwnedGroup();
    givenActiveListing(g.getId(), g.getLeaderId());

    RealtyGroup dissolved = service.dissolveGroupAsAdmin(g.getPublicId(), adminUserId);

    assertThat(dissolved.getDissolvedAt()).isNotNull();
}
```

The `givenActiveListing` helper inserts an `Auction` row with `realtyGroupId = groupId`, `listingAgentId = leaderId`, `status = ACTIVE`. Copy the builder from the auction-fixture pattern used in `AuctionRepositoryRealtyTest`.

> If `RealtyGroupServiceDissolveTest` does not yet exist (A+B may have named it differently), create it as a sibling to the file that already tests `dissolveGroup`. Find it via `grep -n "dissolveGroup\|dissolveGroupAsAdmin" backend/src/test/java/com/slparcelauctions/backend/realty/`.

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend
./mvnw -q test -Dtest=RealtyGroupServiceDissolveTest
```

Expected: the new tests fail because `dissolveGroup` does not yet check active listings.

- [ ] **Step 3: Add the guard**

Open `RealtyGroupService.java` line 274 (the existing `dissolveGroup`). Inject `AuctionRepository`:

```java
private final AuctionRepository auctions;  // add to the @RequiredArgsConstructor field block
```

Modify `dissolveGroup` to:

```java
public RealtyGroup dissolveGroup(UUID publicId, Long callerUserId) {
    RealtyGroup group = loadActive(publicId);
    authorizer.assertLeader(callerUserId, group.getId());
    if (auctions.existsActiveListingsByGroupId(group.getId())) {
        throw new ActiveListingsBlockDissolveException();
    }
    List<User> formerMembers = loadCurrentMembersAsUsers(group.getId());
    group.setDissolvedAt(OffsetDateTime.now());
    RealtyGroup saved = groups.save(group);
    notifications.realtyGroupDissolved(saved, formerMembers);
    log.info("Realty group dissolved by leader: publicId={} leaderId={}", publicId, callerUserId);
    return saved;
}
```

Leave `dissolveGroupAsAdmin` untouched — admin bypass per spec §10.2.

- [ ] **Step 4: Re-run test**

```bash
cd backend
./mvnw -q test -Dtest=RealtyGroupServiceDissolveTest
```

Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/service/RealtyGroupService.java backend/src/test/java/com/slparcelauctions/backend/realty/service/RealtyGroupServiceDissolveTest.java
git commit -m "feat(realty-listing): leader dissolve blocked when group has active listings"
```

---

## Task 12: `leave` reassigns listing_agent_id (TDD)

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/service/RealtyGroupMembershipService.java`
- Modify: `backend/src/test/java/com/slparcelauctions/backend/realty/service/RealtyGroupMembershipServiceLeaveTest.java`

- [ ] **Step 1: Write the failing test**

Append to `RealtyGroupMembershipServiceLeaveTest`:

```java
@Test
void leave_reassigns_listing_agent_to_leader_on_active_auctions() {
    User leader = givenLeader();
    User agent = givenMember(leader, group);
    Long auctionId = givenActiveAuctionListedByAgent(agent.getId(), group.getId());

    service.leave(group.getPublicId(), agent.getId());

    Long currentAgent = auctions.findById(auctionId).orElseThrow().getListingAgentId();
    assertThat(currentAgent).isEqualTo(leader.getId());
}

@Test
void leave_does_not_touch_ended_auctions() {
    User leader = givenLeader();
    User agent = givenMember(leader, group);
    Long endedId = givenEndedAuctionListedByAgent(agent.getId(), group.getId());

    service.leave(group.getPublicId(), agent.getId());

    Long currentAgent = auctions.findById(endedId).orElseThrow().getListingAgentId();
    assertThat(currentAgent).isEqualTo(agent.getId());  // unchanged
}
```

Add the two helper methods following the existing fixture pattern in this test file.

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd backend
./mvnw -q test -Dtest=RealtyGroupMembershipServiceLeaveTest
```

Expected: the two new tests fail (`leave` does not yet call the repo's reassignment).

- [ ] **Step 3: Wire the hook into `leave`**

Open `RealtyGroupMembershipService.java`. Inject `AuctionRepository` into the field block:

```java
private final AuctionRepository auctions;
```

In `leave(...)` (around line 61), after `members.deleteByGroupIdAndUserId(...)` and before the notification, add:

```java
auctions.reassignListingAgentForGroup(group.getId(), callerUserId, group.getLeaderId());
```

- [ ] **Step 4: Re-run test**

```bash
cd backend
./mvnw -q test -Dtest=RealtyGroupMembershipServiceLeaveTest
```

Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/service/RealtyGroupMembershipService.java backend/src/test/java/com/slparcelauctions/backend/realty/service/RealtyGroupMembershipServiceLeaveTest.java
git commit -m "feat(realty-listing): reassign listing_agent_id to leader on member leave"
```

---

## Task 13: `removeMember` reassigns listing_agent_id (TDD)

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/service/RealtyGroupMembershipService.java`
- Modify: `backend/src/test/java/com/slparcelauctions/backend/realty/service/RealtyGroupMembershipServiceRemoveTest.java`

- [ ] **Step 1: Write the failing test**

```java
@Test
void removeMember_reassigns_listing_agent_to_leader_on_active_auctions() {
    User leader = givenLeader();
    User agent = givenMember(leader, group);
    Long auctionId = givenActiveAuctionListedByAgent(agent.getId(), group.getId());

    service.removeMember(group.getPublicId(), memberPublicIdOf(agent), leader.getId());

    Long currentAgent = auctions.findById(auctionId).orElseThrow().getListingAgentId();
    assertThat(currentAgent).isEqualTo(leader.getId());
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend
./mvnw -q test -Dtest=RealtyGroupMembershipServiceRemoveTest
```

Expected: the new test fails.

- [ ] **Step 3: Wire the hook into `removeMember`**

In `removeMember(...)` (around line 89), after `members.deleteByGroupIdAndUserId(group.getId(), row.getUserId())` and before the notification:

```java
auctions.reassignListingAgentForGroup(group.getId(), row.getUserId(), group.getLeaderId());
```

(`AuctionRepository auctions` is already injected by Task 12.)

- [ ] **Step 4: Re-run test**

```bash
cd backend
./mvnw -q test -Dtest=RealtyGroupMembershipServiceRemoveTest
```

Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/service/RealtyGroupMembershipService.java backend/src/test/java/com/slparcelauctions/backend/realty/service/RealtyGroupMembershipServiceRemoveTest.java
git commit -m "feat(realty-listing): reassign listing_agent_id to leader on member remove"
```

---

## Task 14: Extend `AuctionCreateRequest` with `listAsGroupPublicId`

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/dto/AuctionCreateRequest.java`

- [ ] **Step 1: Add the field**

Replace the record signature with:

```java
public record AuctionCreateRequest(
        @NotNull UUID slParcelUuid,
        @NotBlank(message = "title must not be blank")
        @Size(max = 120, message = "title must be at most 120 characters")
        String title,
        @NotNull @Min(1) Long startingBid,
        Long reservePrice,
        Long buyNowPrice,
        @NotNull Integer durationHours,
        @NotNull Boolean snipeProtect,
        Integer snipeWindowMin,
        @Size(max = 5000) String sellerDesc,
        @Size(max = 10) Set<String> tags,
        // C: when present, route to RealtyGroupListingService instead of direct AuctionService.create.
        // When null, behavior is identical to today's individual-listing path.
        UUID listAsGroupPublicId) {
}
```

- [ ] **Step 2: Compile**

```bash
cd backend
./mvnw -q -DskipTests compile
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/dto/AuctionCreateRequest.java
git commit -m "feat(realty-listing): add optional listAsGroupPublicId to AuctionCreateRequest"
```

---

## Task 15: `RealtyGroupListingService` (TDD)

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/listing/RealtyGroupListingService.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/realty/listing/RealtyGroupListingServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.slparcelauctions.backend.realty.listing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionService;
import com.slparcelauctions.backend.auction.dto.AuctionCreateRequest;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.auth.RealtyGroupAuthorizer;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupPermissionDeniedException;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;

@ExtendWith(MockitoExtension.class)
class RealtyGroupListingServiceTest {

    @Mock RealtyGroupRepository groups;
    @Mock RealtyGroupAuthorizer authorizer;
    @Mock AuctionService auctionService;
    @InjectMocks RealtyGroupListingService service;

    private final UUID groupPublicId = UUID.randomUUID();

    private RealtyGroup activeGroup(Long id) {
        RealtyGroup g = RealtyGroup.builder()
                .name("Sunset Realty")
                .slug("sunset-realty")
                .leaderId(99L)
                .agentFeeRate(new BigDecimal("0.0200"))
                .agentFeeSplit(new BigDecimal("0.5000"))
                .build();
        // BaseEntity setters are private. The test uses reflection or the existing
        // fixture pattern from A+B tests. For brevity assume a test-only setter or
        // reflection helper is available.
        return g;
    }

    private AuctionCreateRequest req() {
        return new AuctionCreateRequest(
                UUID.randomUUID(), "Title", 1000L, null, null,
                24, false, null, null, null, groupPublicId);
    }

    @Test
    void create_group_listing_snapshots_rate_and_split() {
        Long callerId = 1L;
        RealtyGroup g = activeGroup(10L);
        when(groups.findByPublicIdAndDissolvedAtIsNull(groupPublicId)).thenReturn(Optional.of(g));
        when(auctionService.create(eq(callerId), any(AuctionCreateRequest.class), eq("1.2.3.4")))
                .thenAnswer(invocation -> {
                    Auction a = new Auction();
                    a.setRealtyGroupId(g.getId());
                    a.setListingAgentId(callerId);
                    a.setAgentFeeRate(g.getAgentFeeRate());
                    a.setAgentFeeSplit(g.getAgentFeeSplit());
                    return a;
                });

        Auction created = service.createGroupListing(callerId, req(), "1.2.3.4");

        verify(authorizer).assertCan(callerId, g.getId(), RealtyGroupPermission.CREATE_LISTING);
        assertThat(created.getRealtyGroupId()).isEqualTo(g.getId());
        assertThat(created.getListingAgentId()).isEqualTo(callerId);
        assertThat(created.getAgentFeeRate()).isEqualTo(new BigDecimal("0.0200"));
        assertThat(created.getAgentFeeSplit()).isEqualTo(new BigDecimal("0.5000"));
    }

    @Test
    void create_group_listing_404_when_group_absent() {
        when(groups.findByPublicIdAndDissolvedAtIsNull(groupPublicId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createGroupListing(1L, req(), "ip"))
                .isInstanceOf(RealtyGroupNotFoundException.class);
    }

    @Test
    void create_group_listing_403_when_no_permission() {
        Long callerId = 1L;
        RealtyGroup g = activeGroup(10L);
        when(groups.findByPublicIdAndDissolvedAtIsNull(groupPublicId)).thenReturn(Optional.of(g));
        doThrow(new RealtyGroupPermissionDeniedException(RealtyGroupPermission.CREATE_LISTING))
                .when(authorizer).assertCan(callerId, g.getId(), RealtyGroupPermission.CREATE_LISTING);

        assertThatThrownBy(() -> service.createGroupListing(callerId, req(), "ip"))
                .isInstanceOf(RealtyGroupPermissionDeniedException.class);
    }
}
```

> The `findByPublicIdAndDissolvedAtIsNull` method may already exist on `RealtyGroupRepository` from A+B. Verify with `grep -n "findByPublicIdAndDissolvedAtIsNull" backend/src/main/java/com/slparcelauctions/backend/realty/RealtyGroupRepository.java`. If absent, add it as a Spring-Data derived method in the same task.

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend
./mvnw -q test -Dtest=RealtyGroupListingServiceTest
```

Expected: COMPILATION FAILURE.

- [ ] **Step 3: Implement the service**

```java
package com.slparcelauctions.backend.realty.listing;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionService;
import com.slparcelauctions.backend.auction.dto.AuctionCreateRequest;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.auth.RealtyGroupAuthorizer;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Wraps {@link AuctionService#create} when the caller specifies {@code listAsGroupPublicId}
 * on the create request. Resolves the group (404 if missing or dissolved), asserts
 * {@code CREATE_LISTING}, then delegates to the underlying auction-create path.
 *
 * <p>The fee-rate snapshot ({@code agent_fee_rate} + {@code agent_fee_split}) is applied
 * on the auction row inside {@code AuctionService.create} via the request DTO's optional
 * group fields — see {@code AuctionController.create} branch logic for the routing.
 *
 * <p>{@code listing_agent_id} is set to the caller (case 1 only — agent == seller). E
 * will introduce separate handling when seller diverges from agent (case 2).
 */
@Service
@RequiredArgsConstructor
public class RealtyGroupListingService {

    private final RealtyGroupRepository groups;
    private final RealtyGroupAuthorizer authorizer;
    private final AuctionService auctionService;

    @Transactional
    public Auction createGroupListing(Long callerUserId, AuctionCreateRequest req, String ip) {
        UUID groupPublicId = req.listAsGroupPublicId();
        RealtyGroup group = groups.findByPublicIdAndDissolvedAtIsNull(groupPublicId)
                .orElseThrow(() -> new RealtyGroupNotFoundException(groupPublicId));
        authorizer.assertCan(callerUserId, group.getId(), RealtyGroupPermission.CREATE_LISTING);

        Auction created = auctionService.create(callerUserId, req, ip);
        created.setRealtyGroupId(group.getId());
        created.setListingAgentId(callerUserId);
        created.setAgentFeeRate(group.getAgentFeeRate());
        created.setAgentFeeSplit(group.getAgentFeeSplit());
        return created;
    }
}
```

> Note on the snapshot pattern: `auctionService.create` returns an attached entity inside the `@Transactional` boundary. Setting the four fields on the returned entity is sufficient — Hibernate's dirty-checking flushes them on commit. No explicit `auctions.save(created)` needed.

- [ ] **Step 4: Re-run test**

```bash
cd backend
./mvnw -q test -Dtest=RealtyGroupListingServiceTest
```

Expected: all three tests pass.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/listing/RealtyGroupListingService.java backend/src/test/java/com/slparcelauctions/backend/realty/listing/RealtyGroupListingServiceTest.java
git commit -m "feat(realty-listing): RealtyGroupListingService wraps auction create with snapshot"
```

---

## Task 16: Branch in `AuctionController.create` on `listAsGroupPublicId`

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionController.java`

- [ ] **Step 1: Inject `RealtyGroupListingService` + branch**

In the controller's field block (after `auctionService`):

```java
private final com.slparcelauctions.backend.realty.listing.RealtyGroupListingService realtyGroupListingService;
```

Modify the `create` method (around line 69):

```java
@PostMapping("/auctions")
@ResponseStatus(HttpStatus.CREATED)
@org.springframework.transaction.annotation.Transactional
public SellerAuctionResponse create(
        @AuthenticationPrincipal AuthPrincipal principal,
        @Valid @RequestBody AuctionCreateRequest req,
        HttpServletRequest httpRequest) {
    requireVerified(principal.userId());
    String ip = httpRequest.getRemoteAddr();
    Auction created;
    if (req.listAsGroupPublicId() != null) {
        created = realtyGroupListingService.createGroupListing(principal.userId(), req, ip);
    } else {
        created = auctionService.create(principal.userId(), req, ip);
    }
    return mapper.toSellerResponse(created, null);
}
```

- [ ] **Step 2: Compile**

```bash
cd backend
./mvnw -q -DskipTests compile
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/AuctionController.java
git commit -m "feat(realty-listing): route POST /auctions to group service when listAsGroupPublicId set"
```

---

## Task 17: `AuctionEndTask` agent-fee snapshot (TDD)

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/auctionend/AuctionEndTask.java`
- Create or extend: `backend/src/test/java/com/slparcelauctions/backend/auction/auctionend/AuctionEndTaskAgentFeeTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.slparcelauctions.backend.auction.auctionend;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
// ...other imports for AuctionEndTask's collaborators

@ExtendWith(MockitoExtension.class)
class AuctionEndTaskAgentFeeTest {

    // ... mock all AuctionEndTask collaborators (escrowService, monitorLifecycle,
    //     notificationPublisher, broadcastPublisher, clock, etc.). Pattern matches
    //     the existing AuctionEndTaskTest. The new fields are:
    @Mock RealtyGroupRepository realtyGroupRepo;
    @Mock AuctionRepository auctionRepo;
    // ...
    @InjectMocks AuctionEndTask task;

    private Auction soldGroupAuction(long groupId, BigDecimal rate, long finalBid) {
        Auction a = new Auction();
        a.setId(42L);
        a.setStatus(AuctionStatus.ACTIVE);
        a.setEndsAt(OffsetDateTime.now().minusSeconds(1));
        a.setBidCount(3);
        a.setCurrentBid(finalBid);
        a.setRealtyGroupId(groupId);
        a.setAgentFeeRate(rate);
        // seller, currentBidderId, etc. wired from the existing fixture pattern
        return a;
    }

    @Test
    void sold_with_2pct_rate_snapshots_agent_fee_amt() {
        Auction a = soldGroupAuction(10L, new BigDecimal("0.0200"), 1000L);
        Mockito.when(auctionRepo.findByIdForUpdate(42L)).thenReturn(Optional.of(a));
        Mockito.when(realtyGroupRepo.findById(10L)).thenReturn(Optional.of(
                RealtyGroup.builder().name("G").slug("g").leaderId(1L).build()));

        task.closeOne(42L);

        assertThat(a.getAgentFeeAmt()).isEqualTo(20L);
    }

    @Test
    void sold_with_dissolved_group_snapshots_zero() {
        Auction a = soldGroupAuction(10L, new BigDecimal("0.0200"), 1000L);
        RealtyGroup dissolved = RealtyGroup.builder().name("G").slug("g").leaderId(1L).build();
        dissolved.setDissolvedAt(OffsetDateTime.now().minusDays(1));
        Mockito.when(auctionRepo.findByIdForUpdate(42L)).thenReturn(Optional.of(a));
        Mockito.when(realtyGroupRepo.findById(10L)).thenReturn(Optional.of(dissolved));

        task.closeOne(42L);

        assertThat(a.getAgentFeeAmt()).isEqualTo(0L);
    }

    @Test
    void sold_individual_listing_leaves_agent_fee_null() {
        Auction a = soldGroupAuction(10L, null, 1000L);
        a.setRealtyGroupId(null);
        Mockito.when(auctionRepo.findByIdForUpdate(42L)).thenReturn(Optional.of(a));

        task.closeOne(42L);

        assertThat(a.getAgentFeeAmt()).isNull();
    }

    @Test
    void floor_rounding_drops_fraction() {
        // rate 0.0333, final L$1000 -> fee = 33 (not 34)
        Auction a = soldGroupAuction(10L, new BigDecimal("0.0333"), 1000L);
        Mockito.when(auctionRepo.findByIdForUpdate(42L)).thenReturn(Optional.of(a));
        Mockito.when(realtyGroupRepo.findById(10L)).thenReturn(Optional.of(
                RealtyGroup.builder().name("G").slug("g").leaderId(1L).build()));

        task.closeOne(42L);

        assertThat(a.getAgentFeeAmt()).isEqualTo(33L);
    }

    @Test
    void no_bids_leaves_agent_fee_null() {
        Auction a = soldGroupAuction(10L, new BigDecimal("0.0200"), 0L);
        a.setBidCount(0);
        Mockito.when(auctionRepo.findByIdForUpdate(42L)).thenReturn(Optional.of(a));

        task.closeOne(42L);

        assertThat(a.getAgentFeeAmt()).isNull();
    }
}
```

> Note: `AuctionEndTask.closeOne` has many collaborators. The existing test class (if it exists) already mocks them. Copy that scaffolding. The only *new* collaborator is `RealtyGroupRepository`.

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend
./mvnw -q test -Dtest=AuctionEndTaskAgentFeeTest
```

Expected: tests fail because `agent_fee_amt` is never written.

- [ ] **Step 3: Add the snapshot logic**

Open `AuctionEndTask.java` (around line 98 where `classifyOutcome` is called). Inject `RealtyGroupRepository`:

```java
private final com.slparcelauctions.backend.realty.RealtyGroupRepository realtyGroupRepo;
```

After `auction.setFinalBidAmount(auction.getCurrentBid())` in the SOLD branch (line 104):

```java
if (outcome == AuctionEndOutcome.SOLD && auction.getRealtyGroupId() != null) {
    auction.setAgentFeeAmt(computeAgentFeeAmt(auction));
}
```

Add the helper at the bottom of the class:

```java
/**
 * Computes the snapshot value of {@code agent_fee_amt} at SOLD close. Returns 0 if the
 * group has been dissolved, has a null/zero rate, or the rate is missing. Floor rounding
 * matches {@code EscrowCommissionCalculator}'s convention so the books stay balanced.
 */
private long computeAgentFeeAmt(Auction a) {
    com.slparcelauctions.backend.realty.RealtyGroup group =
            realtyGroupRepo.findById(a.getRealtyGroupId()).orElse(null);
    if (group == null || group.getDissolvedAt() != null) {
        return 0L;
    }
    java.math.BigDecimal rate = a.getAgentFeeRate();
    if (rate == null || rate.signum() == 0) {
        return 0L;
    }
    return java.math.BigDecimal.valueOf(a.getFinalBidAmount())
            .multiply(rate)
            .setScale(0, java.math.RoundingMode.FLOOR)
            .longValueExact();
}
```

- [ ] **Step 4: Re-run test**

```bash
cd backend
./mvnw -q test -Dtest=AuctionEndTaskAgentFeeTest
```

Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/auctionend/AuctionEndTask.java backend/src/test/java/com/slparcelauctions/backend/auction/auctionend/AuctionEndTaskAgentFeeTest.java
git commit -m "feat(realty-listing): snapshot agent_fee_amt on SOLD close"
```

---

## Task 18: Extend public + seller auction DTOs with group fields

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/dto/PublicAuctionResponse.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/dto/SellerAuctionResponse.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionDtoMapper.java`
- Create (if not present): a small `GroupAttributionDto` record under `auction/dto/`.

- [ ] **Step 1: Create `GroupAttributionDto`**

```java
// backend/src/main/java/com/slparcelauctions/backend/auction/dto/GroupAttributionDto.java
package com.slparcelauctions.backend.auction.dto;

import java.util.UUID;

public record GroupAttributionDto(
        UUID publicId,
        String name,
        String slug,
        String logoUrl,
        boolean dissolved) {
}
```

And the listing-agent sub-DTO:

```java
// backend/src/main/java/com/slparcelauctions/backend/auction/dto/ListingAgentDto.java
package com.slparcelauctions.backend.auction.dto;

import java.util.UUID;

public record ListingAgentDto(
        UUID publicId,
        String displayName,
        String avatarUrl) {
}
```

- [ ] **Step 2: Add the three new fields to both auction responses**

Open `PublicAuctionResponse.java` and `SellerAuctionResponse.java`. They are records; add at the end of the parameter list (before the closing `)`):

```java
        ...
        GroupAttributionDto realtyGroup,       // null for individual listings
        ListingAgentDto listingAgent,          // null for individual listings
        java.math.BigDecimal agentFeeRate      // null for individual listings
```

> The `PublicAuctionResponse` already has a nested `SellerSummary` record; the `ListingAgentDto` is its sibling shape. If the existing record builder pattern uses positional constructors, both call-sites (`AuctionDtoMapper.toPublicResponse`, `AuctionDtoMapper.toSellerResponse`) need updating.

- [ ] **Step 3: Update `AuctionDtoMapper.toPublicResponse` and `toSellerResponse`**

In `AuctionDtoMapper.java`, find both methods. For each, after the existing assignments, compute the three new values:

```java
private GroupAttributionDto resolveGroupAttribution(Auction a) {
    if (a.getRealtyGroupId() == null) return null;
    com.slparcelauctions.backend.realty.RealtyGroup g =
            realtyGroupRepo.findById(a.getRealtyGroupId()).orElse(null);
    if (g == null) return null;
    String logoUrl = g.getLogoObjectKey() == null
            ? null
            : "/api/v1/realty/groups/" + g.getPublicId() + "/logo";
    return new GroupAttributionDto(
            g.getPublicId(), g.getName(), g.getSlug(), logoUrl, g.getDissolvedAt() != null);
}

private ListingAgentDto resolveListingAgent(Auction a) {
    if (a.getListingAgentId() == null) return null;
    com.slparcelauctions.backend.user.User u =
            userRepo.findById(a.getListingAgentId()).orElse(null);
    if (u == null) return null;
    return new ListingAgentDto(
            u.getPublicId(), u.getDisplayName(),
            u.getAvatarObjectKey() == null ? null : "/api/v1/users/" + u.getPublicId() + "/avatar");
}
```

Inject `RealtyGroupRepository realtyGroupRepo;` into the mapper's `@RequiredArgsConstructor` field block. Pass `resolveGroupAttribution(a)`, `resolveListingAgent(a)`, `a.getAgentFeeRate()` into both response constructors.

> The exact URL shape for logo/avatar must match what A+B emits. Verify by reading `RealtyGroupImageController` for the logo endpoint and the user-avatar controller for the avatar endpoint. The values above are placeholders — read the real endpoints before committing.

- [ ] **Step 4: Compile**

```bash
cd backend
./mvnw -q -DskipTests compile
```

Expected: BUILD SUCCESS. If existing tests assert positional constructors of `PublicAuctionResponse`/`SellerAuctionResponse`, they will fail to compile — update each occurrence to pass `null` for the new fields. Use a grep to find all callers:

```bash
grep -rn "new PublicAuctionResponse\|new SellerAuctionResponse" backend/src
```

Add three trailing `null` arguments to each constructor call site that doesn't go through the mapper.

- [ ] **Step 5: Run the auction-DTO and mapper tests**

```bash
cd backend
./mvnw -q test -Dtest=AuctionDtoMapperTest -DfailIfNoTests=false
```

Expected: tests pass. If the mapper test exists and exercised `toPublicResponse`/`toSellerResponse`, add a new test case that asserts the three new fields are populated when the auction has a group and null when it doesn't.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/dto/GroupAttributionDto.java backend/src/main/java/com/slparcelauctions/backend/auction/dto/ListingAgentDto.java backend/src/main/java/com/slparcelauctions/backend/auction/dto/PublicAuctionResponse.java backend/src/main/java/com/slparcelauctions/backend/auction/dto/SellerAuctionResponse.java backend/src/main/java/com/slparcelauctions/backend/auction/AuctionDtoMapper.java
git commit -m "feat(realty-listing): auction DTOs carry group + agent attribution"
```

---

## Task 19: `ListingEligibleGroupDto` + `GET /api/v1/realty/me/listing-eligible-groups` (and run FULL backend suite)

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/listing/ListingEligibleGroupDto.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/listing/RealtyGroupListingController.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/realty/listing/RealtyGroupListingControllerTest.java`

- [ ] **Step 1: Create the DTO**

```java
package com.slparcelauctions.backend.realty.listing;

import java.math.BigDecimal;
import java.util.UUID;

public record ListingEligibleGroupDto(
        UUID publicId,
        String name,
        String slug,
        String logoUrl,
        BigDecimal agentFeeRate) {
}
```

- [ ] **Step 2: Write the failing controller test**

```java
package com.slparcelauctions.backend.realty.listing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RealtyGroupListingControllerTest {

    // ... existing scaffolding for @WithMockUser-equivalent (A+B has a helper),
    //     a fresh user, two groups (one with CREATE_LISTING, one without).
    //     Reuse the @Sql-driven fixture from RealtyGroupControllerTest.

    @Test
    void listing_eligible_groups_returns_only_groups_with_create_listing() {
        // Setup:
        //   - User U is leader of group A (all-implicit permissions; included).
        //   - User U is member of group B with permissions=[INVITE_AGENTS] (CREATE_LISTING missing; excluded).
        //   - User U is member of group C with permissions=[CREATE_LISTING] (included).
        //   - User U is member of dissolved group D (excluded regardless).
        // Expected: response contains A and C, in deterministic order (newest-first by member.joined_at).

        // ... call mockMvc.perform(get("/api/v1/realty/me/listing-eligible-groups")...)
        // ... assert size == 2, names contain "A" and "C".
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

```bash
cd backend
./mvnw -q test -Dtest=RealtyGroupListingControllerTest
```

Expected: COMPILATION FAILURE — controller doesn't exist yet.

- [ ] **Step 4: Implement the controller**

```java
package com.slparcelauctions.backend.realty.listing;

import java.util.List;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupMember;
import com.slparcelauctions.backend.realty.RealtyGroupMemberRepository;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;

import lombok.RequiredArgsConstructor;

/**
 * Read-only surface for sub-project C. Two endpoints:
 *
 * <ul>
 *   <li>{@code GET /api/v1/realty/me/listing-eligible-groups} — caller's groups where they
 *       hold {@code CREATE_LISTING} (or are leader → all-implicit), excluding dissolved
 *       groups. Drives the wizard's List-as picker.</li>
 *   <li>(Task 20 adds {@code GET /realty/groups/{publicId}/listings} on this same
 *       controller — group's active listings, public read.)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/realty")
@RequiredArgsConstructor
public class RealtyGroupListingController {

    private final RealtyGroupMemberRepository members;
    private final RealtyGroupRepository groups;

    @GetMapping("/me/listing-eligible-groups")
    @Transactional(readOnly = true)
    public List<ListingEligibleGroupDto> myEligibleGroups(
            @AuthenticationPrincipal AuthPrincipal principal) {
        List<RealtyGroupMember> myMemberships =
                members.findByUserIdOrderByJoinedAtDesc(principal.userId());
        List<ListingEligibleGroupDto> out = new java.util.ArrayList<>(myMemberships.size());
        for (RealtyGroupMember m : myMemberships) {
            RealtyGroup g = groups.findById(m.getGroupId()).orElse(null);
            if (g == null || g.getDissolvedAt() != null) continue;
            boolean leader = g.getLeaderId().equals(principal.userId());
            boolean hasPerm = m.getPermissions() != null
                    && m.getPermissions().contains(RealtyGroupPermission.CREATE_LISTING);
            if (!leader && !hasPerm) continue;
            String logoUrl = g.getLogoObjectKey() == null
                    ? null
                    : "/api/v1/realty/groups/" + g.getPublicId() + "/logo";
            out.add(new ListingEligibleGroupDto(
                    g.getPublicId(), g.getName(), g.getSlug(), logoUrl, g.getAgentFeeRate()));
        }
        return out;
    }
}
```

> Verify `findByUserIdOrderByJoinedAtDesc` exists on `RealtyGroupMemberRepository`. If not, add the Spring-Data derived method.

- [ ] **Step 5: Re-run test**

```bash
cd backend
./mvnw -q test -Dtest=RealtyGroupListingControllerTest
```

Expected: tests pass.

- [ ] **Step 6: Run FULL backend suite (*FULL SUITE* checkpoint)**

```bash
cd backend
./mvnw test
```

Expected: all green. This catches any regression introduced by the DTO field additions in Task 18.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/listing/ListingEligibleGroupDto.java backend/src/main/java/com/slparcelauctions/backend/realty/listing/RealtyGroupListingController.java backend/src/test/java/com/slparcelauctions/backend/realty/listing/RealtyGroupListingControllerTest.java
git commit -m "feat(realty-listing): GET /realty/me/listing-eligible-groups"
```

---

## Task 20: `GET /api/v1/realty/groups/{publicId}/listings`

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/listing/RealtyGroupListingController.java`
- Extend: `backend/src/test/java/com/slparcelauctions/backend/realty/listing/RealtyGroupListingControllerTest.java`

- [ ] **Step 1: Add the endpoint method**

In `RealtyGroupListingController`:

```java
@org.springframework.beans.factory.annotation.Autowired
private com.slparcelauctions.backend.auction.AuctionRepository auctions;

@org.springframework.beans.factory.annotation.Autowired
private com.slparcelauctions.backend.auction.AuctionDtoMapper auctionMapper;

@GetMapping("/groups/{publicId}/listings")
@Transactional(readOnly = true)
public org.springframework.data.domain.Page<com.slparcelauctions.backend.auction.dto.PublicAuctionResponse>
        groupListings(
            @org.springframework.web.bind.annotation.PathVariable java.util.UUID publicId,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "ACTIVE") String status,
            @org.springframework.data.web.PageableDefault(size = 20) org.springframework.data.domain.Pageable pageable) {
    com.slparcelauctions.backend.realty.RealtyGroup g =
            groups.findByPublicId(publicId).orElseThrow(
                    () -> new com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException(publicId));
    java.util.Set<com.slparcelauctions.backend.auction.AuctionStatus> statuses =
            parseStatuses(status);
    org.springframework.data.domain.Page<com.slparcelauctions.backend.auction.Auction> page =
            auctions.findByRealtyGroupIdAndStatusIn(g.getId(), statuses, pageable);
    return page.map(auctionMapper::toPublicResponse);
}

private static java.util.Set<com.slparcelauctions.backend.auction.AuctionStatus> parseStatuses(String csv) {
    java.util.Set<com.slparcelauctions.backend.auction.AuctionStatus> out = new java.util.HashSet<>();
    for (String s : csv.split(",")) {
        out.add(com.slparcelauctions.backend.auction.AuctionStatus.valueOf(s.trim().toUpperCase()));
    }
    return out;
}
```

> Note: the cleaner pattern is to refactor injection through the constructor. Inject via the existing `@RequiredArgsConstructor` block, not field injection — the snippet above uses `@Autowired` for brevity in the plan; convert to constructor params when implementing.

> Add `findByRealtyGroupIdAndStatusIn` (Spring Data derived) to `AuctionRepository` if absent.

- [ ] **Step 2: Add test case**

```java
@Test
void group_listings_endpoint_returns_paged_active_auctions() {
    // Setup: one group, two ACTIVE listings + one ENDED listing under that group.
    // Default ?status=ACTIVE returns 2 entries; ?status=ACTIVE,ENDED returns 3.
}
```

- [ ] **Step 3: Run test**

```bash
cd backend
./mvnw -q test -Dtest=RealtyGroupListingControllerTest
```

Expected: tests pass.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/listing/RealtyGroupListingController.java backend/src/main/java/com/slparcelauctions/backend/auction/AuctionRepository.java backend/src/test/java/com/slparcelauctions/backend/realty/listing/RealtyGroupListingControllerTest.java
git commit -m "feat(realty-listing): GET /realty/groups/{publicId}/listings"
```

---

## Task 21: Frontend types

**Files:**
- Modify: `frontend/src/types/realty.ts`
- Modify: `frontend/src/types/auction.ts`

- [ ] **Step 1: Add `ListingEligibleGroup` to `realty.ts`**

```ts
// frontend/src/types/realty.ts
// ... existing types

export interface ListingEligibleGroup {
  publicId: string;
  name: string;
  slug: string;
  logoUrl: string | null;
  /** Decimal as number from JSON, e.g. 0.02 for a 2% rate. */
  agentFeeRate: number;
}
```

- [ ] **Step 2: Extend `auction.ts` with group attribution**

```ts
// frontend/src/types/auction.ts
// ... existing types

export interface GroupAttribution {
  publicId: string;
  name: string;
  slug: string;
  logoUrl: string | null;
  dissolved: boolean;
}

export interface ListingAgent {
  publicId: string;
  displayName: string;
  avatarUrl: string | null;
}

// Extend the existing PublicAuction (or AuctionDetail) interface:
//   realtyGroup?: GroupAttribution | null;
//   listingAgent?: ListingAgent | null;
//   agentFeeRate?: number | null;
```

The existing interface for the auction wire-shape should grow these three optional fields. Use the `?` modifier (or `| null`) consistently with how the file treats other optional fields.

- [ ] **Step 3: Type-check**

```bash
cd frontend
npx tsc --noEmit
```

Expected: no type errors.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/types/realty.ts frontend/src/types/auction.ts
git commit -m "feat(realty-listing): frontend types for ListingEligibleGroup + auction group attribution"
```

---

## Task 22: API client + TanStack Query hook for listing-eligible-groups

**Files:**
- Create: `frontend/src/lib/api/realtyGroupListing.ts`
- Create: `frontend/src/hooks/realty/useListingEligibleGroups.ts`
- Create: `frontend/src/hooks/realty/useListingEligibleGroups.test.tsx`

- [ ] **Step 1: Write the failing hook test**

```tsx
import { describe, it, expect } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { server } from "@/test/msw/server";
import { http, HttpResponse } from "msw";
import { useListingEligibleGroups } from "./useListingEligibleGroups";

function wrapper({ children }: { children: React.ReactNode }) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
}

describe("useListingEligibleGroups", () => {
  it("returns eligible groups from /realty/me/listing-eligible-groups", async () => {
    server.use(
      http.get("*/api/v1/realty/me/listing-eligible-groups", () =>
        HttpResponse.json([
          { publicId: "g1", name: "Sunset Realty", slug: "sunset-realty",
            logoUrl: null, agentFeeRate: 0.02 },
        ]),
      ),
    );

    const { result } = renderHook(() => useListingEligibleGroups(), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveLength(1);
    expect(result.current.data?.[0].name).toBe("Sunset Realty");
  });

  it("returns empty array on 200 []", async () => {
    server.use(
      http.get("*/api/v1/realty/me/listing-eligible-groups", () => HttpResponse.json([])),
    );

    const { result } = renderHook(() => useListingEligibleGroups(), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toEqual([]);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd frontend
npx vitest run src/hooks/realty/useListingEligibleGroups.test.tsx
```

Expected: FAIL (module not found).

- [ ] **Step 3: Implement the API helper and hook**

```ts
// frontend/src/lib/api/realtyGroupListing.ts
import { apiFetch } from "@/lib/api";
import type { ListingEligibleGroup } from "@/types/realty";

export async function fetchListingEligibleGroups(): Promise<ListingEligibleGroup[]> {
  return apiFetch<ListingEligibleGroup[]>("/api/v1/realty/me/listing-eligible-groups");
}
```

```tsx
// frontend/src/hooks/realty/useListingEligibleGroups.ts
import { useQuery } from "@tanstack/react-query";
import { fetchListingEligibleGroups } from "@/lib/api/realtyGroupListing";

export function useListingEligibleGroups() {
  return useQuery({
    queryKey: ["realty", "me", "listing-eligible-groups"],
    queryFn: fetchListingEligibleGroups,
  });
}
```

> Verify `apiFetch` is the project's standard fetcher — check `frontend/src/lib/api/index.ts` (or similar) for the canonical helper. If the project uses a different fetcher (e.g. `client.get(...)`), use that instead.

- [ ] **Step 4: Run test to verify it passes**

```bash
cd frontend
npx vitest run src/hooks/realty/useListingEligibleGroups.test.tsx
```

Expected: 2/2 passing.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/lib/api/realtyGroupListing.ts frontend/src/hooks/realty/useListingEligibleGroups.ts frontend/src/hooks/realty/useListingEligibleGroups.test.tsx
git commit -m "feat(realty-listing): useListingEligibleGroups hook + MSW test"
```

---

## Task 23: `AgentFeePreview` component (TDD)

**Files:**
- Create: `frontend/src/components/listing/AgentFeePreview.tsx`
- Create: `frontend/src/components/listing/AgentFeePreview.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { AgentFeePreview } from "./AgentFeePreview";

describe("AgentFeePreview", () => {
  it("computes payout = startingBid - floor(startingBid * 0.05) - floor(startingBid * rate)", () => {
    // L$1000 - floor(50) - floor(20) = L$930
    render(<AgentFeePreview startingBid={1000} groupName="Sunset Realty" agentFeeRate={0.02} />);
    expect(screen.getByText(/Sunset Realty/i)).toBeInTheDocument();
    expect(screen.getByText(/L\$930/)).toBeInTheDocument();
    expect(screen.getByText(/2%/)).toBeInTheDocument();
  });

  it("shows 0% group fee when rate is 0", () => {
    render(<AgentFeePreview startingBid={1000} groupName="Free Group" agentFeeRate={0} />);
    expect(screen.getByText(/0%/)).toBeInTheDocument();
    expect(screen.getByText(/L\$950/)).toBeInTheDocument();
  });

  it("renders nothing for a non-positive startingBid", () => {
    const { container } = render(
      <AgentFeePreview startingBid={0} groupName="X" agentFeeRate={0.02} />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it("uses floor rounding (rate 0.0333 on L$1000 -> fee 33 -> payout 917)", () => {
    render(<AgentFeePreview startingBid={1000} groupName="G" agentFeeRate={0.0333} />);
    expect(screen.getByText(/L\$917/)).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd frontend
npx vitest run src/components/listing/AgentFeePreview.test.tsx
```

Expected: FAIL — component doesn't exist.

- [ ] **Step 3: Implement the component**

```tsx
// frontend/src/components/listing/AgentFeePreview.tsx
"use client";

const COMMISSION_RATE = 0.05;

function floorLindens(bid: number, rate: number): number {
  return Math.floor(bid * rate);
}

export interface AgentFeePreviewProps {
  startingBid: number;
  groupName: string;
  agentFeeRate: number;
}

export function AgentFeePreview({
  startingBid,
  groupName,
  agentFeeRate,
}: AgentFeePreviewProps) {
  if (startingBid <= 0) return null;

  const commission = floorLindens(startingBid, COMMISSION_RATE);
  const agentFee = floorLindens(startingBid, agentFeeRate);
  const payout = startingBid - commission - agentFee;
  const ratePct = (agentFeeRate * 100).toFixed(agentFeeRate < 0.01 ? 2 : 1).replace(/\.0$/, "");

  return (
    <p className="text-sm text-gray-600 mt-2">
      If this lists at L${startingBid.toLocaleString()}, you{"'"}ll receive approximately{" "}
      <strong>L${payout.toLocaleString()}</strong> after platform commission (5%) and{" "}
      {groupName} agent fee ({ratePct}%).
    </p>
  );
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd frontend
npx vitest run src/components/listing/AgentFeePreview.test.tsx
```

Expected: 4/4 passing.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/listing/AgentFeePreview.tsx frontend/src/components/listing/AgentFeePreview.test.tsx
git commit -m "feat(realty-listing): AgentFeePreview inline component"
```

---

## Task 24: `ListAsGroupPicker` component (TDD)

**Files:**
- Create: `frontend/src/components/listing/ListAsGroupPicker.tsx`
- Create: `frontend/src/components/listing/ListAsGroupPicker.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ListAsGroupPicker } from "./ListAsGroupPicker";
import type { ListingEligibleGroup } from "@/types/realty";

const groups: ListingEligibleGroup[] = [
  { publicId: "g1", name: "Sunset Realty", slug: "sunset", logoUrl: null, agentFeeRate: 0.02 },
  { publicId: "g2", name: "Sunrise Lands", slug: "sunrise", logoUrl: null, agentFeeRate: 0.01 },
];

describe("ListAsGroupPicker", () => {
  it("renders Individual + group options when eligibleGroups is non-empty", () => {
    render(<ListAsGroupPicker eligibleGroups={groups} value={null} onChange={() => {}} />);
    expect(screen.getByLabelText(/Individual/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/Sunset Realty/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/Sunrise Lands/i)).toBeInTheDocument();
  });

  it("Individual is selected by default (value=null)", () => {
    render(<ListAsGroupPicker eligibleGroups={groups} value={null} onChange={() => {}} />);
    const individual = screen.getByLabelText(/Individual/i) as HTMLInputElement;
    expect(individual.checked).toBe(true);
  });

  it("calls onChange with publicId when a group radio is clicked", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<ListAsGroupPicker eligibleGroups={groups} value={null} onChange={onChange} />);
    await user.click(screen.getByLabelText(/Sunset Realty/i));
    expect(onChange).toHaveBeenCalledWith("g1");
  });

  it("calls onChange with null when Individual is clicked from a group selection", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<ListAsGroupPicker eligibleGroups={groups} value="g1" onChange={onChange} />);
    await user.click(screen.getByLabelText(/Individual/i));
    expect(onChange).toHaveBeenCalledWith(null);
  });

  it("renders nothing when eligibleGroups is empty", () => {
    const { container } = render(
      <ListAsGroupPicker eligibleGroups={[]} value={null} onChange={() => {}} />,
    );
    expect(container).toBeEmptyDOMElement();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd frontend
npx vitest run src/components/listing/ListAsGroupPicker.test.tsx
```

Expected: FAIL — component doesn't exist.

- [ ] **Step 3: Implement the component**

```tsx
// frontend/src/components/listing/ListAsGroupPicker.tsx
"use client";

import type { ListingEligibleGroup } from "@/types/realty";

export interface ListAsGroupPickerProps {
  eligibleGroups: ListingEligibleGroup[];
  /** Selected group's publicId; null means Individual. */
  value: string | null;
  onChange: (groupPublicId: string | null) => void;
}

export function ListAsGroupPicker({
  eligibleGroups,
  value,
  onChange,
}: ListAsGroupPickerProps) {
  if (eligibleGroups.length === 0) return null;

  return (
    <fieldset className="space-y-2">
      <legend className="font-medium text-sm mb-2">List as</legend>
      <label className="flex items-center gap-2">
        <input
          type="radio"
          name="list-as"
          checked={value === null}
          onChange={() => onChange(null)}
        />
        <span>Individual</span>
      </label>
      {eligibleGroups.map((g) => (
        <label key={g.publicId} className="flex items-center gap-2">
          <input
            type="radio"
            name="list-as"
            checked={value === g.publicId}
            onChange={() => onChange(g.publicId)}
          />
          <span>{g.name}</span>
        </label>
      ))}
    </fieldset>
  );
}
```

> If the project's design system has a `<RadioGroup>` primitive, use it instead of bare `<input type="radio">`. Check `frontend/src/components/ui/` first.

- [ ] **Step 4: Run test to verify it passes**

```bash
cd frontend
npx vitest run src/components/listing/ListAsGroupPicker.test.tsx
```

Expected: 5/5 passing.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/listing/ListAsGroupPicker.tsx frontend/src/components/listing/ListAsGroupPicker.test.tsx
git commit -m "feat(realty-listing): ListAsGroupPicker radio component"
```

---

## Task 25: Wire picker + preview into `ListingWizardForm`

**Files:**
- Modify: `frontend/src/components/listing/ListingWizardForm.tsx`
- Modify: `frontend/src/components/listing/ListingWizardForm.test.tsx`

- [ ] **Step 1: Write the failing test extending the existing wizard test**

Append to the existing test file:

```tsx
describe("ListingWizardForm — List-as picker", () => {
  it("renders the picker when listing-eligible-groups returns >=1 group", async () => {
    server.use(
      http.get("*/api/v1/realty/me/listing-eligible-groups", () =>
        HttpResponse.json([
          { publicId: "g1", name: "Sunset Realty", slug: "sunset", logoUrl: null, agentFeeRate: 0.02 },
        ]),
      ),
    );
    renderWithProviders(<ListingWizardForm mode="create" />);
    expect(await screen.findByLabelText(/Sunset Realty/i)).toBeInTheDocument();
  });

  it("does NOT render the picker when listing-eligible-groups returns []", async () => {
    server.use(
      http.get("*/api/v1/realty/me/listing-eligible-groups", () => HttpResponse.json([])),
    );
    renderWithProviders(<ListingWizardForm mode="create" />);
    // Allow the query to settle
    await screen.findByText(/^Create a listing/i);
    expect(screen.queryByLabelText(/List as/i)).not.toBeInTheDocument();
  });

  it("posts listAsGroupPublicId when a group is chosen", async () => {
    const user = userEvent.setup();
    const submitSpy = vi.fn().mockResolvedValue({ publicId: "auction-1" });
    server.use(
      http.get("*/api/v1/realty/me/listing-eligible-groups", () =>
        HttpResponse.json([
          { publicId: "g1", name: "Sunset Realty", slug: "sunset", logoUrl: null, agentFeeRate: 0.02 },
        ]),
      ),
      http.post("*/api/v1/auctions", async ({ request }) => {
        const body = await request.json() as { listAsGroupPublicId?: string };
        submitSpy(body);
        return HttpResponse.json({ publicId: "auction-1" }, { status: 201 });
      }),
    );
    renderWithProviders(<ListingWizardForm mode="create" />);
    // ... fill in required fields per the existing wizard test pattern
    await user.click(await screen.findByLabelText(/Sunset Realty/i));
    // ... click submit
    await waitFor(() => expect(submitSpy).toHaveBeenCalled());
    expect(submitSpy.mock.calls[0][0].listAsGroupPublicId).toBe("g1");
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd frontend
npx vitest run src/components/listing/ListingWizardForm.test.tsx
```

Expected: new tests fail (picker not rendered).

- [ ] **Step 3: Wire the picker + preview into `ListingWizardForm`**

In `ListingWizardForm.tsx`, near the top of the component body, add:

```tsx
const eligibleGroupsQ = useListingEligibleGroups();
const [listAsGroupPublicId, setListAsGroupPublicId] = useState<string | null>(null);
const selectedGroup = eligibleGroupsQ.data?.find(g => g.publicId === listAsGroupPublicId) ?? null;
```

In the JSX, between the parcel-lookup result and the AuctionSettingsForm block, insert:

```tsx
{eligibleGroupsQ.data && eligibleGroupsQ.data.length > 0 && (
  <div className="my-4">
    <ListAsGroupPicker
      eligibleGroups={eligibleGroupsQ.data}
      value={listAsGroupPublicId}
      onChange={setListAsGroupPublicId}
    />
    {selectedGroup && startingBid > 0 && (
      <AgentFeePreview
        startingBid={startingBid}
        groupName={selectedGroup.name}
        agentFeeRate={selectedGroup.agentFeeRate}
      />
    )}
  </div>
)}
```

In the submit handler, include `listAsGroupPublicId` in the request payload:

```ts
const body = {
  // ... existing fields
  listAsGroupPublicId,
};
```

> Imports to add at the top of the file:
> ```ts
> import { useListingEligibleGroups } from "@/hooks/realty/useListingEligibleGroups";
> import { ListAsGroupPicker } from "./ListAsGroupPicker";
> import { AgentFeePreview } from "./AgentFeePreview";
> ```

- [ ] **Step 4: Run test**

```bash
cd frontend
npx vitest run src/components/listing/ListingWizardForm.test.tsx
```

Expected: all tests pass (existing + new).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/listing/ListingWizardForm.tsx frontend/src/components/listing/ListingWizardForm.test.tsx
git commit -m "feat(realty-listing): wire ListAsGroupPicker + AgentFeePreview into wizard"
```

---

## Task 26: `ListingCard` renders group chip

**Files:**
- Modify: `frontend/src/components/auction/ListingCard.tsx`
- Modify: `frontend/src/components/auction/ListingCard.test.tsx`

- [ ] **Step 1: Write the failing test**

Append to `ListingCard.test.tsx`:

```tsx
describe("ListingCard — group attribution", () => {
  it("renders GroupChip when auction has a non-dissolved realtyGroup", () => {
    render(<ListingCard auction={{
      ...baseAuction,
      realtyGroup: { publicId: "g1", name: "Sunset Realty", slug: "sunset", logoUrl: null, dissolved: false },
    }} />);
    expect(screen.getByText(/Sunset Realty/i)).toBeInTheDocument();
  });

  it("does NOT render GroupChip when realtyGroup is dissolved", () => {
    render(<ListingCard auction={{
      ...baseAuction,
      realtyGroup: { publicId: "g1", name: "Sunset Realty", slug: "sunset", logoUrl: null, dissolved: true },
    }} />);
    expect(screen.queryByText(/Sunset Realty/i)).not.toBeInTheDocument();
  });

  it("does NOT render GroupChip when realtyGroup is null", () => {
    render(<ListingCard auction={{ ...baseAuction, realtyGroup: null }} />);
    // GroupChip should not appear; the test asserts absence via the seller-line fallback being the only attribution row.
    expect(screen.queryByTestId("group-chip")).not.toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd frontend
npx vitest run src/components/auction/ListingCard.test.tsx
```

Expected: new tests fail.

- [ ] **Step 3: Render the chip in `ListingCard.tsx`**

Locate the existing seller line. Insert above it:

```tsx
{auction.realtyGroup && !auction.realtyGroup.dissolved && (
  <GroupChip group={auction.realtyGroup} data-testid="group-chip" />
)}
```

Import:

```tsx
import { GroupChip } from "@/components/realty/GroupChip";
```

> Verify `GroupChip` from A+B accepts a `dissolved` flag or the shape `{ publicId, name, slug, logoUrl }`. If the prop name differs (e.g. it expects a `RealtyGroupSummary` shape), narrow the type at the call site.

- [ ] **Step 4: Run test**

```bash
cd frontend
npx vitest run src/components/auction/ListingCard.test.tsx
```

Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/auction/ListingCard.tsx frontend/src/components/auction/ListingCard.test.tsx
git commit -m "feat(realty-listing): ListingCard renders GroupChip for group listings"
```

---

## Task 27: Auction detail page "Listed by X of Group" line + BidForm COI message (and run frontend FULL SUITE)

**Files:**
- Create: `frontend/src/components/auction/GroupAttributionLine.tsx`
- Create: `frontend/src/components/auction/GroupAttributionLine.test.tsx`
- Modify: `frontend/src/app/auction/[publicId]/page.tsx` (or the corresponding client component that renders the header)
- Modify: the bid widget component (find via `grep -rn "PlaceBid\|BidForm\|useBid" frontend/src/components/auction/`)

- [ ] **Step 1: Write the failing test for `GroupAttributionLine`**

```tsx
import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { GroupAttributionLine } from "./GroupAttributionLine";

describe("GroupAttributionLine", () => {
  it("renders 'Listed by X of Group' with a link to /group/{slug}", () => {
    render(
      <GroupAttributionLine
        agent={{ publicId: "u1", displayName: "Alice", avatarUrl: null }}
        group={{ publicId: "g1", name: "Sunset Realty", slug: "sunset", logoUrl: null, dissolved: false }}
      />,
    );
    expect(screen.getByText(/Listed by/i)).toBeInTheDocument();
    expect(screen.getByText(/Alice/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Sunset Realty/i })).toHaveAttribute("href", "/group/sunset");
  });

  it("renders nothing when group is dissolved", () => {
    const { container } = render(
      <GroupAttributionLine
        agent={{ publicId: "u1", displayName: "Alice", avatarUrl: null }}
        group={{ publicId: "g1", name: "Sunset Realty", slug: "sunset", logoUrl: null, dissolved: true }}
      />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it("renders nothing when group or agent is null", () => {
    const { container } = render(<GroupAttributionLine agent={null} group={null} />);
    expect(container).toBeEmptyDOMElement();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd frontend
npx vitest run src/components/auction/GroupAttributionLine.test.tsx
```

Expected: FAIL — component doesn't exist.

- [ ] **Step 3: Implement `GroupAttributionLine`**

```tsx
// frontend/src/components/auction/GroupAttributionLine.tsx
import Link from "next/link";
import { apiUrl } from "@/lib/api/url";
import type { GroupAttribution, ListingAgent } from "@/types/auction";

export interface GroupAttributionLineProps {
  agent: ListingAgent | null;
  group: GroupAttribution | null;
}

export function GroupAttributionLine({ agent, group }: GroupAttributionLineProps) {
  if (!agent || !group || group.dissolved) return null;
  return (
    <div className="flex items-center gap-2 text-sm">
      <span>Listed by</span>
      {agent.avatarUrl && (
        <img src={apiUrl(agent.avatarUrl) ?? undefined} alt="" className="w-5 h-5 rounded-full" />
      )}
      <strong>{agent.displayName}</strong>
      <span>of</span>
      {group.logoUrl && (
        <img src={apiUrl(group.logoUrl) ?? undefined} alt="" className="w-5 h-5 rounded" />
      )}
      <Link href={`/group/${group.slug}`} className="font-semibold underline">
        {group.name}
      </Link>
    </div>
  );
}
```

- [ ] **Step 4: Run the component test**

```bash
cd frontend
npx vitest run src/components/auction/GroupAttributionLine.test.tsx
```

Expected: 3/3 passing.

- [ ] **Step 5: Render it on the auction detail page**

Find the auction detail header component and insert `<GroupAttributionLine agent={auction.listingAgent} group={auction.realtyGroup} />` near the existing seller summary line.

- [ ] **Step 6: BidForm COI message — write the failing test**

In the bid form's existing test file:

```tsx
describe("BidForm — group COI", () => {
  it("disables the bid input when current user is a member of the auction's group", async () => {
    // Setup: mock /api/v1/me/realty-groups to return [{ publicId: 'g1', ... }].
    server.use(
      http.get("*/api/v1/me/realty-groups", () =>
        HttpResponse.json([{ publicId: "g1", name: "Sunset Realty", slug: "sunset", logoUrl: null, memberCount: 1, memberSince: "2026-01-01T00:00:00Z" }]),
      ),
    );
    renderWithProviders(<BidForm auction={{ ...activeAuction, realtyGroup: { publicId: "g1", name: "Sunset Realty", slug: "sunset", logoUrl: null, dissolved: false } }} />);
    expect(await screen.findByText(/You're a member of Sunset Realty/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Place bid/i })).toBeDisabled();
  });

  it("enables the bid input when current user is not a member", async () => {
    server.use(
      http.get("*/api/v1/me/realty-groups", () => HttpResponse.json([])),
    );
    renderWithProviders(<BidForm auction={{ ...activeAuction, realtyGroup: { publicId: "g1", name: "Sunset Realty", slug: "sunset", logoUrl: null, dissolved: false } }} />);
    await waitFor(() => expect(screen.getByRole("button", { name: /Place bid/i })).not.toBeDisabled());
  });
});
```

- [ ] **Step 7: Wire the COI check into the bid widget**

In the bid widget component, fetch `useMyRealtyGroups()` (if it exists from A+B) or call `/api/v1/me/realty-groups` directly. Compute:

```tsx
const myGroups = useMyRealtyGroups();
const isGroupMember = !!auction.realtyGroup
  && myGroups.data?.some(g => g.publicId === auction.realtyGroup!.publicId);

if (isGroupMember && auction.realtyGroup) {
  return (
    <div className="rounded p-3 bg-gray-50 text-sm text-gray-700" role="alert">
      You{"'"}re a member of <strong>{auction.realtyGroup.name}</strong> and can{"'"}t bid on its listings.
    </div>
  );
}
```

Replace the normal bid input render path with this when `isGroupMember` is true. The disabled-state still allows the rest of the page to render normally.

> Find the existing `useMyRealtyGroups` hook from A+B. If absent, this task adds it (mirror `useListingEligibleGroups` but hitting `/api/v1/me/realty-groups`).

- [ ] **Step 8: Run the FULL frontend suite (*FULL SUITE* checkpoint)**

```bash
cd frontend
npm test
```

Expected: all green.

- [ ] **Step 9: Commit**

```bash
git add frontend/src/components/auction/GroupAttributionLine.tsx frontend/src/components/auction/GroupAttributionLine.test.tsx frontend/src/app/auction/[publicId]/ frontend/src/components/auction/  # narrow to the actual bid-widget path
git commit -m "feat(realty-listing): group attribution on detail page + bid form COI message"
```

---

## Task 28: Postman collection updates

**Files:**
- Postman collection: `SLPA` (workspace `scatr-devs.postman.co`, collection id `8070328-cf3e1190-cb71-4af3-8e2e-70c82dfee288`)

- [ ] **Step 1: Add the "Realty Groups → List as Group" folder**

Inside the existing `Realty Groups` folder, add a sub-folder **List as Group** with these chained requests (variables threaded via `pm.environment.set` / `pm.collectionVariables.set`):

1. `POST /realty/groups` — body `{ "name": "PG{{$randomInt}}", "agentFeeRate": 0.02, "agentFeeSplit": 0.5 }` — test: `pm.collectionVariables.set("groupPublicId", res.publicId)`
2. `GET /realty/me/listing-eligible-groups` — assert the newly-created group appears
3. `POST /auctions` — body includes `"listAsGroupPublicId": "{{groupPublicId}}"` plus the standard auction-create payload — test: `pm.collectionVariables.set("groupAuctionId", res.publicId)`
4. `POST /auctions/{{groupAuctionId}}/bids` (as a fresh bidder user via `{{accessTokenAlt}}`) — assert 201
5. Re-auth as the leader of the group, `POST /auctions/{{groupAuctionId}}/bids` — assert `403` + `code === "GROUP_MEMBER_CANNOT_BID"`
6. `POST /dev/auction-end/run-once` (requires the `dev` profile) — fires the close
7. `GET /auctions/{{groupAuctionId}}` — assert `agent_fee_amt` is set (non-null) on the response

- [ ] **Step 2: Add the "Realty Groups → Dissolve gate" folder**

1. `POST /realty/groups` — capture `groupPublicId`
2. `POST /auctions` with the group → capture `groupAuctionId`
3. `DELETE /realty/groups/{{groupPublicId}}` (or whatever the dissolve endpoint is) — assert `409` + `code === "GROUP_HAS_ACTIVE_LISTINGS"`
4. Cancel the listing via the seller-cancel endpoint
5. Retry `DELETE /realty/groups/{{groupPublicId}}` — assert `204` (or whatever the existing dissolve success status is)

> Use the postman MCP tools to add the folder and requests programmatically rather than via the UI. Reference the existing collection structure for the seed pattern.

- [ ] **Step 3: Commit a placeholder note in the project (since Postman collection lives outside the repo)**

Update `README.md` (under the existing Postman section) to note that the **List as Group** and **Dissolve gate** folders cover sub-project C.

```bash
git add README.md
git commit -m "docs(postman): note new Realty Groups → List as Group + Dissolve gate folders"
```

---

## Task 29: README sweep + verify guards + final QA

**Files:**
- Modify: `README.md` (project root) — add a Realty Groups Listing section under the existing Realty Groups area, listing the new endpoints + COI rules.
- Modify: `docs/implementation/DEFERRED_WORK.md` — note that the agent-fee L$ distribution is deferred to D per spec §13.1.
- Modify: `docs/implementation/PHASES.md` (if it references realty group phases) — bump the C state from "planned" to "complete."

- [ ] **Step 1: Run all verify guards locally**

```bash
cd frontend
npm run verify
```

Expected: all guards pass.

- [ ] **Step 2: Run the full backend suite one more time**

```bash
cd backend
./mvnw test
```

Expected: all green.

- [ ] **Step 3: Run the full frontend suite one more time**

```bash
cd frontend
npm test
```

Expected: all green.

- [ ] **Step 4: Update README**

Add a paragraph under the Realty Groups section in `README.md` describing what C ships (List as picker, agent-fee snapshot, COI rule, dissolution gate tightening, listing-agent reassignment).

- [ ] **Step 5: Update DEFERRED_WORK.md**

Append an entry:

```markdown
- **Agent-fee L$ distribution** — sub-project C calculates and snapshots `agent_fee_amt` on the auction row at SOLD close (spec §7), but no L$ moves. Sub-project D ([#238](https://github.com/TheCodeLlama/slparcelauctions/issues/238)) reads the snapshot and credits the group wallet + agent's user wallet. Backfill policy for C-era closed auctions is decided in D's brainstorm; default is go-live-forward.
```

- [ ] **Step 6: Commit**

```bash
git add README.md docs/implementation/DEFERRED_WORK.md
git commit -m "docs(realty-listing): README + DEFERRED_WORK sweep for sub-project C"
```

- [ ] **Step 7: Push and open the PR into dev**

```bash
git push origin <feature-branch>
gh pr create --base dev --title "feat(realty): listing integration (sub-project C)" --body "$(cat <<'EOF'
## Summary
Sub-project C of realty groups, per spec docs/superpowers/specs/2026-05-11-realty-groups-listing-integration-design.md and issue #237.

- List as: Individual / Group picker on auction-create wizard
- Snapshot agent_fee_rate + agent_fee_split on the auction at create
- Snapshot agent_fee_amt at SOLD close (L$ deferred to D)
- BidEligibilityService consolidates seller + group-member COI
- listing_agent_id reassigns to leader on member leave/remove
- Leader dissolve blocked when group has active listings
- "Listed by X of Group" badge on listing card + auction detail
- Bid form COI message when viewer is a group member

## Test plan
- [ ] Backend full suite green (`./mvnw test`)
- [ ] Frontend full suite green (`npm test`)
- [ ] Frontend verify guards green (`npm run verify`)
- [ ] Postman "List as Group" + "Dissolve gate" flows pass end-to-end
- [ ] Manual: create a listing under a group, place a bid as a non-member, confirm 201; as the group leader, confirm 403 GROUP_MEMBER_CANNOT_BID
- [ ] Manual: close an auction with agent_fee_rate=2%, verify agent_fee_amt is set on the auction row
- [ ] Manual: try to dissolve a group with an ACTIVE listing; confirm 409 GROUP_HAS_ACTIVE_LISTINGS

Closes #237.
EOF
)"
```

---

## Self-Review

### Spec coverage map

| Spec section | Implemented in |
|---|---|
| §1 Goal | Whole plan |
| §2 Architecture | Tasks 6, 11, 12-13, 15-16, 17 |
| §3.1 Schema migration | Task 1 + 2 |
| §3.2 Why snapshot | Task 15 |
| §3.3 No new entity | (architectural — not a task) |
| §4 Permissions enum + wiring | Task 3, 15 |
| §5.1 AuctionCreateRequest field | Task 14 |
| §5.2 listing-eligible-groups endpoint | Task 19 |
| §5.3 Group's listings endpoint | Task 20 |
| §5.4 Domain exceptions | Tasks 4, 5, 10 |
| §5.5 DTO field additions | Task 18 |
| §6.1 Wizard picker | Tasks 22, 23, 24, 25 |
| §6.2 ListingCard chip | Task 26 |
| §6.3 BidForm COI message | Task 27 |
| §6.4 SSR safety | (handled by existing page conventions; tasks honor) |
| §7 Agent-fee snapshot | Task 17 |
| §8 BidEligibilityService | Tasks 6, 7, 8 |
| §9 Listing-agent reassignment | Tasks 9, 12, 13 |
| §10 Dissolution gate | Tasks 9, 10, 11 |
| §11 Cross-cutting integration | (covered piecewise — no new tasks) |
| §12 Testing strategy | Tasks 6, 9, 11, 12, 13, 15, 17, 19, 20, 22-27 |
| §13 Out of scope | (no work; referenced in Task 29 docs sweep) |
| §14 Migration / cutover | Task 1 |
| §15 Open decisions | (deferred — not a task) |

No spec requirement is unimplemented.

### Placeholder scan

No "TBD" / "TODO" / "implement later" / "etc." strings in implementation steps. The two `> Note:` callouts about test-fixture lookups (Tasks 9 and 17) point the implementer at concrete files to copy from — they are reminders, not placeholders.

### Type consistency

- `BidEligibilityService.assertCanBid(Auction, User)` — used identically in Tasks 6, 7, 8.
- `RealtyGroupListingService.createGroupListing(Long, AuctionCreateRequest, String)` — used identically in Tasks 15, 16.
- `auctions.reassignListingAgentForGroup(groupId, oldAgentId, newAgentId)` — used identically in Tasks 9, 12, 13.
- `auctions.existsActiveListingsByGroupId(groupId)` — used identically in Tasks 9, 11.
- `RealtyGroupPermission.CREATE_LISTING` — used identically in Tasks 3, 15, 19.
- `agent_fee_split` column — used identically in Tasks 1, 2, 15.

No type drift.

---

End of plan.
