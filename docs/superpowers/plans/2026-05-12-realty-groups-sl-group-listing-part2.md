# Realty Groups E — Implementation Plan — Part 2 (Tasks 11–20)

Tasks in this file: about-text polling, expiry cleanup, controllers (REST + LSL), member commission rate, listing case-3 flow, agent commission distributor, escrow routing, Method C check.

Read Part 1 first.

---

## Task 11: `SlGroupAboutTextPollTask` (5-min scheduled job)

Implements spec §7.2. Also wires `recheck` (Task 10 stubbed it).

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/SlGroupAboutTextPollTask.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/RealtyGroupSlGroupService.java` (call into the task helper from `recheck`)
- Test: `backend/src/test/java/com/slparcelauctions/backend/realty/slgroup/SlGroupAboutTextPollTaskTest.java`

- [ ] **Step 1: Implementation**

```java
package com.slparcelauctions.backend.realty.slgroup;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.sl.dto.GroupPageData;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Sub-project E spec §7.2 — polls pending {@link RealtyGroupSlGroup} rows on a 5-minute
 * cadence. For each row that hasn't been polled in the last 5 minutes, fetches the SL group
 * page and looks for the verification code in the About text. On match, flips the row to
 * verified-via-ABOUT_TEXT.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SlGroupAboutTextPollTask {

    static final Duration POLL_INTERVAL = Duration.ofMinutes(5);

    private final RealtyGroupSlGroupRepository repo;
    private final SlWorldApiClient worldApi;
    private final Clock clock;

    @Scheduled(fixedDelay = 5 * 60 * 1000L)
    @Transactional
    public void runScheduled() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime cutoff = now.minus(POLL_INTERVAL);
        List<RealtyGroupSlGroup> due = repo.findDueForAboutTextPoll(now, cutoff);
        for (RealtyGroupSlGroup row : due) {
            try {
                pollOne(row, now);
            } catch (RuntimeException e) {
                log.warn("about-text poll for sl_group {} threw {}; will retry next cycle",
                        row.getSlGroupUuid(), e.toString());
            }
        }
    }

    /**
     * Polls a single row immediately, regardless of throttle. Called by the manual
     * /recheck endpoint and by the scheduled sweep.
     */
    @Transactional
    public RealtyGroupSlGroup pollOne(RealtyGroupSlGroup row, OffsetDateTime now) {
        GroupPageData page;
        try {
            page = worldApi.fetchGroupPage(row.getSlGroupUuid()).block();
        } catch (RuntimeException e) {
            row.setLastPolledAt(now);
            row.setPollAttempts(row.getPollAttempts() + 1);
            return repo.save(row);
        }
        if (page == null) {
            row.setLastPolledAt(now);
            row.setPollAttempts(row.getPollAttempts() + 1);
            return repo.save(row);
        }
        String about = page.aboutText();
        String code = row.getVerificationCode();
        if (about != null && code != null && about.contains(code)) {
            row.setVerified(true);
            row.setVerifiedAt(now);
            row.setVerifiedVia(SlGroupVerifyMethod.ABOUT_TEXT);
            row.setVerificationCode(null);
            if (page.name() != null && row.getSlGroupName() == null) {
                row.setSlGroupName(page.name());
            }
            log.info("SL group verified via ABOUT_TEXT: sl_group_uuid={}", row.getSlGroupUuid());
        } else {
            row.setLastPolledAt(now);
            row.setPollAttempts(row.getPollAttempts() + 1);
        }
        return repo.save(row);
    }
}
```

- [ ] **Step 2: Replace the stub in `recheck` (Task 10)**

In `RealtyGroupSlGroupService.recheck`, replace the no-op body with:

```java
        return aboutTextPoller.pollOne(row, java.time.OffsetDateTime.now(clock));
```

Add `private final SlGroupAboutTextPollTask aboutTextPoller;` to the service's field list (Lombok wires it).

- [ ] **Step 3: Test**

`SlGroupAboutTextPollTaskTest.java`:

```java
// Tests using Mockito:
//   pollOne_matchingAboutText_flipsToVerified
//   pollOne_noMatch_incrementsAttemptAndStampsLastPolledAt
//   pollOne_worldApiFails_doesNotFlip_incrementsAttempt
//   pollOne_emptyPageData_incrementsAttempt
// Use Clock.fixed to assert verifiedAt + lastPolledAt deterministically.
```

(Write each case; the existing service-test idioms in the codebase show the Mockito setup.)

- [ ] **Step 4: Run tests**

Run: `cd backend && ./mvnw test -Dtest=SlGroupAboutTextPollTaskTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/SlGroupAboutTextPollTask.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/RealtyGroupSlGroupService.java \
        backend/src/test/java/com/slparcelauctions/backend/realty/slgroup/SlGroupAboutTextPollTaskTest.java
git commit -m "feat(realty-slgroup): about-text poll task + recheck wiring"
git push
```

---

## Task 12: `SlGroupRegistrationExpiryTask` (hourly cleanup)

Implements spec §7.4.

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/SlGroupRegistrationExpiryTask.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/realty/slgroup/SlGroupRegistrationExpiryTaskTest.java`

- [ ] **Step 1: Implementation**

```java
package com.slparcelauctions.backend.realty.slgroup;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Sub-project E spec §7.4 — deletes pending {@link RealtyGroupSlGroup} rows that have
 * passed their {@code verification_code_expires_at}, freeing the SL group UUID for
 * re-registration.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SlGroupRegistrationExpiryTask {

    private final RealtyGroupSlGroupRepository repo;
    private final Clock clock;

    @Scheduled(fixedDelay = 60 * 60 * 1000L)
    @Transactional
    public void runScheduled() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<RealtyGroupSlGroup> expired = repo.findExpiredPending(now);
        if (expired.isEmpty()) return;
        log.info("Deleting {} expired pending SL-group registration(s)", expired.size());
        for (RealtyGroupSlGroup row : expired) {
            repo.delete(row);
        }
    }
}
```

- [ ] **Step 2: Test**

```java
// Tests:
//   runScheduled_deletesExpiredPendingRows
//   runScheduled_doesNotDeleteVerifiedRows (those have verification_code_expires_at set
//       to whatever — the repo query already filters on verified=false, so the test
//       confirms the query filter holds)
```

- [ ] **Step 3: Run tests**

Run: `cd backend && ./mvnw test -Dtest=SlGroupRegistrationExpiryTaskTest`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/SlGroupRegistrationExpiryTask.java \
        backend/src/test/java/com/slparcelauctions/backend/realty/slgroup/SlGroupRegistrationExpiryTaskTest.java
git commit -m "feat(realty-slgroup): hourly expiry sweep for pending registrations"
git push
```

---

## Task 13: REST controller — `RealtyGroupSlGroupController` (register, list, delete, recheck)

Implements spec §5.1.

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/RealtyGroupSlGroupController.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/exception/RealtyExceptionHandler.java` (map new exceptions)
- Test: `backend/src/test/java/com/slparcelauctions/backend/realty/slgroup/RealtyGroupSlGroupControllerTest.java`

- [ ] **Step 1: Controller**

```java
package com.slparcelauctions.backend.realty.slgroup;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.realty.slgroup.dto.RealtyGroupSlGroupDto;
import com.slparcelauctions.backend.realty.slgroup.dto.RealtyGroupSlGroupDtoMapper;
import com.slparcelauctions.backend.realty.slgroup.dto.RegisterSlGroupRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/realty/groups/{publicId}/sl-groups")
@RequiredArgsConstructor
public class RealtyGroupSlGroupController {

    private final RealtyGroupSlGroupService service;
    private final RealtyGroupSlGroupDtoMapper mapper;

    @PostMapping
    public RealtyGroupSlGroupDto register(
            @PathVariable UUID publicId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody RegisterSlGroupRequest req) {
        RealtyGroupSlGroup row = service.register(principal.userId(), publicId, req.slGroupUuid());
        return mapper.toDto(row);
    }

    @GetMapping
    public List<RealtyGroupSlGroupDto> list(
            @PathVariable UUID publicId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return service.listForGroup(principal.userId(), publicId).stream()
                .map(mapper::toDto)
                .toList();
    }

    @DeleteMapping("/{slGroupPublicId}")
    public ResponseEntity<Void> unregister(
            @PathVariable UUID publicId,
            @PathVariable UUID slGroupPublicId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        service.unregister(principal.userId(), publicId, slGroupPublicId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{slGroupPublicId}/recheck")
    public RealtyGroupSlGroupDto recheck(
            @PathVariable UUID publicId,
            @PathVariable UUID slGroupPublicId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        RealtyGroupSlGroup row = service.recheck(principal.userId(), publicId, slGroupPublicId);
        return mapper.toDto(row);
    }
}
```

- [ ] **Step 2: Exception handler mappings**

In `RealtyExceptionHandler.java`, add `@ExceptionHandler` methods for each E exception class, returning `ProblemDetail` (RFC 7807) with the code constant on the `code` extension and the appropriate HTTP status. Mirror the pattern of the existing handlers (e.g., `RealtyGroupPermissionDeniedException`).

Mappings to add:

| Exception | Status |
|---|---|
| `SlGroupAlreadyRegisteredException` | 409 |
| `SlGroupNotVerifiedException` | 422 |
| `SlGroupVerificationExpiredException` | 410 |
| `SlGroupFounderMismatchException` | 422 |
| `ParcelNotOwnedByRegisteredSlGroupException` | 422 |
| `RegisteredSlGroupHasListingsException` | 409 |
| `SlGroupRegisteredBlocksDissolveException` | 409 |

Also map `BrokerCancelNotApplicableException` (422) in `AuctionExceptionHandler` if it exists, else add it to `RealtyExceptionHandler` if that one covers all realty-adjacent exceptions, or add the mapping wherever cancel-side exceptions live today.

- [ ] **Step 3: Controller test**

`RealtyGroupSlGroupControllerTest.java`:

Use `@WebMvcTest(RealtyGroupSlGroupController.class)` with mocked `RealtyGroupSlGroupService`. Tests:

- `register_200OnHappyPath`
- `register_401WithoutAuth`
- `register_403WithoutPermission` (mock service throws `RealtyGroupPermissionDeniedException`)
- `register_409WhenAlreadyRegistered` (mock service throws `SlGroupAlreadyRegisteredException`)
- `list_200WithRows`
- `unregister_204OnHappyPath`
- `unregister_409WhenActiveListings`
- `recheck_200OnNoOp`

- [ ] **Step 4: Run tests**

Run: `cd backend && ./mvnw test -Dtest=RealtyGroupSlGroupControllerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/RealtyGroupSlGroupController.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/exception/RealtyExceptionHandler.java \
        backend/src/test/java/com/slparcelauctions/backend/realty/slgroup/RealtyGroupSlGroupControllerTest.java
git commit -m "feat(realty-slgroup): REST controller + exception handler mappings"
git push
```

---

## Task 14: `SlGroupVerifyController` — LSL founder-via-terminal callback

Implements spec §5.1 (`POST /sl/sl-group/verify`), §7.3.

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/SlGroupVerifyController.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/dto/SlGroupVerifyRequest.java`
- Modify: `RealtyGroupSlGroupService` to add `handleTerminalCallback`
- Test: `backend/src/test/java/com/slparcelauctions/backend/realty/slgroup/SlGroupVerifyControllerTest.java`

- [ ] **Step 1: DTO**

```java
package com.slparcelauctions.backend.realty.slgroup.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SlGroupVerifyRequest(
        @NotBlank String verificationCode,
        @NotNull UUID founderAvatarUuid
) {}
```

- [ ] **Step 2: Service method**

Inside `RealtyGroupSlGroupService`, add:

```java
    @org.springframework.transaction.annotation.Transactional
    public RealtyGroupSlGroup handleTerminalCallback(String verificationCode, java.util.UUID founderAvatarUuid) {
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now(clock);
        RealtyGroupSlGroup row = repo.findPendingByCode(verificationCode, now)
                .orElseThrow(() -> new com.slparcelauctions.backend.realty.slgroup.exception
                        .SlGroupVerificationExpiredException(null));

        com.slparcelauctions.backend.sl.dto.GroupPageData page;
        try {
            page = worldApi.fetchGroupPage(row.getSlGroupUuid()).block();
        } catch (RuntimeException e) {
            // Treat World API failures as a mismatch; the terminal owner-says + the operator
            // can retry.
            throw new com.slparcelauctions.backend.realty.slgroup.exception
                    .SlGroupFounderMismatchException(founderAvatarUuid, null);
        }
        if (page == null || page.founderUuid() == null) {
            throw new com.slparcelauctions.backend.realty.slgroup.exception
                    .SlGroupFounderMismatchException(founderAvatarUuid, null);
        }
        if (!page.founderUuid().equals(founderAvatarUuid)) {
            throw new com.slparcelauctions.backend.realty.slgroup.exception
                    .SlGroupFounderMismatchException(founderAvatarUuid, page.founderUuid());
        }

        row.setVerified(true);
        row.setVerifiedAt(now);
        row.setVerifiedVia(SlGroupVerifyMethod.FOUNDER_TERMINAL);
        row.setFounderAvatarUuid(founderAvatarUuid);
        row.setVerificationCode(null);
        if (page.name() != null && row.getSlGroupName() == null) {
            row.setSlGroupName(page.name());
        }
        return repo.save(row);
    }
```

- [ ] **Step 3: Controller**

```java
package com.slparcelauctions.backend.realty.slgroup;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.realty.slgroup.dto.SlGroupVerifyRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * LSL callback endpoint for sub-project E spec §7.3 founder-via-terminal verification.
 *
 * <p>Auth: the existing in-world request filter validates the shared-secret HMAC and the
 * {@code X-SecondLife-Owner-Key} header before this handler runs. If anything fails the
 * filter, the request is 401'd upstream.
 */
@RestController
@RequestMapping("/api/v1/sl/sl-group")
@RequiredArgsConstructor
public class SlGroupVerifyController {

    private final RealtyGroupSlGroupService service;

    @PostMapping("/verify")
    public org.springframework.http.ResponseEntity<String> verify(
            @Valid @RequestBody SlGroupVerifyRequest req) {
        service.handleTerminalCallback(req.verificationCode(), req.founderAvatarUuid());
        // Terminal scripts owner-say on OK; the response body is informational.
        return org.springframework.http.ResponseEntity.ok("OK");
    }
}
```

- [ ] **Step 4: Test**

`SlGroupVerifyControllerTest.java`:

```java
// Tests:
//   verify_happyPath_flipsRowVerifiedFounderTerminal
//   verify_codeNotFound_410
//   verify_founderMismatch_422
//   verify_worldApiFails_422
// Use @WebMvcTest with the LSL auth filter disabled (or use the test profile that bypasses it).
```

- [ ] **Step 5: Run tests**

Run: `cd backend && ./mvnw test -Dtest=SlGroupVerifyControllerTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/SlGroupVerifyController.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/dto/SlGroupVerifyRequest.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/RealtyGroupSlGroupService.java \
        backend/src/test/java/com/slparcelauctions/backend/realty/slgroup/SlGroupVerifyControllerTest.java
git commit -m "feat(realty-slgroup): LSL founder-terminal callback /sl/sl-group/verify"
git push
```

---

## Task 15: Member commission rate at invite + edit-permissions

Implements spec §5.4 (invitation, member-edit), §3.2 (DB column already added by V27), §4.4.

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/dto/CreateInvitationRequest.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/dto/UpdatePermissionsRequest.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/service/RealtyGroupInvitationService.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/service/RealtyGroupMembershipService.java` (or wherever edit-permissions lives)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/dto/RealtyGroupDtoMapper.java` (surface the rate)
- Test: extend existing invitation + edit-permissions tests

- [ ] **Step 1: Request DTO fields**

Add `agentCommissionRate` (`BigDecimal`, optional, non-negative) to:
- `CreateInvitationRequest` — leader sets the rate at invite time.
- `UpdatePermissionsRequest` — leader edits per-member rate after invite acceptance.

Pattern:

```java
    @jakarta.validation.constraints.DecimalMin("0.0")
    java.math.BigDecimal agentCommissionRate
```

- [ ] **Step 2: Persist on invitation accept**

In `RealtyGroupInvitationService.accept(...)`, when constructing the new `RealtyGroupMember` row, copy the rate from the invitation row (which captured it at create-time). Add `agent_commission_rate BIGINT/DECIMAL` column to `realty_group_invitations` if not already present (it's not). Use a sub-task migration ALTER if needed, **or** simply hold the rate on the in-memory invitation between create + accept and don't persist on the invitation row.

**Simpler approach (preferred):** put the rate on the invitation entity in a transient sense — meaning add the column to `realty_group_invitations` as well, in this task. Mini-migration:

Append to `V27__realty_group_sl_groups.sql` (in Task 1's migration file — modify it, since V27 hasn't been applied to prod yet on this branch):

```sql
ALTER TABLE realty_group_invitations
  ADD COLUMN agent_commission_rate DECIMAL(5,4) NOT NULL DEFAULT 0;
```

(Add this line at the bottom of the V27 SQL from Task 1. Re-run `BackendApplicationTests.contextLoads` to confirm clean apply.)

Then `RealtyGroupInvitation` entity gains a `agentCommissionRate` field, the invitation-create flow persists it, and `acceptInvitation` copies it onto the new `RealtyGroupMember`.

- [ ] **Step 3: Edit-permissions persists on the member row**

The existing edit-permissions service receives `UpdatePermissionsRequest`. Extend it to also update `member.agentCommissionRate` when the field is present.

- [ ] **Step 4: Surface on member DTOs**

In `RealtyGroupDtoMapper.java` (or wherever member→DTO mapping happens), include `agentCommissionRate` on the response shape.

- [ ] **Step 5: Tests**

Extend `RealtyGroupInvitationServiceTest`:

- `createInvitation_persistsCommissionRate`
- `acceptInvitation_copiesRateOntoMember`

Extend the edit-permissions service test:

- `updatePermissions_updatesCommissionRate`
- `updatePermissions_negativeRate_400` (via validation)

- [ ] **Step 6: Run tests**

Run: `cd backend && ./mvnw test -Dtest=RealtyGroupInvitationServiceTest -Dtest=RealtyGroupMembershipServiceTest`
Expected: PASS for all updated tests.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/dto/CreateInvitationRequest.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/dto/UpdatePermissionsRequest.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/service/ \
        backend/src/main/java/com/slparcelauctions/backend/realty/dto/RealtyGroupDtoMapper.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/RealtyGroupInvitation.java \
        backend/src/main/resources/db/migration/V27__realty_group_sl_groups.sql \
        backend/src/test/java/com/slparcelauctions/backend/realty/
git commit -m "feat(realty-slgroup): per-member commission rate at invite + edit-permissions"
git push
```

---

## Task 16: `/listing-eligible-groups` becomes parcel-aware

Implements spec §5.3 (modified from C).

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/listing/RealtyGroupListingController.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/listing/RealtyGroupListingService.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/listing/ListingEligibleGroupDto.java` (if shape needs to change)
- Test: extend `RealtyGroupListingControllerTest` / `RealtyGroupListingServiceTest`

- [ ] **Step 1: Add `slParcelUuid` to the endpoint signature**

In `RealtyGroupListingController.findEligibleGroupsForCaller`:

```java
    @GetMapping("/realty/me/listing-eligible-groups")
    public List<ListingEligibleGroupDto> findEligible(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam("slParcelUuid") UUID slParcelUuid) {
        return service.findEligibleForParcel(principal.userId(), slParcelUuid);
    }
```

- [ ] **Step 2: Service method**

In `RealtyGroupListingService`:

```java
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public java.util.List<ListingEligibleGroupDto> findEligibleForParcel(Long callerUserId, java.util.UUID slParcelUuid) {
        // Look up parcel ownership; if owner_type != "group", no realty group is eligible.
        com.slparcelauctions.backend.parcel.ParcelLookupService.ParcelLookupResult lookup =
                parcelLookupService.lookup(slParcelUuid);
        if (!"group".equalsIgnoreCase(lookup.response().ownerType())) {
            return java.util.List.of();
        }
        java.util.UUID slOwner = lookup.response().ownerUuid();
        // Find verified registrations matching this SL group, joined with the user's
        // membership and CREATE_LISTING permission.
        return realtyGroupSlGroupRepo.findVerifiedForListingByCallerForParcel(callerUserId, slOwner).stream()
                .map(r -> new ListingEligibleGroupDto(
                        r.getGroupPublicId(),
                        r.getGroupName(),
                        r.getGroupSlug(),
                        r.getGroupLogoUrl(),
                        r.getSlGroupName()))
                .toList();
    }
```

Add the join query to `RealtyGroupSlGroupRepository`:

```java
    @Query("""
        SELECT new com.slparcelauctions.backend.realty.listing.dto.EligibleProjection(
            g.publicId, g.name, g.slug, g.logoUrl, r.slGroupName)
          FROM RealtyGroupSlGroup r
          JOIN RealtyGroup g ON g.id = r.realtyGroupId
          JOIN RealtyGroupMember m ON m.group.id = g.id AND m.user.id = :userId
         WHERE r.verified = true
           AND r.slGroupUuid = :slOwner
           AND g.dissolvedAt IS NULL
           AND ('CREATE_LISTING' = ANY(m.permissions) OR g.leader.id = :userId)
        """)
    java.util.List<EligibleProjection> findVerifiedForListingByCallerForParcel(
            @Param("userId") Long userId, @Param("slOwner") java.util.UUID slOwner);
```

(Adjust based on actual entity field names; `ANY(m.permissions)` is shown here as conceptual — the existing repo probably has a different idiom for permission containment via a native query. Use the same idiom as the existing eligibility query rather than inventing a new one.)

- [ ] **Step 3: Test**

Add to `RealtyGroupListingServiceTest`:

- `findEligibleForParcel_groupOwned_returnsMatchingGroups`
- `findEligibleForParcel_agentOwned_returnsEmpty`
- `findEligibleForParcel_groupOwned_butNoMatchingRegistration_returnsEmpty`

- [ ] **Step 4: Run tests**

Run: `cd backend && ./mvnw test -Dtest=RealtyGroupListingControllerTest -Dtest=RealtyGroupListingServiceTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/listing/ \
        backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/RealtyGroupSlGroupRepository.java \
        backend/src/test/java/com/slparcelauctions/backend/realty/listing/
git commit -m "feat(realty-slgroup): listing-eligible-groups becomes parcel-aware"
git push
```

---

## Task 17: `RealtyGroupListingService.createGroupListing` — case-3 validation + snapshot

Implements spec §8.1.

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/listing/RealtyGroupListingService.java`
- Test: extend `RealtyGroupListingServiceTest`

- [ ] **Step 1: Update `createGroupListing`**

Replace the existing body with:

```java
    @Transactional
    public Auction createGroupListing(Long callerUserId, AuctionCreateRequest req, String ip) {
        UUID groupPublicId = req.listAsGroupPublicId();
        RealtyGroup group = groups.findByPublicIdAndDissolvedAtIsNull(groupPublicId)
                .orElseThrow(() -> new RealtyGroupNotFoundException(groupPublicId));
        authorizer.assertCan(callerUserId, group.getId(), RealtyGroupPermission.CREATE_LISTING);

        // Look up the parcel up-front so we can validate ownership before any side effects.
        com.slparcelauctions.backend.parcel.ParcelLookupService.ParcelLookupResult lookup =
                parcelLookupService.lookup(req.slParcelUuid());
        if (!"group".equalsIgnoreCase(lookup.response().ownerType())) {
            throw new ParcelNotOwnedByRegisteredSlGroupException(
                    req.slParcelUuid(), groupPublicId,
                    "Personal land cannot list under a realty group.");
        }
        java.util.UUID slOwner = lookup.response().ownerUuid();
        com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroup slGroup =
                slGroupRepo.findVerifiedForListing(group.getId(), slOwner)
                        .orElseThrow(() -> new ParcelNotOwnedByRegisteredSlGroupException(
                                req.slParcelUuid(), groupPublicId,
                                "The SL group that owns this parcel is not registered/verified to "
                                        + group.getName()));

        // Snapshot the member's commission rate.
        java.math.BigDecimal commissionRate = memberRepo
                .findCommissionRate(group.getId(), callerUserId)
                .orElse(java.math.BigDecimal.ZERO);

        Auction created = auctionService.create(callerUserId, req, ip);
        created.setRealtyGroupId(group.getId());
        created.setRealtyGroupSlGroupId(slGroup.getId());
        created.setListingAgent(created.getSeller()); // creator is both listing_agent and initial seller_id
        created.setAgentCommissionRate(commissionRate);
        // C-era fields stay NULL for case 3.
        created.setAgentFeeRate(null);
        created.setAgentFeeSplit(null);
        return created;
    }
```

Inject the new dependencies (`slGroupRepo`, `memberRepo`, `parcelLookupService`) via the `@RequiredArgsConstructor` field block.

- [ ] **Step 2: Tests**

Add to `RealtyGroupListingServiceTest`:

- `createGroupListing_case3HappyPath_setsAllFields`
- `createGroupListing_parcelAgentOwned_throws`
- `createGroupListing_parcelGroupOwned_noVerifiedRegistration_throws`
- `createGroupListing_snapshotsMemberCommissionRate`

- [ ] **Step 3: Run tests**

Run: `cd backend && ./mvnw test -Dtest=RealtyGroupListingServiceTest`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/listing/RealtyGroupListingService.java \
        backend/src/test/java/com/slparcelauctions/backend/realty/listing/RealtyGroupListingServiceTest.java
git commit -m "feat(realty-slgroup): case-3 validation + snapshot in createGroupListing"
git push
```

---

## Task 18: `AgentCommissionDistributor` + TerminalCommandService wiring

Implements spec §8.5, §9.6.

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/agentfee/AgentCommissionDistributor.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/command/TerminalCommandService.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/auction/agentfee/AgentCommissionDistributorTest.java`

- [ ] **Step 1: Implementation**

```java
package com.slparcelauctions.backend.auction.agentfee;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.realty.wallet.RealtyGroupWalletService;
import com.slparcelauctions.backend.wallet.WalletService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Sub-project E spec §9 — case-3 payout splitter. Credits the listing agent's wallet with
 * the agent slice and the realty group's wallet with the residual group slice. Runs in the
 * MANDATORY transaction of {@code handleEscrowPayoutSuccess}; never opens its own transaction.
 *
 * <p>For case-1 auctions the legacy {@code AgentFeeDistributor} runs instead (selected by
 * branching on {@code auction.realtyGroupSlGroupId} in {@code TerminalCommandService}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentCommissionDistributor {

    private final WalletService walletService;
    private final RealtyGroupWalletService groupWalletService;

    @Transactional(propagation = Propagation.MANDATORY)
    public void distribute(Auction auction, long finalBid, long platformCommission) {
        if (auction.getRealtyGroupSlGroupId() == null) {
            throw new IllegalArgumentException(
                    "AgentCommissionDistributor called on non-case-3 auction " + auction.getId());
        }
        BigDecimal rate = auction.getAgentCommissionRate();
        if (rate == null) {
            log.warn("auction {} has realty_group_sl_group_id set but agent_commission_rate is null; defaulting to 0",
                    auction.getId());
            rate = BigDecimal.ZERO;
        }
        long earnings = finalBid - platformCommission;
        long agentSlice = BigDecimal.valueOf(earnings)
                .multiply(rate)
                .setScale(0, RoundingMode.FLOOR)
                .longValueExact();
        long groupSlice = earnings - agentSlice;

        if (agentSlice > 0 && auction.getListingAgent() != null) {
            walletService.creditAgentCommission(
                    auction.getListingAgent().getId(), auction.getId(), agentSlice);
        }
        if (groupSlice > 0) {
            groupWalletService.creditPayout(
                    auction.getRealtyGroupId(), auction.getId(), groupSlice);
        }
        log.info("agent-commission distribute: auction={} earnings={} rate={} agentSlice={} groupSlice={}",
                auction.getId(), earnings, rate, agentSlice, groupSlice);
    }
}
```

Add `WalletService.creditAgentCommission(Long userId, Long auctionId, long amount)` — same shape as D's `creditAgentFee`. Add `RealtyGroupWalletService.creditPayout(Long groupId, Long auctionId, long amount)` — appends a `LISTING_PAYOUT` ledger entry type to D's `RealtyGroupLedgerEntryType` enum.

- [ ] **Step 2: Wire branching in `TerminalCommandService.handleEscrowPayoutSuccess`**

Locate the existing call to `agentFeeDistributor.distribute(...)`. Replace with:

```java
if (auction.getRealtyGroupSlGroupId() != null) {
    agentCommissionDistributor.distribute(auction, finalBid, platformCommission);
} else if (auction.getRealtyGroupId() != null) {
    agentFeeDistributor.distribute(auction, finalBid);   // legacy case 1
}
```

Inject `AgentCommissionDistributor` via the constructor field list.

- [ ] **Step 3: Add `LISTING_PAYOUT` to `RealtyGroupLedgerEntryType`**

Append the new value to the enum and update the V27 SQL (or write a small V28) to widen the CHECK constraint on `realty_group_ledger.entry_type` if D used one. Verify with the existing `TerminalCommandPurposeCheckConstraintInitializer` pattern — if D used a startup-time initializer to refresh the constraint, no migration needed.

- [ ] **Step 4: Test**

```java
// Tests:
//   distribute_happyPath_creditsAgentAndGroupCorrectly
//   distribute_zeroRate_allToGroup
//   distribute_fullRate_allToAgent_groupSliceZero  // (e.g., rate=1.0)
//   distribute_throwsIfCase1Auction
```

- [ ] **Step 5: Run tests**

Run: `cd backend && ./mvnw test -Dtest=AgentCommissionDistributorTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/agentfee/AgentCommissionDistributor.java \
        backend/src/main/java/com/slparcelauctions/backend/escrow/command/TerminalCommandService.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/wallet/ \
        backend/src/main/java/com/slparcelauctions/backend/wallet/WalletService.java \
        backend/src/test/java/com/slparcelauctions/backend/auction/agentfee/AgentCommissionDistributorTest.java
git commit -m "feat(realty-slgroup): AgentCommissionDistributor + payout-success branching"
git push
```

---

## Task 19: `EscrowService.createForEndedAuction` — case-3 payout routing

Implements spec §8.5, §9.6.

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowService.java`
- Test: extend `EscrowServiceTest`

- [ ] **Step 1: Branch payout routing**

In `createForEndedAuction`, locate the existing `payoutAmt` computation (D's case-1 subtraction). Replace with a case-3 branch:

```java
long platformCommission = commission.payout(finalBid);   // existing
long payoutAmt;
if (auction.getRealtyGroupSlGroupId() != null) {
    // Case 3: agent slice = floor(earnings * rate); group wallet receives residual via
    // AgentCommissionDistributor. Escrow itself pays out the entire (earnings - agent_slice)
    // to the group wallet's payout target.
    long earnings = finalBid - platformCommission;
    java.math.BigDecimal rate = auction.getAgentCommissionRate() == null
            ? java.math.BigDecimal.ZERO : auction.getAgentCommissionRate();
    long agentSlice = java.math.BigDecimal.valueOf(earnings).multiply(rate)
            .setScale(0, java.math.RoundingMode.FLOOR).longValueExact();
    payoutAmt = earnings - agentSlice;
} else if (auction.getRealtyGroupId() != null) {
    // Case 1 legacy: D's existing computation.
    payoutAmt = commission.payout(finalBid) - nullToZero(auction.getAgentFeeAmt());
} else {
    payoutAmt = commission.payout(finalBid);
}
// ... pass payoutAmt to the Escrow builder unchanged
```

Also: ensure the escrow row's `payoutTargetUuid` (or equivalent) routes to the realty group's wallet for case-3. The group wallet doesn't have an SL avatar UUID — payouts to the group wallet are internal credits, not L$ transfers. Look at how D handles this: D's `RealtyGroupWalletService.creditPayout` (or the equivalent existing method) is the integration point. If escrow is for in-world L$ transfer, the case-3 payout doesn't go through escrow — it's a pure internal ledger credit at the same point as the agent-commission distribution. Re-check D's pattern and follow it.

If case-3 escrow uses a payout to the realty group's leader's avatar as a proxy SL transfer: that doesn't match the spec which says payout goes to the group wallet (an internal balance). The cleaner interpretation: **case-3 payouts skip the SL-side L$ transfer entirely** — the buyer's funds were captured by SLPA at bid-win, and the group wallet credit happens via `AgentCommissionDistributor.distribute(...)` in Task 18. The escrow row may still need to exist for parcel-transfer tracking (because the parcel does need to transfer from the SL group to the buyer), but `payoutAmt = 0` in that case because no L$ goes out to a personal avatar.

The implementer should decide between two shapes during implementation:

(a) Escrow row has `payoutAmt = 0` for case-3; all L$ routing happens via internal ledger entries.

(b) Escrow row has `payoutAmt = payout_to_group_wallet`; treat the group wallet as a virtual "payee" with no SL avatar UUID, then `AgentCommissionDistributor` only handles the agent slice.

Pick (a) for simplicity unless D's existing model forces (b). Verify against D's commit history and reconcile.

- [ ] **Step 2: Test**

Extend `EscrowServiceTest`:

- `createForEndedAuction_case3_payoutAmtIsZero_orPayoutToGroupWallet` (whichever shape is chosen)
- `createForEndedAuction_case1Legacy_payoutAmtMatchesD`
- `createForEndedAuction_individual_payoutAmtUnchanged`

- [ ] **Step 3: Run tests**

Run: `cd backend && ./mvnw test -Dtest=EscrowServiceTest`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowService.java \
        backend/src/test/java/com/slparcelauctions/backend/escrow/EscrowServiceTest.java
git commit -m "feat(realty-slgroup): case-3 payout routing in createForEndedAuction"
git push
```

---

## Task 20: `BotTaskService.complete` — case-3 ownership check

Implements spec §8.3.

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskService.java`
- Test: extend `BotTaskServiceTest` / `BotTaskServiceCompleteTest`

- [ ] **Step 1: Inject the SL group repo**

Add `private final RealtyGroupSlGroupRepository slGroupRepo;` to the field list (Lombok wires it).

- [ ] **Step 2: Insert the case-3 check**

In `complete(...)`, after the existing `authBuyerId` + `salePrice` checks but **before** the parcel-lock pre-check, insert:

```java
        if (auction.getRealtyGroupSlGroupId() != null) {
            com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroup reg = slGroupRepo
                    .findById(auction.getRealtyGroupSlGroupId())
                    .orElse(null);
            UUID reported = body.parcelOwner();
            if (reg == null || reported == null || !reported.equals(reg.getSlGroupUuid())) {
                task.setStatus(BotTaskStatus.FAILED);
                task.setFailureReason("SL_GROUP_OWNERSHIP_MISMATCH");
                task.setCompletedAt(now);
                botTaskRepo.save(task);
                auction.setStatus(AuctionStatus.VERIFICATION_FAILED);
                auction.setVerificationNotes(
                        "Bot: parcel is not owned by the registered SL group. Re-check that the "
                                + "parcel is deeded to the correct SL group, then retry verification.");
                auctionRepo.save(auction);
                log.info("Bot task {} case-3 ownership mismatch: auctionId={} reported={} expected={}",
                        taskId, auction.getId(), reported, reg == null ? null : reg.getSlGroupUuid());
                return task;
            }
        }
```

- [ ] **Step 3: Test**

Extend `BotTaskServiceTest`:

- `complete_case3_parcelOwnerMatchesRegistration_succeeds`
- `complete_case3_parcelOwnerMismatch_failsVerification`
- `complete_case3_nullReportedOwner_failsVerification`

- [ ] **Step 4: Run tests**

Run: `cd backend && ./mvnw test -Dtest=BotTaskServiceTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskService.java \
        backend/src/test/java/com/slparcelauctions/backend/bot/BotTaskServiceTest.java
git commit -m "feat(realty-slgroup): case-3 ownership check in BotTaskService.complete"
git push
```

---

## End of Part 2

Part 3 (Tasks 21–30): ownership monitor, member-departure reassignment, dissolution gate, broker cancel, frontend, LSL, docs.
