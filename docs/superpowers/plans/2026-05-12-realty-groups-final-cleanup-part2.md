# Realty Groups: G — Part 2: Section C + Section D

> Index: [`2026-05-12-realty-groups-final-cleanup.md`](2026-05-12-realty-groups-final-cleanup.md).
> Spec: [`docs/superpowers/specs/2026-05-12-realty-groups-final-cleanup-design.md`](../specs/2026-05-12-realty-groups-final-cleanup-design.md).
> Predecessor: [`2026-05-12-realty-groups-final-cleanup-part1.md`](2026-05-12-realty-groups-final-cleanup-part1.md).

**Tasks 11–21.** Section C DTO / wire cleanup (N+1 fixes + `ListingEligibleGroupDto`), Section D admin wallet adjust core + controller + SL-group withdraw destination + leader-terms DTO field + frontend modals + banner condition flip.

**Order rule:** Part 1 must be merged on this branch first — the new enum values (`ADMIN_ADJUSTMENT`, `REALTY_GROUP_WALLET_ADMIN_ADJUSTMENT`, `WITHDRAW_GROUP`) land in Task 2, and V29 has been applied to the dev DB in Task 10.

**Concurrency:**
- Task 11 (mapper N+1) is parallel-safe with Task 12 (`ListingEligibleGroupDto`).
- Tasks 13–14 (admin wallet service + controller) chain — Task 14 depends on Task 13.
- Tasks 15–17 (SL-group withdraw request + service + bot) chain — Task 16 depends on Task 15, Task 17 depends on Task 15.
- Task 18 (DTO field) is parallel-safe with Tasks 11–17 once Part 1 is in.
- Tasks 19–21 (frontend) parallel-safe with each other once their backend DTOs land (Task 14 for the admin modal, Task 15 for the withdraw recipient picker, Task 18 for the banner).

**Codebase facts verified before drafting:**
- `Auction.winnerId` is the field actually used by `AuctionDtoMapper.resolveWinnerPublicId` (not `winnerUserId`, which is a separate column).
- `AuctionPhoto.auction` is a `@ManyToOne` association; the join column is `auction_id` — the repo query exposes auction ids via `getAuction().getId()`.
- `RealtyGroupSlGroupRepository.findBySlGroupUuid` already exists; `RealtyGroup.dissolvedAt` is the dissolution flag.
- `RealtyExceptionHandler` is package-scoped (`com.slparcelauctions.backend.realty`) so all new exception classes under `realty/wallet/exception/` are picked up automatically.
- `AdminActionService.record(...)` already takes `(adminUserId, actionType, targetType, targetId, notes, details)`.
- The bot's `Tasks/` directory has only `VerifyHandler.cs` + `MonitorHandler.cs` + `TaskLoop.cs` today — there is no `WithdrawHandler.cs`. Task 17 follows the `VerifyHandler` pattern (constructor-injected `IBotSession` + `IBackendClient`, `HandleAsync(BotTaskResponse, CancellationToken)`).
- `BotTaskType` enum currently only has `VERIFY`, `MONITOR_AUCTION`, `MONITOR_ESCROW`; Task 17 adds `WITHDRAW_GROUP` to it plus a thin extension of `BotTaskResponse` for `RecipientUuid` + `AmountL`.

---

## Task 11: `AuctionDtoMapper` `MapperBatchContext` + three N+1 fixes [parallel-safe]

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionDtoMapper.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionPhotoRepository.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/UserRepository.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auction/AuctionDtoMapperBatchContextTest.java`

- [ ] **Step 1: Write a failing test that asserts batch resolution issues exactly three repo queries**

Test file: `backend/src/test/java/com/slparcelauctions/backend/auction/AuctionDtoMapperBatchContextTest.java`

```java
package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

@ExtendWith(MockitoExtension.class)
class AuctionDtoMapperBatchContextTest {

    @Mock AuctionPhotoRepository photoRepo;
    @Mock EscrowRepository escrowRepo;
    @Mock UserRepository userRepo;
    @Mock RealtyGroupRepository realtyGroupRepo;

    private AuctionDtoMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new AuctionDtoMapper(photoRepo, escrowRepo, userRepo, realtyGroupRepo);
    }

    @Test
    void buildBatchContext_issuesExactlyThreeQueries_whenCalledForMultipleAuctions() {
        Auction a1 = auctionWith(101L, 11L, 21L);
        Auction a2 = auctionWith(102L, 12L, 22L);
        Auction a3 = auctionWith(103L, 11L, 23L); // shared group id intentional
        List<Auction> auctions = List.of(a1, a2, a3);

        when(realtyGroupRepo.findAllById(anySet())).thenReturn(List.<RealtyGroup>of());
        when(photoRepo.findPrimaryForAuctions(anySet())).thenReturn(List.<AuctionPhoto>of());
        when(userRepo.findPublicIdsByIds(anySet())).thenReturn(Map.<Long, UUID>of());

        AuctionDtoMapper.MapperBatchContext ctx =
                AuctionDtoMapper.MapperBatchContext.build(
                        auctions, realtyGroupRepo, photoRepo, userRepo);

        verify(realtyGroupRepo, times(1)).findAllById(anySet());
        verify(photoRepo, times(1)).findPrimaryForAuctions(anySet());
        verify(userRepo, times(1)).findPublicIdsByIds(anySet());
        assertThat(ctx).isNotNull();
    }

    @Test
    void buildBatchContext_skipsRepoCallsWithEmptyIdSets_whenNoAuctionHasAttribution() {
        Auction a1 = auctionWith(101L, null, null);
        Auction a2 = auctionWith(102L, null, null);

        // Empty sets still go through the repos; the implementation uses
        // findAllById(Set.of()) which JPA collapses to a no-op. Verifying the
        // call count is the durable contract — exactly one batch call per
        // attribution dimension regardless of payload size or sparsity.
        when(realtyGroupRepo.findAllById(anySet())).thenReturn(List.<RealtyGroup>of());
        when(photoRepo.findPrimaryForAuctions(anySet())).thenReturn(List.<AuctionPhoto>of());
        when(userRepo.findPublicIdsByIds(anySet())).thenReturn(Map.<Long, UUID>of());

        AuctionDtoMapper.MapperBatchContext.build(
                List.of(a1, a2), realtyGroupRepo, photoRepo, userRepo);

        verify(realtyGroupRepo, times(1)).findAllById(anySet());
        verify(photoRepo, times(1)).findPrimaryForAuctions(anySet());
        verify(userRepo, times(1)).findPublicIdsByIds(anySet());
    }

    private static Auction auctionWith(Long id, Long groupId, Long winnerId) {
        Auction a = new Auction();
        a.setId(id);
        a.setRealtyGroupId(groupId);
        a.setWinnerId(winnerId);
        return a;
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails**

```bash
./mvnw test -Dtest=AuctionDtoMapperBatchContextTest
```

Expected: compile failure — `MapperBatchContext`, `findPrimaryForAuctions`, and `findPublicIdsByIds` do not exist yet.

- [ ] **Step 3: Add the two new repo methods**

Edit `AuctionPhotoRepository.java` — append after `findMaxSortOrderByAuctionId`:

```java
    /**
     * Sub-project G §6.1 — batch resolver for "primary photo" used by
     * {@link com.slparcelauctions.backend.auction.AuctionDtoMapper.MapperBatchContext}.
     *
     * <p>Returns one {@link AuctionPhoto} per supplied auction id: the row with
     * the lowest {@code sortOrder} on each auction (matches the per-row
     * resolution that {@code photoList} used to do via
     * {@code findByAuctionIdOrderBySortOrderAsc(...).get(0)}). Auctions with no
     * photos return no row.
     *
     * <p>One query per call regardless of input cardinality. Empty input is
     * accepted and returns an empty list (no SQL emitted; Spring Data short-
     * circuits the empty {@code IN ()} case).
     */
    @Query("""
        SELECT p FROM AuctionPhoto p
         WHERE p.auction.id IN :auctionIds
           AND p.sortOrder = (
               SELECT MIN(p2.sortOrder) FROM AuctionPhoto p2
                WHERE p2.auction.id = p.auction.id)
        """)
    List<AuctionPhoto> findPrimaryForAuctions(@Param("auctionIds") java.util.Set<Long> auctionIds);
```

Edit `UserRepository.java` — append after `findIdBySlAvatarUuid`:

```java
    /**
     * Sub-project G §6.1 — batch resolver for {@code id → publicId} used by
     * {@link com.slparcelauctions.backend.auction.AuctionDtoMapper.MapperBatchContext}
     * when projecting the winner's {@code publicId} into many DTOs at once.
     *
     * <p>One query per call regardless of input cardinality. Empty input is
     * accepted and returns an empty map.
     */
    @Query("SELECT u.id AS id, u.publicId AS publicId FROM User u WHERE u.id IN :ids")
    java.util.List<UserIdPublicIdProjection> findIdPublicIdProjections(@Param("ids") Set<Long> ids);

    default Map<Long, UUID> findPublicIdsByIds(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<Long, UUID> out = new java.util.HashMap<>(ids.size());
        for (UserIdPublicIdProjection p : findIdPublicIdProjections(ids)) {
            out.put(p.getId(), p.getPublicId());
        }
        return out;
    }

    interface UserIdPublicIdProjection {
        Long getId();
        UUID getPublicId();
    }
```

- [ ] **Step 4: Add `MapperBatchContext` to `AuctionDtoMapper`**

Edit `AuctionDtoMapper.java` — add nested record + private resolvers that accept the context, then expose new batch overloads (`toBatchPublicResponses`, `toBatchSellerResponses`). The single-DTO entry points (`toPublicResponse(Auction)`, `toSellerResponse(Auction, PendingVerification)`) keep their existing per-row resolve shape — no behavior change for non-batch callers.

Add the record nested in the class (top of the file, after the fields):

```java
    /**
     * Sub-project G §6.1 — single-pass batch resolution for the three N+1s the
     * batch overloads previously incurred (group attribution, primary photo,
     * winner public id). Built once per batch via
     * {@link #build(List, RealtyGroupRepository, AuctionPhotoRepository, UserRepository)}
     * and threaded into each per-row resolve.
     */
    public record MapperBatchContext(
            java.util.Map<Long, RealtyGroup> groupsById,
            java.util.Map<Long, AuctionPhoto> primaryPhotoByAuctionId,
            java.util.Map<Long, UUID> winnerPublicIdByAuctionId) {

        public static MapperBatchContext build(
                List<Auction> auctions,
                RealtyGroupRepository groupRepo,
                AuctionPhotoRepository photoRepo,
                UserRepository userRepo) {
            java.util.Set<Long> groupIds = auctions.stream()
                    .map(Auction::getRealtyGroupId)
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());
            java.util.Set<Long> auctionIds = auctions.stream()
                    .map(Auction::getId)
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());
            java.util.Set<Long> winnerIds = auctions.stream()
                    .map(Auction::getWinnerId)
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());

            java.util.Map<Long, RealtyGroup> groups = groupRepo.findAllById(groupIds).stream()
                    .collect(java.util.stream.Collectors.toMap(
                            RealtyGroup::getId, java.util.function.Function.identity()));
            java.util.Map<Long, AuctionPhoto> primaryPhotos = photoRepo
                    .findPrimaryForAuctions(auctionIds).stream()
                    .collect(java.util.stream.Collectors.toMap(
                            p -> p.getAuction().getId(), java.util.function.Function.identity()));
            java.util.Map<Long, UUID> winners = userRepo.findPublicIdsByIds(winnerIds);
            return new MapperBatchContext(groups, primaryPhotos, winners);
        }
    }
```

Add three overloaded private resolvers that prefer the context when present and fall back to the repo when null (the single-DTO entrypoints pass `null`):

```java
    private GroupAttributionDto resolveGroupAttribution(Auction a, MapperBatchContext ctx) {
        if (a.getRealtyGroupId() == null) {
            return null;
        }
        RealtyGroup g = (ctx == null)
                ? realtyGroupRepo.findById(a.getRealtyGroupId()).orElse(null)
                : ctx.groupsById().get(a.getRealtyGroupId());
        if (g == null) {
            return null;
        }
        String logoUrl = g.getLogoObjectKey() == null
                ? null
                : "/api/v1/realty-groups/" + g.getPublicId() + "/logo/image";
        return new GroupAttributionDto(
                g.getPublicId(), g.getName(), g.getSlug(), logoUrl, g.getDissolvedAt() != null);
    }

    private List<AuctionPhotoResponse> photoList(Auction a, MapperBatchContext ctx) {
        if (a.getId() == null) {
            return List.of();
        }
        if (ctx == null) {
            return photoRepo.findByAuctionIdOrderBySortOrderAsc(a.getId()).stream()
                    .map(AuctionPhotoResponse::from)
                    .toList();
        }
        AuctionPhoto primary = ctx.primaryPhotoByAuctionId().get(a.getId());
        return primary == null ? List.of() : List.of(AuctionPhotoResponse.from(primary));
    }

    private UUID resolveWinnerPublicId(Auction a, MapperBatchContext ctx) {
        if (a.getWinnerId() == null) {
            return null;
        }
        if (ctx == null) {
            return userRepo.findById(a.getWinnerId())
                    .map(User::getPublicId)
                    .orElse(null);
        }
        return ctx.winnerPublicIdByAuctionId().get(a.getWinnerId());
    }
```

Rewire the existing `resolveGroupAttribution(Auction)`, `photoList(Auction)`, and `resolveWinnerPublicId(Auction)` callers in `toPublicResponse` / `toSellerResponse` to delegate to the new context-aware overloads with `null` (preserves single-DTO behavior). Then add batch entry points:

```java
    /**
     * Batch overload for callers that map many auctions in one go (e.g.
     * {@code listMine}, {@code search}). Pre-loads the group + primary-photo +
     * winner-publicId relations into a {@link MapperBatchContext} so each row's
     * resolve becomes a map lookup. Sub-project G §6.1.
     */
    public List<PublicAuctionResponse> toBatchPublicResponses(
            List<Auction> auctions, java.util.Map<Long, Escrow> escrowsByAuctionId) {
        MapperBatchContext ctx = MapperBatchContext.build(auctions, realtyGroupRepo, photoRepo, userRepo);
        return auctions.stream()
                .map(a -> toPublicResponse(a, escrowsByAuctionId.get(a.getId()), ctx))
                .toList();
    }

    public List<SellerAuctionResponse> toBatchSellerResponses(
            List<Auction> auctions,
            java.util.Map<Long, PendingVerification> pendingByAuctionId,
            java.util.Map<Long, Escrow> escrowsByAuctionId) {
        MapperBatchContext ctx = MapperBatchContext.build(auctions, realtyGroupRepo, photoRepo, userRepo);
        return auctions.stream()
                .map(a -> toSellerResponse(a, pendingByAuctionId.get(a.getId()),
                        escrowsByAuctionId.get(a.getId()), ctx))
                .toList();
    }
```

Add the corresponding context-aware overloads `toPublicResponse(Auction, Escrow, MapperBatchContext)` / `toSellerResponse(Auction, PendingVerification, Escrow, MapperBatchContext)` that mirror the existing two-arg overloads but route through the ctx-aware private resolvers. Existing two-arg overloads keep their signature and call the new three-arg form with `ctx = null`.

- [ ] **Step 5: Update existing batch callers (`listMine`, `search`) to use the new batch entry points**

```bash
./mvnw -q clean compile
grep -rn "toSellerResponse\|toPublicResponse" backend/src/main/java/com/slparcelauctions/backend/auction/ \
    backend/src/main/java/com/slparcelauctions/backend/admin/
```

For every call site that already iterates a `List<Auction>` and calls the per-row mapper inside a loop (typically `listMine` / `search` controllers and services), replace with `toBatchPublicResponses(...)` / `toBatchSellerResponses(...)`. Keep single-auction call sites as-is.

- [ ] **Step 6: Run the tests to confirm they pass + full suite stays green**

```bash
./mvnw test -Dtest=AuctionDtoMapperBatchContextTest
./mvnw test
```

Expected: target test green; full suite green.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/AuctionDtoMapper.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/AuctionPhotoRepository.java \
        backend/src/main/java/com/slparcelauctions/backend/user/UserRepository.java \
        backend/src/test/java/com/slparcelauctions/backend/auction/AuctionDtoMapperBatchContextTest.java
git commit -m "refactor(realty-g): AuctionDtoMapper MapperBatchContext -- fix three N+1s on batch paths"
```

---

## Task 12: `ListingEligibleGroupDto` `agentFeeRate` → `agentCommissionRate` swap [parallel-safe]

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/listing/ListingEligibleGroupDto.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/listing/RealtyGroupListingService.java`
- Modify: `frontend/src/types/realty.ts` (or wherever the matching TS type lives)
- Modify: any frontend wizard hooks reading `agentFeeRate` off the eligible list
- Modify: associated test file(s)

- [ ] **Step 1: Write a failing test asserting per-user commission rate flows through**

Test file: `backend/src/test/java/com/slparcelauctions/backend/realty/listing/RealtyGroupListingServiceEligibleRateTest.java`

```java
package com.slparcelauctions.backend.realty.listing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupMember;
import com.slparcelauctions.backend.realty.RealtyGroupMemberRepository;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

@SpringBootTest
@Transactional
class RealtyGroupListingServiceEligibleRateTest {

    @Autowired RealtyGroupListingService service;
    @Autowired RealtyGroupRepository groups;
    @Autowired RealtyGroupMemberRepository members;
    @Autowired UserRepository users;

    @Test
    void findEligibleForParcel_returnsCallerSpecificCommissionRate_whenMultipleMembersHaveDifferentRates() {
        // Fixture (group A: user X has 0.10, user Y has 0.15).
        // Use real seeded fixtures via @Sql or programmatic insertion --
        // the assertion below is the durable contract: each caller sees
        // their OWN per-member rate, not the group's average.
        User x = users.save(User.builder().username("listing-x").build());
        User y = users.save(User.builder().username("listing-y").build());
        RealtyGroup g = groups.save(RealtyGroup.builder().name("g-rate").slug("g-rate").build());
        members.save(RealtyGroupMember.builder().groupId(g.getId()).userId(x.getId())
                .agentCommissionRate(new BigDecimal("0.1000")).build());
        members.save(RealtyGroupMember.builder().groupId(g.getId()).userId(y.getId())
                .agentCommissionRate(new BigDecimal("0.1500")).build());

        UUID parcelUuid = UUID.randomUUID();
        // Stub the parcel-lookup path so it resolves to a group-owned parcel
        // whose owner SL group UUID matches a verified registration for g.
        // (Fixture wiring detail elided — match the existing service test
        // setup for parcel-aware eligibility.)
        // ...

        List<ListingEligibleGroupDto> asX = service.findEligibleForParcel(x.getId(), parcelUuid);
        List<ListingEligibleGroupDto> asY = service.findEligibleForParcel(y.getId(), parcelUuid);

        assertThat(asX)
                .extracting(ListingEligibleGroupDto::publicId, ListingEligibleGroupDto::agentCommissionRate)
                .containsExactly(tuple(g.getPublicId(), new BigDecimal("0.1000")));
        assertThat(asY)
                .extracting(ListingEligibleGroupDto::publicId, ListingEligibleGroupDto::agentCommissionRate)
                .containsExactly(tuple(g.getPublicId(), new BigDecimal("0.1500")));
    }
}
```

(If the existing test class has the parcel-lookup fixture machinery wired, add the new assertion to that class instead.)

- [ ] **Step 2: Run the test to confirm it fails**

```bash
./mvnw test -Dtest=RealtyGroupListingServiceEligibleRateTest
```

Expected: compile failure — `agentCommissionRate` accessor does not exist on the DTO yet.

- [ ] **Step 3: Rename the DTO field**

Edit `ListingEligibleGroupDto.java` — replace the record body:

```java
package com.slparcelauctions.backend.realty.listing;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Read-only row for the {@code GET /api/v1/realty/me/listing-eligible-groups} endpoint.
 * Drives the wizard's List-as picker; carries only what the picker needs to render +
 * compute its fee preview.
 *
 * <p>{@code agentCommissionRate} is the calling user's per-member commission rate
 * within this group (sub-project G §6.2). The wizard reads it directly off the
 * eligible-list row, removing the prior per-parcel {@code useRealtyGroup} round-trip.
 */
public record ListingEligibleGroupDto(
        UUID publicId,
        String name,
        String slug,
        String logoUrl,
        BigDecimal agentCommissionRate) {
}
```

- [ ] **Step 4: Update the service query to project the per-caller rate**

Edit `RealtyGroupListingService.findEligibleForParcel` — after the leader/permission filter inside the loop, replace the existing DTO construction (line ~163-165) with one that pulls the per-member rate. Since the leader implicitly has all permissions, the leader's rate is whatever lives on their `realty_group_members.agent_commission_rate` (leaders have a member row by convention; if not, fall back to `BigDecimal.ZERO`):

```java
            BigDecimal commissionRate = members
                    .findCommissionRate(g.getId(), callerUserId)
                    .orElse(BigDecimal.ZERO);
            String logoUrl = g.getLogoObjectKey() == null
                    ? null
                    : "/api/v1/realty-groups/" + g.getPublicId() + "/logo/image";
            out.add(new ListingEligibleGroupDto(
                    g.getPublicId(), g.getName(), g.getSlug(), logoUrl, commissionRate));
```

(`findCommissionRate(Long groupId, Long userId)` is the existing repository method `RealtyGroupListingService.createGroupListing` already calls at line 89-91 — reuse it.)

Update the class Javadoc paragraph that says `agentFeeRate` is `null` to say `agentCommissionRate` carries the caller's per-member rate.

- [ ] **Step 5: Update the frontend type + wizard hook**

```bash
grep -rn "agentFeeRate\|listing-eligible-group" frontend/src/
```

Edit the TS type definition (likely `frontend/src/types/realty.ts`) — rename the field from `agentFeeRate?: number | null` to `agentCommissionRate: number | null`. Update every consumer; the wizard's fee-preview path should read `agentCommissionRate` directly and drop any code path that fetched the per-member rate via `useRealtyGroup`.

- [ ] **Step 6: Run tests**

```bash
./mvnw test -Dtest=RealtyGroupListingServiceEligibleRateTest
./mvnw test
cd frontend && npm run lint && npm test
cd ..
```

Expected: green.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/listing/ListingEligibleGroupDto.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/listing/RealtyGroupListingService.java \
        backend/src/test/java/com/slparcelauctions/backend/realty/listing/RealtyGroupListingServiceEligibleRateTest.java \
        frontend/src/types/realty.ts \
        frontend/src/
git commit -m "feat(realty-g): ListingEligibleGroupDto carries per-caller agentCommissionRate"
```

---

## Task 13: `AdminRealtyGroupWalletService` — admin wallet adjust core

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/wallet/admin/AdminRealtyGroupWalletService.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/wallet/admin/AdminWalletAdjustProperties.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/RealtyConfig.java` (register the new properties bean)
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/wallet/exception/AdminAdjustAmountOutOfRangeException.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/realty/wallet/admin/AdminRealtyGroupWalletServiceTest.java`

- [ ] **Step 1: Write a failing test covering credit, debit, validation, audit, broadcast**

Test file: `backend/src/test/java/com/slparcelauctions/backend/realty/wallet/admin/AdminRealtyGroupWalletServiceTest.java`

```java
package com.slparcelauctions.backend.realty.wallet.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.admin.audit.AdminAction;
import com.slparcelauctions.backend.admin.audit.AdminActionRepository;
import com.slparcelauctions.backend.admin.audit.AdminActionType;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.wallet.RealtyGroupLedgerEntry;
import com.slparcelauctions.backend.realty.wallet.RealtyGroupLedgerEntryType;
import com.slparcelauctions.backend.realty.wallet.RealtyGroupLedgerRepository;
import com.slparcelauctions.backend.realty.wallet.dto.GroupWalletDto;
import com.slparcelauctions.backend.realty.wallet.exception.AdminAdjustAmountOutOfRangeException;
import com.slparcelauctions.backend.realty.wallet.exception.InsufficientGroupBalanceException;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

@SpringBootTest
@Transactional
@DirtiesContext
class AdminRealtyGroupWalletServiceTest {

    @Autowired AdminRealtyGroupWalletService service;
    @Autowired RealtyGroupRepository groups;
    @Autowired RealtyGroupLedgerRepository ledger;
    @Autowired AdminActionRepository adminActions;
    @Autowired UserRepository users;

    @Test
    void adjust_credits_walletBalanceAndWritesLedgerAuditRow_whenAmountIsPositive() {
        User admin = users.save(User.builder().username("admin-a").role(Role.ADMIN).build());
        RealtyGroup g = groups.save(RealtyGroup.builder()
                .name("adj-credit").slug("adj-credit").leaderId(1L).balanceLindens(0L).build());

        GroupWalletDto out = service.adjust(admin.getId(), g.getPublicId(), 2500L,
                "Compensating bad payout");

        assertThat(out.balance()).isEqualTo(2500L);
        assertThat(groups.findById(g.getId()).orElseThrow().getBalanceLindens()).isEqualTo(2500L);
        RealtyGroupLedgerEntry tail = ledger.findRecentForGroup(g.getId(),
                org.springframework.data.domain.PageRequest.of(0, 1)).get(0);
        assertThat(tail.getEntryType()).isEqualTo(RealtyGroupLedgerEntryType.ADMIN_ADJUSTMENT);
        assertThat(tail.getAmount()).isEqualTo(2500L);
        assertThat(tail.getDescription()).isEqualTo("Compensating bad payout");
        AdminAction action = adminActions.findAll().stream()
                .filter(a -> a.getActionType() == AdminActionType.REALTY_GROUP_WALLET_ADMIN_ADJUSTMENT)
                .findFirst().orElseThrow();
        assertThat(action.getTargetId()).isEqualTo(g.getId());
        assertThat(action.getDetails()).containsEntry("amount", 2500)
                .containsEntry("reason", "Compensating bad payout");
    }

    @Test
    void adjust_debitsWallet_whenAmountIsNegativeAndBalanceAdequate() {
        User admin = users.save(User.builder().username("admin-b").role(Role.ADMIN).build());
        RealtyGroup g = groups.save(RealtyGroup.builder()
                .name("adj-debit").slug("adj-debit").leaderId(2L).balanceLindens(10_000L).build());

        GroupWalletDto out = service.adjust(admin.getId(), g.getPublicId(), -3500L, "Recovering overpaid commission");

        assertThat(out.balance()).isEqualTo(6_500L);
    }

    @Test
    void adjust_throws_whenDebitWouldOverdraw() {
        User admin = users.save(User.builder().username("admin-c").role(Role.ADMIN).build());
        RealtyGroup g = groups.save(RealtyGroup.builder()
                .name("adj-overdraw").slug("adj-overdraw").leaderId(3L).balanceLindens(500L).build());

        assertThatThrownBy(() -> service.adjust(admin.getId(), g.getPublicId(), -1000L, "Bad call"))
                .isInstanceOf(InsufficientGroupBalanceException.class);
    }

    @Test
    void adjust_throws_whenAmountIsZero() {
        User admin = users.save(User.builder().username("admin-d").role(Role.ADMIN).build());
        RealtyGroup g = groups.save(RealtyGroup.builder()
                .name("adj-zero").slug("adj-zero").leaderId(4L).balanceLindens(100L).build());

        assertThatThrownBy(() -> service.adjust(admin.getId(), g.getPublicId(), 0L, "Nope"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void adjust_throws_whenAmountExceedsCeiling() {
        User admin = users.save(User.builder().username("admin-e").role(Role.ADMIN).build());
        RealtyGroup g = groups.save(RealtyGroup.builder()
                .name("adj-huge").slug("adj-huge").leaderId(5L).balanceLindens(0L).build());

        assertThatThrownBy(() -> service.adjust(admin.getId(), g.getPublicId(), 10_000_001L, "Too big"))
                .isInstanceOf(AdminAdjustAmountOutOfRangeException.class);
    }

    @Test
    void adjust_throws_whenReasonIsBlank() {
        User admin = users.save(User.builder().username("admin-f").role(Role.ADMIN).build());
        RealtyGroup g = groups.save(RealtyGroup.builder()
                .name("adj-blank").slug("adj-blank").leaderId(6L).balanceLindens(0L).build());

        assertThatThrownBy(() -> service.adjust(admin.getId(), g.getPublicId(), 100L, "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails**

```bash
./mvnw test -Dtest=AdminRealtyGroupWalletServiceTest
```

Expected: compile failure — `AdminRealtyGroupWalletService`, `AdminAdjustAmountOutOfRangeException`, and `AdminWalletAdjustProperties` do not exist yet.

- [ ] **Step 3: Create the exception class**

`backend/src/main/java/com/slparcelauctions/backend/realty/wallet/exception/AdminAdjustAmountOutOfRangeException.java`:

```java
package com.slparcelauctions.backend.realty.wallet.exception;

/**
 * Sub-project G §7.2 -- admin-initiated wallet adjustment exceeded the sanity
 * ceiling configured by {@code slpa.realty.admin-wallet-adjust-max-l}. Surfaced
 * as 422 by {@code RealtyExceptionHandler} so the admin UI can show a clear
 * "adjustment too large" error and operators can dial the ceiling up via config
 * if a legitimate large adjustment is ever blocked.
 */
public class AdminAdjustAmountOutOfRangeException extends RuntimeException {

    private final long amount;
    private final long ceiling;

    public AdminAdjustAmountOutOfRangeException(long amount, long ceiling) {
        super("amount " + amount + " exceeds configured ceiling " + ceiling);
        this.amount = amount;
        this.ceiling = ceiling;
    }

    public long getAmount() { return amount; }
    public long getCeiling() { return ceiling; }
}
```

- [ ] **Step 4: Create the properties bean**

`backend/src/main/java/com/slparcelauctions/backend/realty/wallet/admin/AdminWalletAdjustProperties.java`:

```java
package com.slparcelauctions.backend.realty.wallet.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

/**
 * Sub-project G §7.2 -- admin wallet adjustment knobs. The ceiling is a sanity
 * gate against fat-finger mistakes (the prod policy lever is admin review, not
 * the cap). Tuneable via config -- operators dial it up if a legitimate large
 * adjustment is ever blocked.
 */
@ConfigurationProperties(prefix = "slpa.realty")
@Getter
@Setter
public class AdminWalletAdjustProperties {

    @Min(1)
    private long adminWalletAdjustMaxL = 10_000_000L;
}
```

Edit `RealtyConfig.java` — extend the `@EnableConfigurationProperties` array:

```java
@EnableConfigurationProperties({
        RealtyGroupModerationProperties.class,
        com.slparcelauctions.backend.realty.wallet.admin.AdminWalletAdjustProperties.class})
```

- [ ] **Step 5: Create `AdminRealtyGroupWalletService`**

`backend/src/main/java/com/slparcelauctions/backend/realty/wallet/admin/AdminRealtyGroupWalletService.java`:

```java
package com.slparcelauctions.backend.realty.wallet.admin;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.admin.audit.AdminActionService;
import com.slparcelauctions.backend.admin.audit.AdminActionTargetType;
import com.slparcelauctions.backend.admin.audit.AdminActionType;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException;
import com.slparcelauctions.backend.realty.exception.GroupDissolvedException;
import com.slparcelauctions.backend.realty.wallet.RealtyGroupLedgerEntry;
import com.slparcelauctions.backend.realty.wallet.RealtyGroupLedgerEntryType;
import com.slparcelauctions.backend.realty.wallet.RealtyGroupLedgerRepository;
import com.slparcelauctions.backend.realty.wallet.broadcast.GroupWalletBroadcastPublisher;
import com.slparcelauctions.backend.realty.wallet.dto.GroupWalletDto;
import com.slparcelauctions.backend.realty.wallet.dto.GroupWalletDtoMapper;
import com.slparcelauctions.backend.realty.wallet.exception.AdminAdjustAmountOutOfRangeException;
import com.slparcelauctions.backend.realty.wallet.exception.InsufficientGroupBalanceException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Sub-project G §7.2 -- admin-initiated realty group wallet adjustment.
 * Single endpoint with signed amount (positive = credit, negative = debit) and
 * a freeform reason. Writes one {@code ADMIN_ADJUSTMENT} ledger row, mutates
 * {@code realty_groups.balance_lindens} atomically, writes a paired
 * {@code REALTY_GROUP_WALLET_ADMIN_ADJUSTMENT} audit row, and broadcasts the
 * balance change on the group's WS topic.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminRealtyGroupWalletService {

    private static final int MAX_REASON_LENGTH = 500;
    private static final int RECENT_LEDGER_LIMIT = 50;

    private final RealtyGroupRepository groupRepository;
    private final RealtyGroupLedgerRepository ledgerRepository;
    private final UserRepository userRepository;
    private final AdminActionService adminActionService;
    private final GroupWalletBroadcastPublisher broadcastPublisher;
    private final GroupWalletDtoMapper walletDtoMapper;
    private final AdminWalletAdjustProperties props;

    @Transactional
    public GroupWalletDto adjust(Long adminUserId, UUID groupPublicId, long amount, String reason) {
        if (amount == 0L) {
            throw new IllegalArgumentException("amount must be non-zero");
        }
        if (Math.abs(amount) > props.getAdminWalletAdjustMaxL()) {
            throw new AdminAdjustAmountOutOfRangeException(amount, props.getAdminWalletAdjustMaxL());
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must be non-blank");
        }
        String trimmed = reason.strip();
        if (trimmed.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("reason exceeds " + MAX_REASON_LENGTH + " chars");
        }

        RealtyGroup g = groupRepository.findByPublicId(groupPublicId)
                .orElseThrow(() -> new RealtyGroupNotFoundException(groupPublicId));
        if (g.getDissolvedAt() != null) {
            throw new GroupDissolvedException(groupPublicId);
        }

        // Re-acquire under pessimistic lock for the balance mutation.
        RealtyGroup locked = groupRepository.findByIdForUpdate(g.getId()).orElseThrow();
        long newBalance = locked.getBalanceLindens() + amount;
        if (newBalance < 0L) {
            // §7.2 -- a debit may not push balance below zero.
            throw new InsufficientGroupBalanceException(locked.availableLindens(), Math.abs(amount));
        }
        locked.setBalanceLindens(newBalance);
        groupRepository.save(locked);

        RealtyGroupLedgerEntry entry = ledgerRepository.save(RealtyGroupLedgerEntry.builder()
                .groupId(locked.getId())
                .entryType(RealtyGroupLedgerEntryType.ADMIN_ADJUSTMENT)
                .amount(amount)
                .balanceAfter(newBalance)
                .reservedAfter(locked.getReservedLindens())
                .description(trimmed)
                .actorAdminUserId(adminUserId)
                .build());

        adminActionService.record(
                adminUserId,
                AdminActionType.REALTY_GROUP_WALLET_ADMIN_ADJUSTMENT,
                AdminActionTargetType.REALTY_GROUP,
                locked.getId(),
                null,
                Map.of("amount", amount, "reason", trimmed));

        broadcastPublisher.publish(locked.getPublicId(),
                newBalance, locked.getReservedLindens(), locked.availableLindens(),
                RealtyGroupLedgerEntryType.ADMIN_ADJUSTMENT.name(), entry.getPublicId());

        log.info("admin wallet adjustment: groupId={}, amount={}, balanceAfter={}, admin={}",
                locked.getId(), amount, newBalance, adminUserId);

        User leader = userRepository.findById(locked.getLeaderId()).orElseThrow();
        List<RealtyGroupLedgerEntry> recent = ledgerRepository.findRecentForGroup(
                locked.getId(), PageRequest.of(0, RECENT_LEDGER_LIMIT));
        return new GroupWalletDto(
                locked.getBalanceLindens(),
                locked.getReservedLindens(),
                locked.availableLindens(),
                leader.getWalletTermsAcceptedAt() == null ? null : leader.getWalletTermsAcceptedAt().toInstant(),
                walletDtoMapper.toDtos(recent));
    }
}
```

(Note: the new `leaderTermsAcceptedAt` field on `GroupWalletDto` lands in Task 18; if Task 18 is sequenced after Task 13 the constructor call needs adjusting once Task 18 merges. The recommended order — Task 18 first, since it's parallel-safe — sidesteps the issue.)

`RealtyGroupLedgerEntry.actorAdminUserId` may be the same column as `actorUserId` — if so, use `actorUserId(adminUserId)` and let the audit row carry the "admin" disambiguation. Verify the existing entity field name and use it. Some prior tasks may have introduced an explicit `actorAdminUserId` field; if not, the audit row is the source of truth for "admin actor" and `actorUserId` suffices on the ledger side.

Also confirm `AdminActionTargetType.REALTY_GROUP` exists; if not, add it as part of this task.

- [ ] **Step 6: Run the test to confirm it passes**

```bash
./mvnw test -Dtest=AdminRealtyGroupWalletServiceTest
```

Expected: green.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/wallet/admin/ \
        backend/src/main/java/com/slparcelauctions/backend/realty/wallet/exception/AdminAdjustAmountOutOfRangeException.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/RealtyConfig.java \
        backend/src/test/java/com/slparcelauctions/backend/realty/wallet/admin/AdminRealtyGroupWalletServiceTest.java
git commit -m "feat(realty-g): AdminRealtyGroupWalletService -- admin wallet adjustment core"
```

---

## Task 14: `AdminRealtyGroupWalletController` + DTOs + exception handler mapping

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/wallet/admin/AdminRealtyGroupWalletController.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/wallet/admin/dto/AdminWalletAdjustRequest.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/exception/RealtyExceptionHandler.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/realty/wallet/admin/AdminRealtyGroupWalletControllerTest.java`

- [ ] **Step 1: Write a failing test exercising the controller end-to-end**

Test file: `backend/src/test/java/com/slparcelauctions/backend/realty/wallet/admin/AdminRealtyGroupWalletControllerTest.java`

```java
package com.slparcelauctions.backend.realty.wallet.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminRealtyGroupWalletControllerTest {

    @Autowired MockMvc mvc;
    @Autowired RealtyGroupRepository groups;
    @Autowired UserRepository users;

    @Test
    @WithMockUser(roles = "ADMIN")
    void post_walletAdjust_returns200WithUpdatedWalletDto_whenInputsValid() throws Exception {
        User admin = users.save(User.builder().username("ctrl-a").role(Role.ADMIN).build());
        RealtyGroup g = groups.save(RealtyGroup.builder()
                .name("ctrl-credit").slug("ctrl-credit").leaderId(admin.getId()).balanceLindens(0L).build());

        mvc.perform(post("/api/v1/admin/realty-groups/{publicId}/wallet/adjust", g.getPublicId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 1500, "reason": "Manual credit for support ticket SLPA-42"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(1500));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void post_walletAdjust_returns422_whenAmountIsZero() throws Exception {
        User admin = users.save(User.builder().username("ctrl-b").role(Role.ADMIN).build());
        RealtyGroup g = groups.save(RealtyGroup.builder()
                .name("ctrl-zero").slug("ctrl-zero").leaderId(admin.getId()).balanceLindens(0L).build());

        mvc.perform(post("/api/v1/admin/realty-groups/{publicId}/wallet/adjust", g.getPublicId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 0, "reason": "nope"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void post_walletAdjust_returns422_whenAmountExceedsCeiling() throws Exception {
        User admin = users.save(User.builder().username("ctrl-c").role(Role.ADMIN).build());
        RealtyGroup g = groups.save(RealtyGroup.builder()
                .name("ctrl-huge").slug("ctrl-huge").leaderId(admin.getId()).balanceLindens(0L).build());

        mvc.perform(post("/api/v1/admin/realty-groups/{publicId}/wallet/adjust", g.getPublicId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 20000000, "reason": "Too big to fit"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ADMIN_ADJUST_AMOUNT_OUT_OF_RANGE"));
    }

    @Test
    @WithMockUser(roles = "USER")
    void post_walletAdjust_returns403_whenCallerIsNotAdmin() throws Exception {
        RealtyGroup g = groups.save(RealtyGroup.builder()
                .name("ctrl-forbidden").slug("ctrl-forbidden").leaderId(1L).balanceLindens(0L).build());

        mvc.perform(post("/api/v1/admin/realty-groups/{publicId}/wallet/adjust", g.getPublicId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 100, "reason": "drive-by"}
                                """))
                .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails**

```bash
./mvnw test -Dtest=AdminRealtyGroupWalletControllerTest
```

Expected: 4 failures — controller does not exist.

- [ ] **Step 3: Create the request DTO**

`backend/src/main/java/com/slparcelauctions/backend/realty/wallet/admin/dto/AdminWalletAdjustRequest.java`:

```java
package com.slparcelauctions.backend.realty.wallet.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Sub-project G §7.2 -- admin realty-group wallet adjustment request body.
 * {@code amount} is signed: positive credits, negative debits. Zero is
 * rejected at the service layer.
 */
public record AdminWalletAdjustRequest(
        long amount,
        @NotBlank @Size(max = 500) String reason) {
}
```

- [ ] **Step 4: Create the controller**

`backend/src/main/java/com/slparcelauctions/backend/realty/wallet/admin/AdminRealtyGroupWalletController.java`:

```java
package com.slparcelauctions.backend.realty.wallet.admin;

import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.realty.wallet.admin.dto.AdminWalletAdjustRequest;
import com.slparcelauctions.backend.realty.wallet.dto.GroupWalletDto;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Sub-project G §7.2 -- admin realty group wallet adjustment surface.
 * Single endpoint; signed-amount payload; service writes ledger + audit
 * + broadcast as one transactional unit.
 */
@RestController
@RequestMapping("/api/v1/admin/realty-groups/{publicId}/wallet")
@RequiredArgsConstructor
public class AdminRealtyGroupWalletController {

    private final AdminRealtyGroupWalletService service;

    @PostMapping("/adjust")
    @PreAuthorize("hasRole('ADMIN')")
    public GroupWalletDto adjust(
            @PathVariable UUID publicId,
            @Valid @RequestBody AdminWalletAdjustRequest req,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return service.adjust(principal.userId(), publicId, req.amount(), req.reason());
    }
}
```

- [ ] **Step 5: Map the new exception in `RealtyExceptionHandler`**

Edit `RealtyExceptionHandler.java` — add the import + handler method alongside the other wallet exception mappings:

```java
import com.slparcelauctions.backend.realty.wallet.exception.AdminAdjustAmountOutOfRangeException;
```

```java
    @ExceptionHandler(AdminAdjustAmountOutOfRangeException.class)
    public ProblemDetail handleAdminAdjustOutOfRange(
            AdminAdjustAmountOutOfRangeException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        pd.setTitle("Admin Adjust Amount Out Of Range");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "ADMIN_ADJUST_AMOUNT_OUT_OF_RANGE");
        pd.setProperty("amount", e.getAmount());
        pd.setProperty("ceiling", e.getCeiling());
        return pd;
    }
```

- [ ] **Step 6: Run the test to confirm it passes + full suite stays green**

```bash
./mvnw test -Dtest=AdminRealtyGroupWalletControllerTest
./mvnw test
```

Expected: green.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/wallet/admin/AdminRealtyGroupWalletController.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/wallet/admin/dto/AdminWalletAdjustRequest.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/exception/RealtyExceptionHandler.java \
        backend/src/test/java/com/slparcelauctions/backend/realty/wallet/admin/AdminRealtyGroupWalletControllerTest.java
git commit -m "feat(realty-g): POST /api/v1/admin/realty-groups/{publicId}/wallet/adjust"
```

---

## Task 15: `GroupWithdrawRequest` extension + `GroupWithdrawRecipient` enum

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/wallet/dto/GroupWithdrawRecipient.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/wallet/dto/GroupWithdrawRequest.java`
- Modify: `backend/src/test/java/com/slparcelauctions/backend/realty/wallet/dto/GroupWithdrawRequestTest.java` (create if absent)

- [ ] **Step 1: Write a failing test confirming the request requires a recipient**

Test file: `backend/src/test/java/com/slparcelauctions/backend/realty/wallet/dto/GroupWithdrawRequestTest.java`

```java
package com.slparcelauctions.backend.realty.wallet.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class GroupWithdrawRequestTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void validation_rejects_whenRecipientIsNull() {
        GroupWithdrawRequest req = new GroupWithdrawRequest(100L, UUID.randomUUID(), null);
        Set<ConstraintViolation<GroupWithdrawRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> "recipient".equals(v.getPropertyPath().toString()));
    }

    @Test
    void validation_accepts_whenRecipientIsAvatar() {
        GroupWithdrawRequest req = new GroupWithdrawRequest(
                100L, UUID.randomUUID(), GroupWithdrawRecipient.AVATAR);
        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    void validation_accepts_whenRecipientIsSlGroup() {
        GroupWithdrawRequest req = new GroupWithdrawRequest(
                100L, UUID.randomUUID(), GroupWithdrawRecipient.SL_GROUP);
        assertThat(validator.validate(req)).isEmpty();
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails**

```bash
./mvnw test -Dtest=GroupWithdrawRequestTest
```

Expected: compile failure — `GroupWithdrawRecipient` does not exist; `GroupWithdrawRequest` has only two parameters.

- [ ] **Step 3: Create the enum**

`backend/src/main/java/com/slparcelauctions/backend/realty/wallet/dto/GroupWithdrawRecipient.java`:

```java
package com.slparcelauctions.backend.realty.wallet.dto;

/**
 * Sub-project G §7.3 -- group wallet withdrawal destination.
 * {@link #AVATAR} routes L$ to the group leader's verified SL avatar (the
 * pre-G flow). {@link #SL_GROUP} routes to the currently-registered
 * {@code RealtyGroupSlGroup} for the realty group (bot-fulfilled via
 * {@code Self.GiveGroupMoney}).
 */
public enum GroupWithdrawRecipient {
    AVATAR,
    SL_GROUP
}
```

- [ ] **Step 4: Extend the request record**

Edit `GroupWithdrawRequest.java`:

```java
package com.slparcelauctions.backend.realty.wallet.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Request body for POST /api/v1/realty/groups/{publicId}/wallet/withdraw.
 * Spec §5.3 / §5.6; recipient field added in sub-project G §7.3.
 *
 * <p>No backward-compat default. The {@code recipient} field is required
 * ({@code @NotNull}). SLParcels is pre-launch with no production clients,
 * so all callers update atomically with the G PR.
 */
public record GroupWithdrawRequest(
        @Positive long amount,
        @NotNull UUID idempotencyKey,
        @NotNull GroupWithdrawRecipient recipient) {
}
```

- [ ] **Step 5: Update every existing test fixture / controller call site**

```bash
grep -rn "new GroupWithdrawRequest(" backend/src/
```

For each constructor site, append `GroupWithdrawRecipient.AVATAR` as the third argument (preserves existing semantics — pre-G the only destination was the avatar).

- [ ] **Step 6: Run the test to confirm it passes + full suite stays green**

```bash
./mvnw test -Dtest=GroupWithdrawRequestTest
./mvnw test
```

Expected: green.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/wallet/dto/GroupWithdrawRecipient.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/wallet/dto/GroupWithdrawRequest.java \
        backend/src/test/java/com/slparcelauctions/backend/realty/wallet/dto/GroupWithdrawRequestTest.java \
        backend/src/
git commit -m "feat(realty-g): GroupWithdrawRequest carries required recipient (AVATAR | SL_GROUP)"
```

---

## Task 16: `RealtyGroupWalletService.withdraw` SL_GROUP branch + new exceptions

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupWalletService.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/wallet/exception/SlGroupNotRegisteredException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/wallet/exception/SlGroupRegistrationSuspendedException.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/RealtyGroupSlGroupRepository.java` (add a `findCurrentByRealtyGroupId` helper if absent)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/exception/RealtyExceptionHandler.java`
- Modify: `backend/src/test/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupWalletServiceWithdrawTest.java` (or create if absent)

- [ ] **Step 1: Write a failing test for each SL_GROUP code path**

Test file: `backend/src/test/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupWalletServiceSlGroupWithdrawTest.java`

```java
package com.slparcelauctions.backend.realty.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.escrow.command.TerminalCommand;
import com.slparcelauctions.backend.escrow.command.TerminalCommandAction;
import com.slparcelauctions.backend.escrow.command.TerminalCommandRepository;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.moderation.RealtyGroupSuspension;
import com.slparcelauctions.backend.realty.moderation.RealtyGroupSuspensionRepository;
import com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroup;
import com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroupRepository;
import com.slparcelauctions.backend.realty.wallet.dto.GroupWithdrawRecipient;
import com.slparcelauctions.backend.realty.wallet.exception.SlGroupNotRegisteredException;
import com.slparcelauctions.backend.realty.wallet.exception.SlGroupRegistrationSuspendedException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

@SpringBootTest
@Transactional
class RealtyGroupWalletServiceSlGroupWithdrawTest {

    @Autowired RealtyGroupWalletService service;
    @Autowired RealtyGroupRepository groups;
    @Autowired RealtyGroupSlGroupRepository slGroups;
    @Autowired RealtyGroupSuspensionRepository suspensions;
    @Autowired TerminalCommandRepository terminalCommands;
    @Autowired UserRepository users;

    @Test
    void withdraw_toSlGroup_enqueuesWithdrawGroupCommand_whenRegistrationVerified() {
        User leader = users.save(User.builder().username("sl-wd-a")
                .walletTermsAcceptedAt(OffsetDateTime.now()).build());
        RealtyGroup g = groups.save(RealtyGroup.builder()
                .name("sl-wd-a").slug("sl-wd-a").leaderId(leader.getId()).balanceLindens(5000L).build());
        UUID slGroupUuid = UUID.randomUUID();
        slGroups.save(RealtyGroupSlGroup.builder()
                .realtyGroupId(g.getId()).slGroupUuid(slGroupUuid)
                .verified(true).verifiedAt(OffsetDateTime.now()).build());

        RealtyGroupWalletService.WithdrawResult result = service.withdraw(
                g.getId(), 1000L, UUID.randomUUID(), leader.getId(),
                GroupWithdrawRecipient.SL_GROUP);

        TerminalCommand cmd = terminalCommands.findById(result.queueId()).orElseThrow();
        assertThat(cmd.getAction()).isEqualTo(TerminalCommandAction.WITHDRAW_GROUP);
        assertThat(cmd.getRecipientUuid()).isEqualTo(slGroupUuid.toString());
    }

    @Test
    void withdraw_toSlGroup_throwsSlGroupNotRegistered_whenNoRegistrationExists() {
        User leader = users.save(User.builder().username("sl-wd-b")
                .walletTermsAcceptedAt(OffsetDateTime.now()).build());
        RealtyGroup g = groups.save(RealtyGroup.builder()
                .name("sl-wd-b").slug("sl-wd-b").leaderId(leader.getId()).balanceLindens(5000L).build());

        assertThatThrownBy(() -> service.withdraw(g.getId(), 1000L, UUID.randomUUID(),
                leader.getId(), GroupWithdrawRecipient.SL_GROUP))
                .isInstanceOf(SlGroupNotRegisteredException.class);
    }

    @Test
    void withdraw_toSlGroup_throwsSlGroupRegistrationSuspended_whenRealtyGroupHasActiveSuspension() {
        User leader = users.save(User.builder().username("sl-wd-c")
                .walletTermsAcceptedAt(OffsetDateTime.now()).build());
        RealtyGroup g = groups.save(RealtyGroup.builder()
                .name("sl-wd-c").slug("sl-wd-c").leaderId(leader.getId()).balanceLindens(5000L).build());
        slGroups.save(RealtyGroupSlGroup.builder()
                .realtyGroupId(g.getId()).slGroupUuid(UUID.randomUUID())
                .verified(true).verifiedAt(OffsetDateTime.now()).build());
        suspensions.save(RealtyGroupSuspension.builder()
                .realtyGroupId(g.getId())
                .issuedByAdminUserId(leader.getId())
                .reason("test")
                .issuedAt(OffsetDateTime.now())
                .build());

        assertThatThrownBy(() -> service.withdraw(g.getId(), 1000L, UUID.randomUUID(),
                leader.getId(), GroupWithdrawRecipient.SL_GROUP))
                .isInstanceOf(SlGroupRegistrationSuspendedException.class);
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails**

```bash
./mvnw test -Dtest=RealtyGroupWalletServiceSlGroupWithdrawTest
```

Expected: compile failure — service `withdraw` signature does not accept a recipient; new exceptions do not exist.

- [ ] **Step 3: Create the two new exception classes**

`backend/src/main/java/com/slparcelauctions/backend/realty/wallet/exception/SlGroupNotRegisteredException.java`:

```java
package com.slparcelauctions.backend.realty.wallet.exception;

import java.util.UUID;

/**
 * Sub-project G §7.3 -- caller asked to withdraw to {@code SL_GROUP} but the
 * realty group has no currently-registered SL group (no row, or the registration
 * was force-unregistered). Surfaced as 422.
 */
public class SlGroupNotRegisteredException extends RuntimeException {
    private final UUID realtyGroupPublicId;

    public SlGroupNotRegisteredException(UUID realtyGroupPublicId) {
        super("Realty group " + realtyGroupPublicId + " has no registered SL group");
        this.realtyGroupPublicId = realtyGroupPublicId;
    }

    public UUID getRealtyGroupPublicId() { return realtyGroupPublicId; }
}
```

`backend/src/main/java/com/slparcelauctions/backend/realty/wallet/exception/SlGroupRegistrationSuspendedException.java`:

```java
package com.slparcelauctions.backend.realty.wallet.exception;

import java.util.UUID;

/**
 * Sub-project G §7.3 -- the realty group has an SL group registration but the
 * realty group itself is currently SUSPENDED. Withdraw to {@code SL_GROUP} is
 * blocked; withdraw to the leader's avatar is still available. Drift alone
 * (registration has {@code drift_reason != null} but the realty group is not
 * suspended) does NOT trip this exception -- §7.3 explicitly allows that case.
 * Surfaced as 422.
 */
public class SlGroupRegistrationSuspendedException extends RuntimeException {
    private final UUID realtyGroupPublicId;

    public SlGroupRegistrationSuspendedException(UUID realtyGroupPublicId) {
        super("Realty group " + realtyGroupPublicId + " is suspended; SL-group withdraw blocked");
        this.realtyGroupPublicId = realtyGroupPublicId;
    }

    public UUID getRealtyGroupPublicId() { return realtyGroupPublicId; }
}
```

- [ ] **Step 4: Add the repo helpers**

Edit `RealtyGroupSlGroupRepository.java` — append a "current registered SL group for a realty group" helper that excludes force-unregistered rows:

```java
    /**
     * Sub-project G §7.3 -- the realty group's currently-registered SL group,
     * if any. Excludes force-unregistered rows ({@code unregistered_at IS NOT
     * NULL}). UNIQUE(sl_group_uuid) and the per-realty-group registration
     * convention guarantee at most one such row per realty group at a time.
     */
    @Query("""
        SELECT r FROM RealtyGroupSlGroup r
         WHERE r.realtyGroupId = :realtyGroupId
           AND r.verified = true
           AND r.unregisteredAt IS NULL
         ORDER BY r.verifiedAt DESC
        """)
    java.util.List<RealtyGroupSlGroup> findCurrentRegisteredForRealtyGroup(
            @Param("realtyGroupId") Long realtyGroupId);
```

(Returning a list rather than `Optional` keeps the query safe against the unlikely race where two rows are momentarily verified — caller takes the first.)

- [ ] **Step 5: Extend `RealtyGroupWalletService.withdraw`**

Edit `RealtyGroupWalletService.java`:

1. Inject `RealtyGroupSlGroupRepository slGroupRepository` and `RealtyGroupSuspensionRepository suspensionRepository` (add the constructor fields via Lombok's `@RequiredArgsConstructor` — just declare the `private final` fields).
2. Change the method signature to accept `GroupWithdrawRecipient recipient`.
3. Branch on recipient at the top of the method (after the idempotency-replay check).

Replacement body sketch (only the diff lines around the existing AVATAR flow are shown — keep the rest of the method shape):

```java
    @Transactional
    public WithdrawResult withdraw(Long groupId, long amount, UUID idempotencyKey,
            Long callerUserId, GroupWithdrawRecipient recipient) {
        realtyGroupGuard.requireGroupCanOperate(groupId);
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive: " + amount);
        }
        if (recipient == null) {
            throw new IllegalArgumentException("recipient must be non-null");
        }
        String idemStr = idempotencyKey.toString();
        Optional<RealtyGroupLedgerEntry> replay = ledgerRepository.findByIdempotencyKey(idemStr);
        if (replay.isPresent()) {
            TerminalCommand prior = terminalCommandRepository
                .findByIdempotencyKey("GWAL-" + replay.get().getId())
                .orElseThrow(() -> new IllegalStateException(
                    "ledger row exists but terminal command missing for GWAL-" + replay.get().getId()));
            return new WithdrawResult(prior.getId(), 60);
        }

        RealtyGroup group = groupRepository.findByIdForUpdate(groupId).orElseThrow();
        User leader = userRepository.findById(group.getLeaderId())
            .orElseThrow(() -> new IllegalStateException(
                "group " + group.getPublicId() + " leader id " + group.getLeaderId() + " missing"));

        if (leader.getWalletTermsAcceptedAt() == null) {
            throw new com.slparcelauctions.backend.realty.wallet.exception
                .LeaderTermsNotAcceptedException(leader.getPublicId());
        }
        if ((leader.getWalletFrozenAt() != null)
                || (leader.getBannedFromListing() != null && leader.getBannedFromListing())) {
            throw new com.slparcelauctions.backend.realty.wallet.exception.LeaderFrozenException();
        }
        long available = group.availableLindens();
        if (available < amount) {
            throw new com.slparcelauctions.backend.realty.wallet.exception
                .InsufficientGroupBalanceException(available, amount);
        }

        // §7.3 -- resolve recipient destination + descriptor before balance mutation.
        String recipientUuid;
        TerminalCommandAction action;
        String description;
        if (recipient == GroupWithdrawRecipient.AVATAR) {
            recipientUuid = leader.getSlAvatarUuid().toString();
            action = TerminalCommandAction.WITHDRAW;
            description = "to leader avatar";
        } else {
            java.util.List<com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroup> regs =
                    slGroupRepository.findCurrentRegisteredForRealtyGroup(groupId);
            if (regs.isEmpty()) {
                throw new com.slparcelauctions.backend.realty.wallet.exception
                    .SlGroupNotRegisteredException(group.getPublicId());
            }
            if (suspensionRepository.existsActiveForGroup(groupId)) {
                throw new com.slparcelauctions.backend.realty.wallet.exception
                    .SlGroupRegistrationSuspendedException(group.getPublicId());
            }
            com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroup reg = regs.get(0);
            recipientUuid = reg.getSlGroupUuid().toString();
            action = TerminalCommandAction.WITHDRAW_GROUP;
            description = "to SL group " + (reg.getSlGroupName() == null ? reg.getSlGroupUuid() : reg.getSlGroupName());
        }

        long newBalance = group.getBalanceLindens() - amount;
        group.setBalanceLindens(newBalance);
        clearDormancyOnActivity(group);
        groupRepository.save(group);

        RealtyGroupLedgerEntry queuedEntry = ledgerRepository.save(RealtyGroupLedgerEntry.builder()
            .groupId(groupId)
            .entryType(RealtyGroupLedgerEntryType.WITHDRAW_QUEUED)
            .amount(amount)
            .balanceAfter(newBalance)
            .reservedAfter(group.getReservedLindens())
            .actorUserId(callerUserId)
            .idempotencyKey(idemStr)
            .refType("TERMINAL_COMMAND")
            .description(description)
            .build());

        TerminalCommand cmd = terminalCommandRepository.save(TerminalCommand.builder()
            .action(action)
            .purpose(TerminalCommandPurpose.GROUP_WALLET_WITHDRAWAL)
            .recipientUuid(recipientUuid)
            .amount(amount)
            .status(TerminalCommandStatus.QUEUED)
            .idempotencyKey("GWAL-" + queuedEntry.getId())
            .realtyGroupId(groupId)
            .nextAttemptAt(OffsetDateTime.now(clock))
            .attemptCount(0)
            .requiresManualReview(false)
            .build());

        broadcastPublisher.publish(group.getPublicId(),
            newBalance, group.getReservedLindens(), group.availableLindens(),
            RealtyGroupLedgerEntryType.WITHDRAW_QUEUED.name(), queuedEntry.getPublicId());

        log.info("group withdraw queued: groupId={}, amount={}, recipient={}, action={}, queueId={}",
            groupId, amount, recipient, action, cmd.getId());
        return new WithdrawResult(cmd.getId(), 60);
    }
```

If `RealtyGroupSuspensionRepository.existsActiveForGroup(Long)` does not exist, add it (matches the F pattern of "active = `lifted_at IS NULL AND (expires_at IS NULL OR expires_at > now)`"). The realty-group guard at the top of `withdraw` already blocks suspended groups for the operate-permission gate — re-checking explicitly here keeps the semantic ("SL_GROUP path requires the realty group to be operable") legible and survives any future loosening of the guard.

Update `RealtyGroupWalletController.withdraw` to pass `req.recipient()` into the service.

- [ ] **Step 6: Map the new exceptions in `RealtyExceptionHandler`**

```java
    @ExceptionHandler(SlGroupNotRegisteredException.class)
    public ProblemDetail handleSlGroupNotRegistered(
            SlGroupNotRegisteredException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        pd.setTitle("SL Group Not Registered");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "SL_GROUP_NOT_REGISTERED");
        pd.setProperty("realtyGroupPublicId", e.getRealtyGroupPublicId().toString());
        return pd;
    }

    @ExceptionHandler(SlGroupRegistrationSuspendedException.class)
    public ProblemDetail handleSlGroupRegistrationSuspended(
            SlGroupRegistrationSuspendedException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        pd.setTitle("SL Group Registration Suspended");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "SL_GROUP_REGISTRATION_SUSPENDED");
        pd.setProperty("realtyGroupPublicId", e.getRealtyGroupPublicId().toString());
        return pd;
    }
```

Add imports at the top:

```java
import com.slparcelauctions.backend.realty.wallet.exception.SlGroupNotRegisteredException;
import com.slparcelauctions.backend.realty.wallet.exception.SlGroupRegistrationSuspendedException;
```

- [ ] **Step 7: Run the tests + full suite**

```bash
./mvnw test -Dtest=RealtyGroupWalletServiceSlGroupWithdrawTest
./mvnw test
```

Expected: green.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupWalletService.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupWalletController.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/wallet/exception/SlGroupNotRegisteredException.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/wallet/exception/SlGroupRegistrationSuspendedException.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/RealtyGroupSlGroupRepository.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/exception/RealtyExceptionHandler.java \
        backend/src/test/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupWalletServiceSlGroupWithdrawTest.java
git commit -m "feat(realty-g): SL_GROUP withdraw destination -- enqueues WITHDRAW_GROUP TerminalCommand"
```

---

## Task 17: Bot `WithdrawGroupHandler` (LibreMetaverse `GiveGroupMoney`)

**Files:**
- Create: `bot/src/Slpa.Bot/Tasks/WithdrawGroupHandler.cs`
- Modify: `bot/src/Slpa.Bot/Tasks/TaskLoop.cs`
- Modify: `bot/src/Slpa.Bot/Backend/Models/BotTaskType.cs`
- Modify: `bot/src/Slpa.Bot/Backend/Models/BotTaskResponse.cs`
- Modify: `bot/src/Slpa.Bot/Sl/IBotSession.cs` (add `GiveGroupMoney` to the session interface so the fake can intercept it)
- Create: `bot/tests/Slpa.Bot.Tests/Tasks/WithdrawGroupHandlerTests.cs`

**Note on existing state:** the bot's `Tasks/` directory has only `VerifyHandler.cs`, `MonitorHandler.cs`, and `TaskLoop.cs` today. The user prompt referenced a `WithdrawHandler.cs` to model from, but no such file exists — `VerifyHandler` is the de-facto pattern reference. Withdraw flows pre-G run through the LSL terminal, not the bot; this task adds the first bot-fulfilled withdrawal path (SL-group destination).

- [ ] **Step 1: Extend the bot task model**

Edit `bot/src/Slpa.Bot/Backend/Models/BotTaskType.cs`:

```csharp
namespace Slpa.Bot.Backend.Models;

public enum BotTaskType { VERIFY, MONITOR_AUCTION, MONITOR_ESCROW, WITHDRAW_GROUP }
```

Edit `bot/src/Slpa.Bot/Backend/Models/BotTaskResponse.cs` — append two optional fields (`RecipientUuid`, `AmountL`) so the same record carries verify-shape, monitor-shape, and withdraw-shape payloads. Defaulting to `null` keeps the wire compatible with the existing claim endpoint until the backend adds the new fields.

```csharp
namespace Slpa.Bot.Backend.Models;

public sealed record BotTaskResponse(
    long Id,
    BotTaskType TaskType,
    BotTaskStatus Status,
    long AuctionId,
    long? EscrowId,
    Guid ParcelUuid,
    string? RegionName,
    double? PositionX,
    double? PositionY,
    double? PositionZ,
    long SentinelPrice,
    Guid? ExpectedOwnerUuid,
    Guid? ExpectedAuthBuyerUuid,
    long? ExpectedSalePriceLindens,
    Guid? ExpectedWinnerUuid,
    Guid? ExpectedSellerUuid,
    long? ExpectedMaxSalePriceLindens,
    Guid? AssignedBotUuid,
    string? FailureReason,
    DateTimeOffset? NextRunAt,
    int? RecurrenceIntervalSeconds,
    DateTimeOffset CreatedAt,
    DateTimeOffset? CompletedAt,
    Guid? RecipientUuid = null,
    long? AmountL = null);
```

- [ ] **Step 2: Add `GiveGroupMoney` to `IBotSession`**

Edit `bot/src/Slpa.Bot/Sl/IBotSession.cs` — append:

```csharp
    /// <summary>
    /// Issues a Self.GiveGroupMoney transfer from the logged-in avatar to the
    /// supplied SL group UUID. Returns synchronously -- LibreMetaverse fires the
    /// transfer and the caller has no acknowledgement to await locally; success
    /// vs. failure surfaces via the in-world money-tracker callback, which the
    /// backend ingests separately. Sub-project G §7.4.
    /// </summary>
    void GiveGroupMoney(Guid slGroupUuid, int amountL, string memo);
```

Update the production `BotSession` implementation to call `Client.Self.GiveGroupMoney(slGroupUuid, amountL, memo);` and the test fake `FakeBotSession` (or whichever IBotSession test double the existing suite uses) to capture the call arguments for assertion.

- [ ] **Step 3: Write the handler test**

`bot/tests/Slpa.Bot.Tests/Tasks/WithdrawGroupHandlerTests.cs`:

```csharp
using System;
using System.Threading;
using System.Threading.Tasks;
using FluentAssertions;
using Microsoft.Extensions.Logging.Abstractions;
using Slpa.Bot.Backend;
using Slpa.Bot.Backend.Models;
using Slpa.Bot.Sl;
using Slpa.Bot.Tasks;
using Slpa.Bot.Tests.Fakes;
using Xunit;

namespace Slpa.Bot.Tests.Tasks;

public sealed class WithdrawGroupHandlerTests
{
    [Fact]
    public async Task HandleAsync_calls_GiveGroupMoney_with_recipient_amount_and_memo_when_task_is_well_formed()
    {
        var fakeSession = new FakeBotSession();
        var fakeBackend = new FakeBackendClient();
        var handler = new WithdrawGroupHandler(fakeSession, fakeBackend, NullLogger<WithdrawGroupHandler>.Instance);

        var recipient = Guid.NewGuid();
        var task = TestTasks.WithdrawGroup(id: 42, recipient: recipient, amountL: 1500);

        await handler.HandleAsync(task, CancellationToken.None);

        fakeSession.GiveGroupMoneyCalls.Should().ContainSingle();
        var call = fakeSession.GiveGroupMoneyCalls[0];
        call.GroupUuid.Should().Be(recipient);
        call.AmountL.Should().Be(1500);
        call.Memo.Should().Be("SLPA group wallet withdraw — ref 42");
    }

    [Fact]
    public async Task HandleAsync_skips_GiveGroupMoney_when_recipient_or_amount_is_missing()
    {
        var fakeSession = new FakeBotSession();
        var fakeBackend = new FakeBackendClient();
        var handler = new WithdrawGroupHandler(fakeSession, fakeBackend, NullLogger<WithdrawGroupHandler>.Instance);

        var task = TestTasks.WithdrawGroup(id: 43, recipient: null, amountL: 1500);
        await handler.HandleAsync(task, CancellationToken.None);

        fakeSession.GiveGroupMoneyCalls.Should().BeEmpty();
    }
}
```

(`TestTasks` and `FakeBotSession` extension is a small fixture helper; if a fixture builder doesn't already live in the test project, create one. The fake's `GiveGroupMoneyCalls` list captures `(GroupUuid, AmountL, Memo)` tuples.)

- [ ] **Step 4: Run the test to confirm it fails**

```bash
cd bot && dotnet test --filter "FullyQualifiedName~WithdrawGroupHandlerTests"
```

Expected: compile failure — `WithdrawGroupHandler` does not exist.

- [ ] **Step 5: Create the handler**

`bot/src/Slpa.Bot/Tasks/WithdrawGroupHandler.cs`:

```csharp
using System;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Extensions.Logging;
using Slpa.Bot.Backend;
using Slpa.Bot.Backend.Models;
using Slpa.Bot.Sl;

namespace Slpa.Bot.Tasks;

/// <summary>
/// Sub-project G §7.4 -- handles WITHDRAW_GROUP tasks by issuing a
/// <c>Self.GiveGroupMoney</c> transfer from the logged-in avatar to the
/// registered SL group. The backend has already debited the realty-group
/// wallet and written the WITHDRAW_QUEUED ledger row; the bot's role is
/// the in-world transfer itself. Success vs. failure surfaces via the
/// money-tracker callback path, not via this handler's return value.
/// </summary>
public sealed class WithdrawGroupHandler
{
    private readonly IBotSession _session;
    private readonly IBackendClient _backend;
    private readonly ILogger<WithdrawGroupHandler> _log;

    public WithdrawGroupHandler(
        IBotSession session,
        IBackendClient backend,
        ILogger<WithdrawGroupHandler> log)
    {
        _session = session;
        _backend = backend;
        _log = log;
    }

    public Task HandleAsync(BotTaskResponse task, CancellationToken ct)
    {
        if (task.RecipientUuid is null || task.AmountL is null)
        {
            _log.LogWarning(
                "WITHDRAW_GROUP {TaskId} missing RecipientUuid or AmountL; skipping",
                task.Id);
            return Task.CompletedTask;
        }

        var memo = $"SLPA group wallet withdraw — ref {task.Id}";
        var amountL = (int)task.AmountL.Value;
        _session.GiveGroupMoney(task.RecipientUuid.Value, amountL, memo);
        _log.LogInformation(
            "WITHDRAW_GROUP {TaskId} issued GiveGroupMoney to {GroupUuid}, L${Amount}",
            task.Id, task.RecipientUuid.Value, amountL);
        return Task.CompletedTask;
    }
}
```

- [ ] **Step 6: Wire the handler into `TaskLoop`**

Edit `bot/src/Slpa.Bot/Tasks/TaskLoop.cs`:

1. Add a `Func<WithdrawGroupHandler> _withdrawGroup` field plus matching constructor parameters in both the production and the test ctor.
2. Extend `DispatchAsync`:

```csharp
    private Task DispatchAsync(BotTaskResponse task, CancellationToken ct)
    {
        return task.TaskType switch
        {
            BotTaskType.VERIFY => _verify().HandleAsync(task, ct),
            BotTaskType.MONITOR_AUCTION => _monitor().HandleAsync(task, ct),
            BotTaskType.MONITOR_ESCROW => _monitor().HandleAsync(task, ct),
            BotTaskType.WITHDRAW_GROUP => _withdrawGroup().HandleAsync(task, ct),
            _ => Task.CompletedTask
        };
    }
```

3. Register `WithdrawGroupHandler` in the DI composition root (typically `Program.cs`) so the singleton resolves.

- [ ] **Step 7: Run the test to confirm it passes + full bot suite stays green**

```bash
cd bot && dotnet test
```

Expected: green.

- [ ] **Step 8: Commit**

```bash
git add bot/src/Slpa.Bot/Tasks/WithdrawGroupHandler.cs \
        bot/src/Slpa.Bot/Tasks/TaskLoop.cs \
        bot/src/Slpa.Bot/Backend/Models/BotTaskType.cs \
        bot/src/Slpa.Bot/Backend/Models/BotTaskResponse.cs \
        bot/src/Slpa.Bot/Sl/IBotSession.cs \
        bot/src/Slpa.Bot/Sl/ \
        bot/tests/Slpa.Bot.Tests/Tasks/WithdrawGroupHandlerTests.cs \
        bot/tests/Slpa.Bot.Tests/Fakes/
git commit -m "feat(realty-g): bot WithdrawGroupHandler -- Self.GiveGroupMoney for SL-group withdrawals"
```

---

## Task 18: `GroupWalletDto.leaderTermsAcceptedAt` + mapper extension [parallel-safe]

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/wallet/dto/GroupWalletDto.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/wallet/dto/GroupWalletDtoMapper.java`
- Modify: every caller that builds `GroupWalletDto` (`RealtyGroupWalletController.getWallet`, `AdminRealtyGroupWalletService.adjust`)
- Modify: `backend/src/test/java/com/slparcelauctions/backend/realty/wallet/dto/GroupWalletDtoMapperTest.java` (or create if absent)

- [ ] **Step 1: Write a failing test asserting the field flows through**

Test file: `backend/src/test/java/com/slparcelauctions/backend/realty/wallet/dto/GroupWalletDtoMapperLeaderTermsTest.java`

```java
package com.slparcelauctions.backend.realty.wallet.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.user.User;

@SpringBootTest
@Transactional
class GroupWalletDtoMapperLeaderTermsTest {

    @Autowired GroupWalletDtoMapper mapper;

    @Test
    void buildWalletDto_carries_leaderWalletTermsAcceptedAt_when_set() {
        Instant ts = Instant.parse("2026-04-01T10:00:00Z");
        User leader = User.builder().username("leader-a")
                .walletTermsAcceptedAt(OffsetDateTime.ofInstant(ts, java.time.ZoneOffset.UTC))
                .build();

        GroupWalletDto dto = mapper.toWalletDto(1000L, 100L, 900L, leader, List.of());

        assertThat(dto.leaderTermsAcceptedAt()).isEqualTo(ts);
    }

    @Test
    void buildWalletDto_carries_null_when_leaderHasNotAcceptedTerms() {
        User leader = User.builder().username("leader-b").build();

        GroupWalletDto dto = mapper.toWalletDto(0L, 0L, 0L, leader, List.of());

        assertThat(dto.leaderTermsAcceptedAt()).isNull();
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails**

```bash
./mvnw test -Dtest=GroupWalletDtoMapperLeaderTermsTest
```

Expected: compile failure — `GroupWalletDto` has no `leaderTermsAcceptedAt` field; `GroupWalletDtoMapper.toWalletDto` does not exist.

- [ ] **Step 3: Add the field**

Edit `GroupWalletDto.java`:

```java
package com.slparcelauctions.backend.realty.wallet.dto;

import java.time.Instant;
import java.util.List;

/**
 * Response DTO for GET /api/v1/realty/groups/{publicId}/wallet.
 * Spec §5.1 / §5.6 (D); {@code leaderTermsAcceptedAt} added in sub-project G §7.5
 * so the frontend leader-terms-block banner can conditionally render.
 *
 * <p>{@code leaderTermsAcceptedAt} is the timestamp the group's leader accepted
 * the wallet Terms of Service (sourced from {@link
 * com.slparcelauctions.backend.user.User#getWalletTermsAcceptedAt()}). Null
 * means the leader has NOT accepted terms; the banner shows on that condition.
 */
public record GroupWalletDto(
        long balance,
        long reserved,
        long available,
        Instant leaderTermsAcceptedAt,
        List<GroupLedgerEntryDto> recentLedger) {
}
```

- [ ] **Step 4: Add a builder on the mapper**

Edit `GroupWalletDtoMapper.java` — add the new helper:

```java
    /**
     * Sub-project G §7.5 -- assemble the full wallet DTO, copying the leader's
     * wallet-terms acceptance timestamp from the supplied {@link User} row.
     */
    public GroupWalletDto toWalletDto(long balance, long reserved, long available,
            User leader, List<RealtyGroupLedgerEntry> recentLedger) {
        java.time.Instant leaderTerms = (leader == null || leader.getWalletTermsAcceptedAt() == null)
                ? null
                : leader.getWalletTermsAcceptedAt().toInstant();
        return new GroupWalletDto(balance, reserved, available, leaderTerms, toDtos(recentLedger));
    }
```

- [ ] **Step 5: Update every caller**

`RealtyGroupWalletController.getWallet` — load the leader user (already implicitly available via `g.getLeaderId()`) and call the new builder:

```java
        User leader = userRepository.findById(g.getLeaderId()).orElseThrow();
        return mapper.toWalletDto(
                g.getBalanceLindens(), g.getReservedLindens(), g.availableLindens(),
                leader, recent);
```

(Inject `UserRepository` if not already present.)

`AdminRealtyGroupWalletService.adjust` — replace the inline `new GroupWalletDto(...)` constructor with the same `mapper.toWalletDto(...)` call.

Any test fixtures that construct `GroupWalletDto` literally (`new GroupWalletDto(...)`) need the new positional parameter — pass `null` for legacy-shape fixtures, or use the new builder.

- [ ] **Step 6: Run tests**

```bash
./mvnw test -Dtest=GroupWalletDtoMapperLeaderTermsTest
./mvnw test
```

Expected: green.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/wallet/dto/GroupWalletDto.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/wallet/dto/GroupWalletDtoMapper.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/wallet/RealtyGroupWalletController.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/wallet/admin/AdminRealtyGroupWalletService.java \
        backend/src/test/java/com/slparcelauctions/backend/realty/wallet/dto/GroupWalletDtoMapperLeaderTermsTest.java
git commit -m "feat(realty-g): GroupWalletDto carries leaderTermsAcceptedAt"
```

---

## Task 19: Frontend admin wallet adjust modal

**Files:**
- Create: `frontend/src/components/admin/realty/AdminWalletAdjustCard.tsx`
- Create: `frontend/src/components/admin/realty/AdminWalletAdjustCard.test.tsx`
- Create: `frontend/src/lib/api/adminRealtyGroupWallet.ts`
- Modify: `frontend/src/app/admin/realty-groups/[publicId]/page.tsx` (wire the card into the existing tab grid)

- [ ] **Step 1: Write a failing Vitest exercising the card**

`frontend/src/components/admin/realty/AdminWalletAdjustCard.test.tsx`:

```tsx
import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";
import { AdminWalletAdjustCard } from "./AdminWalletAdjustCard";

const groupPublicId = "00000000-0000-4000-8000-000000000001";

const server = setupServer(
  http.post(
    `/api/v1/admin/realty-groups/${groupPublicId}/wallet/adjust`,
    async ({ request }) => {
      const body = (await request.json()) as { amount: number; reason: string };
      return HttpResponse.json({
        balance: body.amount,
        reserved: 0,
        available: body.amount,
        leaderTermsAcceptedAt: null,
        recentLedger: [],
      });
    },
  ),
);

beforeAll(() => server.listen());
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe("AdminWalletAdjustCard", () => {
  it("submits a positive amount when direction is + and the form is filled", async () => {
    const onAdjusted = vi.fn();
    render(<AdminWalletAdjustCard publicId={groupPublicId} onAdjusted={onAdjusted} />);

    fireEvent.click(screen.getByRole("button", { name: /credit/i }));
    fireEvent.change(screen.getByLabelText(/amount/i), { target: { value: "2500" } });
    fireEvent.change(screen.getByLabelText(/reason/i), { target: { value: "Compensating bad payout" } });
    fireEvent.click(screen.getByRole("button", { name: /submit adjustment/i }));

    await waitFor(() => expect(onAdjusted).toHaveBeenCalledOnce());
    expect(onAdjusted.mock.calls[0][0].balance).toBe(2500);
  });

  it("flips amount sign when direction toggles to -", async () => {
    const onAdjusted = vi.fn();
    render(<AdminWalletAdjustCard publicId={groupPublicId} onAdjusted={onAdjusted} />);

    fireEvent.click(screen.getByRole("button", { name: /debit/i }));
    fireEvent.change(screen.getByLabelText(/amount/i), { target: { value: "1000" } });
    fireEvent.change(screen.getByLabelText(/reason/i), { target: { value: "Recovering overpaid commission" } });
    fireEvent.click(screen.getByRole("button", { name: /submit adjustment/i }));

    await waitFor(() => expect(onAdjusted).toHaveBeenCalledOnce());
    expect(onAdjusted.mock.calls[0][0].balance).toBe(-1000);
  });

  it("disables submit when reason is blank", () => {
    render(<AdminWalletAdjustCard publicId={groupPublicId} onAdjusted={vi.fn()} />);
    fireEvent.change(screen.getByLabelText(/amount/i), { target: { value: "100" } });
    expect(screen.getByRole("button", { name: /submit adjustment/i })).toBeDisabled();
  });
});
```

- [ ] **Step 2: Run the test to confirm it fails**

```bash
cd frontend && npm test -- AdminWalletAdjustCard
```

Expected: file-not-found failure.

- [ ] **Step 3: Create the API client**

`frontend/src/lib/api/adminRealtyGroupWallet.ts`:

```ts
import { apiFetch } from "@/lib/api/fetch";
import type { GroupWalletDto } from "@/types/realty";

export interface AdminWalletAdjustRequest {
  amount: number;
  reason: string;
}

export function adjustGroupWallet(
  publicId: string,
  req: AdminWalletAdjustRequest,
): Promise<GroupWalletDto> {
  return apiFetch<GroupWalletDto>(
    `/api/v1/admin/realty-groups/${publicId}/wallet/adjust`,
    { method: "POST", body: JSON.stringify(req) },
  );
}
```

(Use whatever the project's canonical fetch wrapper is; if `apiFetch` is named differently, mirror the surrounding files.)

- [ ] **Step 4: Create the card**

`frontend/src/components/admin/realty/AdminWalletAdjustCard.tsx`:

```tsx
"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";
import { Textarea } from "@/components/ui/Textarea";
import { isApiError } from "@/lib/api";
import { adjustGroupWallet } from "@/lib/api/adminRealtyGroupWallet";
import type { GroupWalletDto } from "@/types/realty";

export interface AdminWalletAdjustCardProps {
  /** Group public UUID. */
  publicId: string;
  /** Invoked with the updated wallet DTO on a successful adjustment. */
  onAdjusted: (wallet: GroupWalletDto) => void;
}

type Direction = "credit" | "debit";

/**
 * Sub-project G §7.2 -- admin card on the existing admin realty-group detail
 * page. Posts a signed amount + reason to the new admin wallet adjust endpoint
 * and surfaces the resulting wallet DTO to the parent so the balance card on
 * the same page refreshes inline.
 */
export function AdminWalletAdjustCard({ publicId, onAdjusted }: AdminWalletAdjustCardProps) {
  const [direction, setDirection] = useState<Direction>("credit");
  const [amountText, setAmountText] = useState<string>("");
  const [reason, setReason] = useState<string>("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const reasonTrimmed = reason.trim();
  const parsedAmount = parseInt(amountText, 10);
  const validAmount = Number.isFinite(parsedAmount) && parsedAmount > 0;
  const canSubmit = validAmount && reasonTrimmed.length > 0 && !submitting;

  const handleSubmit = async () => {
    setError(null);
    setSubmitting(true);
    const signed = direction === "credit" ? parsedAmount : -parsedAmount;
    try {
      const dto = await adjustGroupWallet(publicId, { amount: signed, reason: reasonTrimmed });
      onAdjusted(dto);
      setAmountText("");
      setReason("");
    } catch (e) {
      if (isApiError(e)) {
        const code = e.problem.code as string | undefined;
        if (code === "INSUFFICIENT_GROUP_BALANCE") {
          setError("Debit would push balance below zero.");
        } else if (code === "ADMIN_ADJUST_AMOUNT_OUT_OF_RANGE") {
          setError("Amount exceeds the configured sanity ceiling.");
        } else {
          setError(e.message);
        }
      } else {
        setError("Adjustment failed. Please try again.");
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <section
      className="rounded-lg border border-default bg-bg-card p-4"
      data-testid="admin-wallet-adjust-card"
    >
      <header className="mb-3">
        <h3 className="font-medium text-fg text-sm">Admin wallet adjustment</h3>
        <p className="text-xs text-fg-muted mt-1">
          Writes an audit row and a ledger entry; broadcasts the new balance to the group.
        </p>
      </header>
      <div className="flex flex-col gap-3">
        <div className="flex gap-2" role="group" aria-label="Adjustment direction">
          <Button
            variant={direction === "credit" ? "primary" : "secondary"}
            onClick={() => setDirection("credit")}
            aria-pressed={direction === "credit"}
          >
            Credit (+)
          </Button>
          <Button
            variant={direction === "debit" ? "primary" : "secondary"}
            onClick={() => setDirection("debit")}
            aria-pressed={direction === "debit"}
          >
            Debit (-)
          </Button>
        </div>
        <Input
          type="text"
          inputMode="numeric"
          value={amountText}
          onChange={(e) => setAmountText(e.target.value)}
          placeholder="L$ amount"
          aria-label="Amount in L$"
        />
        <Textarea
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          placeholder="Reason (required, max 500 chars)"
          aria-label="Reason"
          maxLength={500}
        />
        {error && <p className="text-sm text-error">{error}</p>}
        <Button
          variant="primary"
          onClick={handleSubmit}
          disabled={!canSubmit}
          loading={submitting}
        >
          Submit adjustment
        </Button>
      </div>
    </section>
  );
}
```

- [ ] **Step 5: Wire into the admin realty-group page**

Edit `frontend/src/app/admin/realty-groups/[publicId]/page.tsx` — import the new card and render it inside the existing admin tabs (e.g. under a "Wallet" section). Pass an `onAdjusted` handler that refreshes the on-page wallet query (TanStack Query `invalidateQueries`).

- [ ] **Step 6: Run tests + verify**

```bash
cd frontend && npm test -- AdminWalletAdjustCard
npm run verify
cd ..
```

Expected: green.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/components/admin/realty/AdminWalletAdjustCard.tsx \
        frontend/src/components/admin/realty/AdminWalletAdjustCard.test.tsx \
        frontend/src/lib/api/adminRealtyGroupWallet.ts \
        frontend/src/app/admin/realty-groups/[publicId]/page.tsx
git commit -m "feat(realty-g): admin wallet adjust card on the realty-group detail page"
```

---

## Task 20: Frontend withdraw modal recipient picker

**Files:**
- Modify: `frontend/src/components/realty/wallet/GroupWithdrawModal.tsx`
- Modify: `frontend/src/components/realty/wallet/GroupWithdrawModal.test.tsx`
- Modify: `frontend/src/lib/api/realtyGroupWallet.ts` (extend the request body type with `recipient: "AVATAR" | "SL_GROUP"`)
- Modify: callers that open the modal — they need to pass the group's current SL group registration + suspension state for the radio's disabled-tooltip logic

- [ ] **Step 1: Write a failing test extending the existing modal test**

Add to `frontend/src/components/realty/wallet/GroupWithdrawModal.test.tsx`:

```tsx
import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";
import { GroupWithdrawModal } from "./GroupWithdrawModal";

const publicId = "00000000-0000-4000-8000-000000000010";
const server = setupServer(
  http.post(`/api/v1/realty/groups/${publicId}/wallet/withdraw`, async ({ request }) => {
    const body = (await request.json()) as { recipient: string };
    return HttpResponse.json({ queueId: 99, estimatedFulfillmentSeconds: 60, echoedRecipient: body.recipient });
  }),
);
beforeAll(() => server.listen());
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe("GroupWithdrawModal recipient picker", () => {
  it("submits with recipient=AVATAR when 'Leader' is selected", async () => {
    const onClose = vi.fn();
    render(
      <GroupWithdrawModal
        open
        onClose={onClose}
        publicId={publicId}
        available={5000}
        slGroup={{ name: "Test Estate", suspended: false }}
      />,
    );
    fireEvent.click(screen.getByLabelText(/leader/i));
    fireEvent.change(screen.getByLabelText(/amount/i), { target: { value: "1000" } });
    fireEvent.click(screen.getByTestId("withdraw-submit"));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  it("submits with recipient=SL_GROUP when 'SL group' is selected", async () => {
    const onClose = vi.fn();
    render(
      <GroupWithdrawModal
        open
        onClose={onClose}
        publicId={publicId}
        available={5000}
        slGroup={{ name: "Test Estate", suspended: false }}
      />,
    );
    fireEvent.click(screen.getByLabelText(/sl group: test estate/i));
    fireEvent.change(screen.getByLabelText(/amount/i), { target: { value: "1000" } });
    fireEvent.click(screen.getByTestId("withdraw-submit"));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  it("disables the SL group radio with a tooltip when suspended", () => {
    render(
      <GroupWithdrawModal
        open
        onClose={vi.fn()}
        publicId={publicId}
        available={5000}
        slGroup={{ name: "Test Estate", suspended: true }}
      />,
    );
    const slRadio = screen.getByLabelText(/sl group: test estate/i) as HTMLInputElement;
    expect(slRadio.disabled).toBe(true);
    expect(screen.getByText(/group registration suspended/i)).toBeInTheDocument();
  });

  it("omits the SL group radio entirely when no SL group is registered", () => {
    render(
      <GroupWithdrawModal
        open
        onClose={vi.fn()}
        publicId={publicId}
        available={5000}
        slGroup={null}
      />,
    );
    expect(screen.queryByLabelText(/sl group/i)).not.toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run the test to confirm it fails**

```bash
cd frontend && npm test -- GroupWithdrawModal
```

Expected: failures referencing the missing `slGroup` prop, missing radio, etc.

- [ ] **Step 3: Extend the API client request type**

Edit `frontend/src/lib/api/realtyGroupWallet.ts`:

```ts
export interface GroupWithdrawRequest {
  amount: number;
  idempotencyKey: string;
  recipient: "AVATAR" | "SL_GROUP";
}
```

(If the type already exists with two fields, add the third — required, no default.)

- [ ] **Step 4: Add the recipient picker to the modal**

Edit `frontend/src/components/realty/wallet/GroupWithdrawModal.tsx`:

1. Add a new prop `slGroup: { name: string; suspended: boolean } | null` — the group's currently-registered SL group (`null` if none).
2. Add internal state `const [recipient, setRecipient] = useState<"AVATAR" | "SL_GROUP">("AVATAR")`.
3. Render a `<fieldset>` with two radios. SL-group radio is omitted entirely when `slGroup === null`; rendered but `disabled` with a tooltip when `slGroup.suspended === true`; selectable otherwise.
4. Pass `recipient` in the withdraw API call body.
5. Map two new server error codes:
   - `SL_GROUP_NOT_REGISTERED` → `"This group has no registered SL group. Choose 'Leader' or register an SL group first."`
   - `SL_GROUP_REGISTRATION_SUSPENDED` → `"SL-group withdrawals are blocked while the realty group is suspended. Choose 'Leader' instead."`

Replace the existing copy block ("Funds will be sent to the group leader's verified SL avatar…") with the picker. Leave the "Available: L$X" header intact.

- [ ] **Step 5: Update callers**

`GroupWalletPage` (the parent that renders the modal) must source the SL-group state. Add a small fetcher hook (likely `useGroupSlGroupRegistration(publicId)`) or extend the existing wallet query payload to include the registered SL group's name + suspended flag; pass it into the modal as the `slGroup` prop.

- [ ] **Step 6: Run tests + verify**

```bash
cd frontend && npm test -- GroupWithdrawModal
npm run verify
cd ..
```

Expected: green.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/components/realty/wallet/GroupWithdrawModal.tsx \
        frontend/src/components/realty/wallet/GroupWithdrawModal.test.tsx \
        frontend/src/components/realty/wallet/GroupWalletPage.tsx \
        frontend/src/lib/api/realtyGroupWallet.ts \
        frontend/src/hooks/realty/
git commit -m "feat(realty-g): withdraw modal recipient picker (Leader avatar | SL group)"
```

---

## Task 21: Frontend `LeaderTermsBlockBanner` condition flip [parallel-safe]

**Files:**
- Modify: `frontend/src/components/realty/wallet/LeaderTermsBlockBanner.tsx`
- Modify: `frontend/src/components/realty/wallet/LeaderTermsBlockBanner.test.tsx`
- Modify: callers that render the banner — `GroupWalletPage` etc.

The banner today already only renders when `leaderTermsAcceptedAt == null` (see the existing component body). The "condition flip" called out by the spec §7.5 / user prompt refers to **how the prop is sourced**: pre-G, callers always pass `null` (the value isn't on the wire), so the banner always shows for everyone. Post-G the value comes from `wallet.leaderTermsAcceptedAt`, so the banner correctly hides for leaders who have accepted terms.

The component code itself is already correct; this task is about (a) the caller wiring + (b) the test that verifies the post-G behavior end-to-end.

- [ ] **Step 1: Write a failing test asserting the banner is gated on the wallet field**

Update `frontend/src/components/realty/wallet/LeaderTermsBlockBanner.test.tsx` — append:

```tsx
import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { LeaderTermsBlockBanner } from "./LeaderTermsBlockBanner";

describe("LeaderTermsBlockBanner gating", () => {
  it("renders when leaderTermsAcceptedAt is null", () => {
    render(<LeaderTermsBlockBanner leaderTermsAcceptedAt={null} />);
    expect(screen.getByTestId("leader-terms-block-banner")).toBeInTheDocument();
  });

  it("renders when leaderTermsAcceptedAt is undefined", () => {
    render(<LeaderTermsBlockBanner leaderTermsAcceptedAt={undefined} />);
    expect(screen.getByTestId("leader-terms-block-banner")).toBeInTheDocument();
  });

  it("does NOT render when leaderTermsAcceptedAt is set", () => {
    render(<LeaderTermsBlockBanner leaderTermsAcceptedAt="2026-04-15T10:00:00Z" />);
    expect(screen.queryByTestId("leader-terms-block-banner")).not.toBeInTheDocument();
  });
});
```

Also add a `GroupWalletPage.test.tsx` assertion (or amend the existing one) that the wallet page passes `wallet.leaderTermsAcceptedAt` into the banner:

```tsx
it("hides the leader-terms banner when the wallet payload reports terms accepted", async () => {
  // MSW: /api/v1/realty/groups/{publicId}/wallet returns leaderTermsAcceptedAt: "2026-04-15T..."
  // ...
  expect(screen.queryByTestId("leader-terms-block-banner")).not.toBeInTheDocument();
});

it("shows the leader-terms banner when the wallet payload reports terms missing", async () => {
  // MSW: /api/v1/realty/groups/{publicId}/wallet returns leaderTermsAcceptedAt: null
  // ...
  expect(await screen.findByTestId("leader-terms-block-banner")).toBeInTheDocument();
});
```

- [ ] **Step 2: Run the test to confirm it fails (or passes — if so, this task is verification-only)**

```bash
cd frontend && npm test -- LeaderTermsBlockBanner GroupWalletPage
```

Expected: failures on the `GroupWalletPage` half if the page is currently passing a hardcoded `null` instead of `wallet.leaderTermsAcceptedAt`.

- [ ] **Step 3: Update the wallet page wiring**

Edit the place(s) that render `<LeaderTermsBlockBanner>` (most likely `GroupWalletPage.tsx` and any embedded wallet card). Replace any hardcoded `leaderTermsAcceptedAt={null}` or `={undefined}` with `leaderTermsAcceptedAt={wallet.leaderTermsAcceptedAt}` reading from the TanStack Query result.

If the frontend `GroupWalletDto` TS type doesn't yet have the field, extend it:

```ts
export interface GroupWalletDto {
  balance: number;
  reserved: number;
  available: number;
  leaderTermsAcceptedAt: string | null;
  recentLedger: GroupLedgerEntryDto[];
}
```

- [ ] **Step 4: Run tests + verify**

```bash
cd frontend && npm test -- LeaderTermsBlockBanner GroupWalletPage
npm run verify
cd ..
```

Expected: green.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/realty/wallet/LeaderTermsBlockBanner.test.tsx \
        frontend/src/components/realty/wallet/GroupWalletPage.tsx \
        frontend/src/components/realty/wallet/GroupWalletPage.test.tsx \
        frontend/src/types/realty.ts
git commit -m "fix(realty-g): LeaderTermsBlockBanner reads wallet.leaderTermsAcceptedAt from server"
```

---

End of Part 2. Part 3 (Section E + Section F polish + Section B frontend cleanup) begins at Task 22.
