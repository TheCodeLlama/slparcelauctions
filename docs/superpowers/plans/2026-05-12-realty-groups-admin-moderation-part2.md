# Realty Groups F — Implementation Plan — Part 2 (Tasks 15–28)

Slice 3 (fraud-flag extension), Slice 4 (reports against groups), Slice 5a (SL group admin: parser fix, About-text removal, reverify, force-unregister, admin endpoints).

---

## Task 15: Fraud flag extension (Slice 3)

**Files (modify):**
- `backend/src/main/java/com/slparcelauctions/backend/admin/AdminFraudFlagService.java`
- `backend/src/main/java/com/slparcelauctions/backend/admin/dto/AdminFraudFlagDetailDto.java` (if it switches on entity type)
- `backend/src/test/java/com/slparcelauctions/backend/admin/AdminFraudFlagServiceTest.java`

**Spec references:** §11.

- [ ] **Step 1: Add REALTY_GROUP entity-resolver case to AdminFraudFlagService**

Find the existing switch / strategy that resolves `entityId` based on `entityType`. Add a case for `REALTY_GROUP` that looks up via `RealtyGroupRepository.findByPublicId`, asserts existence, and returns the internal Long ID.

- [ ] **Step 2: Add a test case**

`createFraudFlag_forRealtyGroup_writesFlagWithEntityTypeAndEntityId` — covers entity resolution + flag insertion via the existing flow.

- [ ] **Step 3: Run + commit**

```bash
./mvnw test -Dtest=AdminFraudFlagServiceTest
git add backend/src/main backend/src/test
git commit -m "feat(realty-f): fraud-flag service supports REALTY_GROUP entity"
```

---

## Task 16: RealtyGroupReportService

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/reports/RealtyGroupReportService.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/reports/exception/AlreadyReportedException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/reports/exception/CannotReportOwnGroupException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/reports/exception/ReportNotFoundException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/reports/RealtyGroupReportRateLimiter.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/realty/reports/RealtyGroupReportServiceTest.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/realty/reports/RealtyGroupReportRateLimiterTest.java`

**Spec references:** §8, §12.1, §12.3.

- [ ] **Step 1: Write the rate limiter**

Mirror `ListingReportRateLimiter` if it exists; otherwise a Redis INCR/EXPIRE pattern keyed by `report_rl:{userId}:{yyyy-mm-dd}` with a hard limit (default 5) — share the bucket with listing reports so the 5/day limit is total across both entity types.

- [ ] **Step 2: Write `RealtyGroupReportService` test (failing)**

Test cases:
- `submit_happyPath_insertsOpenRowAndReturnsDto`
- `submit_whenReporterIsMember_throwsCannotReportOwnGroup`
- `submit_whenAlreadyOpenReport_throwsAlreadyReported`
- `submit_whenRateLimited_throws429RateLimitedException`
- `resolve_setsResolvedAtAndAdmin`
- `resolve_recordsAdminAction`
- `dismiss_setsDismissedAndIncrementsDismissedReportsCount`
- `dismiss_recordsAdminAction`

- [ ] **Step 3: Implement**

```java
@Service
@RequiredArgsConstructor
public class RealtyGroupReportService {
    private final RealtyGroupReportRepository reportRepo;
    private final RealtyGroupRepository groupRepo;
    private final RealtyGroupMemberRepository memberRepo;
    private final UserRepository userRepo;
    private final RealtyGroupReportRateLimiter rateLimiter;
    private final AdminActionService adminActionService;
    private final Clock clock;

    @Transactional
    public RealtyGroupReport submit(UUID groupPublicId, Long reporterId, RealtyGroupReportReason reason, String details) { ... }

    @Transactional
    public RealtyGroupReport resolve(UUID reportPublicId, Long adminId, String notes) { ... }

    @Transactional
    public RealtyGroupReport dismiss(UUID reportPublicId, Long adminId, String notes) { ... }
}
```

Handle the unique-violation on `uq_rg_reports_one_open_per_reporter` by catching `DataIntegrityViolationException` and translating to `AlreadyReportedException`.

- [ ] **Step 4: Run + commit**

---

## Task 17: Public RealtyGroupReportController

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/reports/RealtyGroupReportController.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/reports/dto/SubmitReportRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/reports/dto/ReportDto.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/realty/reports/RealtyGroupReportControllerSliceTest.java`

**Spec references:** §6.1, §12.1.

- [ ] **Step 1: Write the slice test**

Test cases:
- `postReport_happyPath_returns201`
- `postReport_unauthenticated_returns401`
- `postReport_alreadyReported_returns409AlreadyReported`
- `postReport_ownGroup_returns409CannotReportOwn`
- `postReport_rateLimited_returns429`

- [ ] **Step 2: Implement**

```java
@RestController
@RequestMapping("/api/v1/realty-groups/{publicId}/reports")
@RequiredArgsConstructor
public class RealtyGroupReportController {
    private final RealtyGroupReportService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReportDto submit(
        @PathVariable UUID publicId,
        @Valid @RequestBody SubmitReportRequest req,
        @AuthenticationPrincipal AuthPrincipal reporter
    ) { ... }
}
```

`SubmitReportRequest`: `{ reason: RealtyGroupReportReason, details: String(10–2000) }`.

- [ ] **Step 3: Add exception handlers in `RealtyExceptionHandler`** for `AlreadyReportedException` (409), `CannotReportOwnGroupException` (409), `ReportNotFoundException` (404).

- [ ] **Step 4: Run + commit**

---

## Task 18: Admin reports queue controller + service

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/reports/AdminRealtyGroupReportController.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/reports/dto/AdminReportRowDto.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/reports/dto/AdminReportDetailDto.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/reports/dto/AdminResolveReportRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/reports/dto/AdminDismissReportRequest.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/realty/reports/AdminRealtyGroupReportControllerSliceTest.java`

**Spec references:** §6.5, §12.2, §12.3.

- [ ] **Step 1: Write the slice test**

Test cases:
- `getList_paginated_returnsRows`
- `getList_filterByStatus_appliesFilter`
- `getDetail_returnsFullReport`
- `postResolve_setsResolved`
- `postDismiss_incrementsDismissedReportsCount`
- `postResolve_withEscalateToSuspendGroup_doesNotItselfSuspend` (verify backend treats escalateTo as informational)

- [ ] **Step 2: Implement**

```java
@RestController
@RequestMapping("/api/v1/admin/realty-groups/reports")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminRealtyGroupReportController {
    private final RealtyGroupReportService service;
    private final ReportDtoMapper mapper;

    @GetMapping
    public Page<AdminReportRowDto> list(
        @RequestParam(required = false) RealtyGroupReportStatus status,
        @PageableDefault(size = 20, sort = "createdAt", direction = DESC) Pageable pageable
    ) { ... }

    @GetMapping("/{publicId}")
    public AdminReportDetailDto detail(@PathVariable UUID publicId) { ... }

    @PostMapping("/{publicId}/resolve")
    public AdminReportDetailDto resolve(
        @PathVariable UUID publicId,
        @Valid @RequestBody AdminResolveReportRequest req,
        @AuthenticationPrincipal AuthPrincipal admin
    ) { ... }

    @PostMapping("/{publicId}/dismiss")
    public AdminReportDetailDto dismiss(
        @PathVariable UUID publicId,
        @Valid @RequestBody AdminDismissReportRequest req,
        @AuthenticationPrincipal AuthPrincipal admin
    ) { ... }
}
```

- [ ] **Step 3: Run + commit**

---

## Task 19: /me/reports UNION extension for reporter-visibility

**Files (modify):**
- `backend/src/main/java/com/slparcelauctions/backend/admin/reports/UserReportService.java` (the service backing `/me/reports`; may be named differently — Grep for "MeReports" or the existing `/me/reports` route)
- `backend/src/main/java/com/slparcelauctions/backend/admin/reports/dto/MyReportResponse.java` (existing — extend the envelope shape)
- `backend/src/test/java/com/slparcelauctions/backend/admin/reports/UserReportServiceTest.java`

**Spec references:** §12.4.

- [ ] **Step 1: Add entityType + entityPublicId fields to the response envelope**

Extend `MyReportResponse` (or whatever the existing reporter-visibility DTO is) to include:
```java
private String entityType;       // "LISTING" or "REALTY_GROUP"
private UUID entityPublicId;
```

If the DTO is shared with frontend types, frontend handler updates follow in Part 3.

- [ ] **Step 2: Implement the UNION query**

Existing query reads `listing_reports`. Add a parallel read from `realty_group_reports` and merge in service code (or use a JPA `UNION ALL` query).

- [ ] **Step 3: Test the merged stream**

Add a test that seeds one listing report + one group report from the same reporter and asserts both come back in `created_at DESC` order with correct `entityType` discrimination.

- [ ] **Step 4: Run + commit**

---

## Task 20: SL group page parser fix + fixture-based test

**Files (modify + create):**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/sl/SlWorldApiClient.java`
- Create: `backend/src/test/resources/sl/group-page-slparcels.html` (move from `.scratch/sl-group-page.html`)
- Create: `backend/src/test/resources/sl/group-page-with-charter.html` (hand-crafted variant)
- Create: `backend/src/test/java/com/slparcelauctions/backend/sl/SlWorldApiClientGroupParserTest.java`

**Spec references:** §14.1, §14.2.

- [ ] **Step 1: Write the failing test**

```java
class SlWorldApiClientGroupParserTest {
    @Test
    void parsesSlparcelsFixture() {
        String html = readFixture("/sl/group-page-slparcels.html");
        SlWorldApiClient client = new SlWorldApiClient(/* no webClient needed for parse */);
        GroupPageData data = client.parseGroupHtml(
            UUID.fromString("79f06955-38f4-3124-25b3-f5506c85828f"),
            html
        );
        assertThat(data.name()).isEqualTo("SLParcels");
        assertThat(data.founderUuid()).isEqualTo(UUID.fromString("aa87bc38-c175-427d-b665-02e6838963cc"));
        assertThat(data.aboutText()).isNullOrEmpty();
    }

    @Test
    void parsesCharterFixture() {
        String html = readFixture("/sl/group-page-with-charter.html");
        GroupPageData data = new SlWorldApiClient(/* ... */).parseGroupHtml(SOME_UUID, html);
        assertThat(data.aboutText()).isEqualTo("Expected charter text");
    }
}
```

The current `parseGroupHtml` is private — either expose it package-private or extract into a static helper. Existing test conventions in the codebase will dictate.

- [ ] **Step 2: Verify it fails** (current parser returns null name + null about text).

- [ ] **Step 3: Fix the parser**

```java
private String parseGroupName(Document doc) {
    Element el = doc.selectFirst(".details h1");
    if (el != null && !el.text().isBlank()) return el.text();
    Element titleEl = doc.selectFirst("title");
    if (titleEl != null && !titleEl.text().isBlank()) return titleEl.text();
    return null;
}

private String parseGroupAboutText(Document doc) {
    Element el = doc.selectFirst(".details p.desc");
    return el != null && !el.text().isBlank() ? el.text() : null;
}
```

- [ ] **Step 4: Create the hand-crafted second fixture**

A minimal HTML with `<div class="details"><h1>My Group</h1><p class="desc">Expected charter text</p></div>` and a meta `<meta name="founderid" content="bbbbbbbb-...">`.

- [ ] **Step 5: Confirm `.scratch/sl-group-page.html` is moved (not copied) to the test resources path; remove the scratch copy.**

- [ ] **Step 6: Run + commit**

```bash
./mvnw test -Dtest=SlWorldApiClientGroupParserTest
git rm .scratch/sl-group-page.html 2>/dev/null || true
git add backend/src/main/java/com/slparcelauctions/backend/sl/SlWorldApiClient.java
git add backend/src/test/java backend/src/test/resources/sl
git commit -m "fix(realty-f): SL group parser selectors + fixture-based test"
```

---

## Task 21: Remove SlGroupAboutTextPollTask + ABOUT_TEXT method

**Files (delete + modify):**
- Delete: `backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/SlGroupAboutTextPollTask.java`
- Delete: `backend/src/test/java/com/slparcelauctions/backend/realty/slgroup/SlGroupAboutTextPollTaskTest.java` (or equivalent)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/SlGroupVerifyMethod.java` (drop `ABOUT_TEXT` value)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/RealtyGroupSlGroupService.java` (drop the about-text branch in `register`)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/dto/RegisterSlGroupRequest.java` (drop the `method` field)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/RealtyGroupSlGroupController.java` (signature drop)
- Modify: any controller / service tests touching method = ABOUT_TEXT
- Frontend: `frontend/src/components/realty/slgroup/RegisterSlGroupModal.tsx` (drop method picker)
- Frontend: `frontend/src/components/realty/slgroup/SlGroupVerificationInstructionsCard.tsx` (founder-terminal only)
- Frontend: `frontend/src/types/realty.ts` (drop `SlGroupVerifyMethod.ABOUT_TEXT`)

**Spec references:** §14.3.

- [ ] **Step 1: Delete the poll task + its tests** (`git rm` both).

- [ ] **Step 2: Drop `ABOUT_TEXT` from `SlGroupVerifyMethod`**

If any code branches on the enum, simplify to founder-terminal-only.

- [ ] **Step 3: Simplify `RegisterSlGroupRequest`** to drop the `method` field. Backend implicitly uses FOUNDER_TERMINAL.

- [ ] **Step 4: Simplify `RealtyGroupSlGroupService.register(...)`** to drop the about-text branch.

- [ ] **Step 5: Frontend simplifications**

- `RegisterSlGroupModal.tsx`: remove the method radio group, render only founder-terminal instructions.
- `SlGroupVerificationInstructionsCard.tsx`: keep only the founder-terminal copy.
- `frontend/src/types/realty.ts`: drop `ABOUT_TEXT` from `SlGroupVerifyMethod` union.

- [ ] **Step 6: Run backend + frontend tests; fix any breakage**

```bash
cd backend && ./mvnw test
cd ../frontend && npm test
```

- [ ] **Step 7: Commit**

```bash
git rm backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/SlGroupAboutTextPollTask.java
# (and the matching test)
git add -A backend/src frontend/src
git commit -m "refactor(realty-f): drop About-text SL group verification path"
```

---

## Task 22: SlGroupReverifyService

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/SlGroupReverifyService.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/SlGroupDriftReason.java` (enum)
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/SlGroupReverifyResult.java` (record)
- Create: `backend/src/test/java/com/slparcelauctions/backend/realty/slgroup/SlGroupReverifyServiceTest.java`

**Spec references:** §13.2.

- [ ] **Step 1: Write the failing test**

Test cases:
- `recheck_happyPath_updatesLastRevalidatedAtAndCurrentFounderUuid`
- `recheck_whenFounderChanged_setsDriftDetectedAndReason`
- `recheck_when404_setsDriftDetectedWithGroupNotFound`
- `recheck_whenFetchFailsBelowThreshold_doesNotFlagDriftButIncrementsCounter`
- `recheck_whenFetchFailsAtThreshold_flagsDriftWithFetchFailedRepeatedly`
- `recheck_resetCounterOnSuccessfulFetch`
- `recheck_doesNotUpdateLastRevalidatedAtOnFetchFailure`

- [ ] **Step 2: Implement**

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class SlGroupReverifyService {
    private final RealtyGroupSlGroupRepository slGroupRepo;
    private final SlWorldApiClient slWorldApiClient;
    private final NotificationPublisher notificationPublisher;
    private final RealtyGroupModerationProperties props;
    private final Clock clock;

    public record SlGroupReverifyResult(
        boolean driftDetected,
        SlGroupDriftReason driftReason,
        UUID currentFounderUuid
    ) {}

    @Transactional
    public SlGroupReverifyResult recheck(Long slGroupId) {
        RealtyGroupSlGroup row = slGroupRepo.findById(slGroupId).orElseThrow();
        try {
            GroupPageData parsed = slWorldApiClient.fetchGroupPage(row.getSlGroupUuid()).block();
            row.setConsecutiveFetchFailures(0);
            row.setLastRevalidatedAt(OffsetDateTime.now(clock));
            row.setCurrentFounderUuid(parsed.founderUuid());
            if (!Objects.equals(parsed.founderUuid(), row.getFounderAvatarUuid())
                    && row.getDriftDetectedAt() == null) {
                flagDrift(row, SlGroupDriftReason.FOUNDER_CHANGED);
            }
            return new SlGroupReverifyResult(row.getDriftDetectedAt() != null, row.getDriftReason(), parsed.founderUuid());
        } catch (WebClientResponseException.NotFound nf) {
            flagDrift(row, SlGroupDriftReason.GROUP_NOT_FOUND);
            return new SlGroupReverifyResult(true, SlGroupDriftReason.GROUP_NOT_FOUND, null);
        } catch (Exception e) {
            int next = row.getConsecutiveFetchFailures() + 1;
            row.setConsecutiveFetchFailures(next);
            if (next >= props.getSlGroup().getReverifyFetchFailureThreshold()) {
                flagDrift(row, SlGroupDriftReason.FETCH_FAILED_REPEATEDLY);
            }
            // Note: do NOT update last_revalidated_at on failure.
            return new SlGroupReverifyResult(next >= props.getSlGroup().getReverifyFetchFailureThreshold(),
                row.getDriftReason(), null);
        }
    }

    private void flagDrift(RealtyGroupSlGroup row, SlGroupDriftReason reason) {
        row.setDriftDetectedAt(OffsetDateTime.now(clock));
        row.setDriftReason(reason.name());
        notificationPublisher.realtyGroupSlGroupDriftDetected(
            row.getRealtyGroup().getLeader().getId(),
            row.getRealtyGroup().getId(),
            row.getSlGroupName(),
            reason.name()
        );
    }
}
```

Add the new `realtyGroupSlGroupDriftDetected` notification helper to `NotificationPublisher` interface + impl (reuse SL IM channel).

`SlGroupDriftReason` enum: `FOUNDER_CHANGED`, `GROUP_NOT_FOUND`, `FETCH_FAILED_REPEATEDLY`.

- [ ] **Step 3: Run + commit**

---

## Task 23: SlGroupReverifyTask (scheduled)

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/SlGroupReverifyTask.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/realty/slgroup/SlGroupReverifyTaskTest.java`

**Spec references:** §13.1.

- [ ] **Step 1: Failing test**

Test cases:
- `runOnce_picksRowsDueForRevalidationBasedOnCadence`
- `runOnce_skipsRowsRevalidatedRecentlyEnough`
- `runOnce_skipsUnverifiedRows`
- `runOnce_callsRecheckPerEligibleRow`

- [ ] **Step 2: Implement**

```java
@Component
@RequiredArgsConstructor
public class SlGroupReverifyTask {
    private final RealtyGroupSlGroupRepository repo;
    private final SlGroupReverifyService reverifyService;
    private final RealtyGroupModerationProperties props;
    private final Clock clock;

    @Scheduled(fixedRate = 60 * 60 * 1000)
    @ConditionalOnProperty(name = "slpa.realty.sl-group.reverify.enabled", havingValue = "true", matchIfMissing = true)
    public void runOnce() {
        OffsetDateTime threshold = OffsetDateTime.now(clock)
            .minusDays(props.getSlGroup().getReverifyCadenceDays());
        List<RealtyGroupSlGroup> due = repo.findDueForReverify(threshold);
        for (RealtyGroupSlGroup row : due) {
            try { reverifyService.recheck(row.getId()); }
            catch (Exception e) { log.warn("reverify failed for {}", row.getId(), e); }
        }
    }
}
```

Add `RealtyGroupSlGroupRepository.findDueForReverify(OffsetDateTime threshold)`:
```java
@Query("""
    SELECT r FROM RealtyGroupSlGroup r
     WHERE r.verified = true
       AND r.unregisteredAt IS NULL
       AND (r.lastRevalidatedAt IS NULL OR r.lastRevalidatedAt < :threshold)
""")
List<RealtyGroupSlGroup> findDueForReverify(OffsetDateTime threshold);
```

- [ ] **Step 3: Run + commit**

---

## Task 24: SlGroupForceUnregisterService

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/SlGroupForceUnregisterService.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/realty/slgroup/SlGroupForceUnregisterServiceTest.java`

**Spec references:** §13.5.

- [ ] **Step 1: Failing test**

Test cases:
- `forceUnregister_setsUnregisteredAtAndAdmin`
- `forceUnregister_findsCase3ActiveListings_callsBulkSuspendAll`
- `forceUnregister_zeroActiveListings_succeedsWithNoCascade`
- `forceUnregister_writesAdminAction`

- [ ] **Step 2: Implement**

```java
@Service
@RequiredArgsConstructor
public class SlGroupForceUnregisterService {
    private final RealtyGroupSlGroupRepository slGroupRepo;
    private final AuctionRepository auctionRepo;
    private final BulkListingSuspendService bulkListingSuspendService;
    private final AdminActionService adminActionService;
    private final UserRepository userRepo;
    private final Clock clock;

    @Transactional
    public void forceUnregister(
        UUID realtyGroupPublicId,
        UUID slGroupPublicId,
        Long adminId,
        String reason
    ) {
        RealtyGroupSlGroup row = slGroupRepo.findByPublicId(slGroupPublicId).orElseThrow();
        row.setUnregisteredAt(OffsetDateTime.now(clock));
        row.setUnregisteredByAdmin(userRepo.findById(adminId).orElseThrow());
        row.setUnregisterReason(reason);
        // Cascade: bulk-suspend any in-flight case-3 listings.
        List<Auction> active = auctionRepo.findActiveCase3ListingsForSlGroup(row.getId());
        if (!active.isEmpty()) {
            bulkListingSuspendService.suspendAll(
                row.getRealtyGroup().getId(),
                adminId,
                "SL_GROUP_FORCE_UNREGISTER",
                null   // no linked group suspension
            );
        }
        adminActionService.recordAction(adminId, AdminActionType.REALTY_GROUP_SL_GROUP_FORCE_UNREGISTER,
            Map.of("slGroupPublicId", slGroupPublicId.toString(),
                   "cascadedListingCount", active.size(),
                   "reason", reason));
    }
}
```

Add `AuctionRepository.findActiveCase3ListingsForSlGroup(Long slGroupId)`:
```java
@Query("""
    SELECT a FROM Auction a
     WHERE a.realtyGroupSlGroupId = :slGroupId
       AND a.status = com.slparcelauctions.backend.auction.AuctionStatus.ACTIVE
""")
List<Auction> findActiveCase3ListingsForSlGroup(Long slGroupId);
```

- [ ] **Step 3: Run + commit**

---

## Task 25: AdminSlGroupController (recheck / ack-drift / force-unregister)

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/admin/AdminSlGroupController.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/admin/AdminSlGroupService.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/admin/dto/SlGroupRecheckResultDto.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/admin/dto/AckDriftRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/slgroup/admin/dto/ForceUnregisterRequest.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/realty/slgroup/admin/AdminSlGroupControllerSliceTest.java`

**Spec references:** §6.6, §13.3, §13.4, §13.5.

- [ ] **Step 1: Slice test**

Test cases:
- `postRecheck_returns200WithResult`
- `postRecheck_writesAdminAction`
- `postAckDrift_clearsDriftAndUpdatesFounderSnapshotIfChanged`
- `postAckDrift_whenNoDrift_returns409NoDriftDetected`
- `deleteForce_returns204AndCascades`
- `deleteForce_withoutForceParam_delegatesToExistingNonForceUnregister` (or returns the standard gate-based 409)

- [ ] **Step 2: Implement controller + service-wrapper**

```java
@RestController
@RequestMapping("/api/v1/admin/realty-groups/{publicId}/sl-groups/{slGroupPublicId}")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminSlGroupController {
    private final AdminSlGroupService service;

    @PostMapping("/recheck")
    public SlGroupRecheckResultDto recheck(...) { ... }

    @PostMapping("/ack-drift")
    public AdminSlGroupRowDto ackDrift(...) { ... }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unregister(
        @PathVariable UUID publicId,
        @PathVariable UUID slGroupPublicId,
        @RequestParam(name = "force", defaultValue = "false") boolean force,
        @Valid @RequestBody ForceUnregisterRequest req,
        @AuthenticationPrincipal AuthPrincipal admin
    ) {
        if (force) {
            service.forceUnregister(publicId, slGroupPublicId, admin.userId(), req.reason());
        } else {
            service.unregister(publicId, slGroupPublicId, admin.userId());
            // existing non-force unregister via RealtyGroupSlGroupService.unregister
        }
    }
}
```

- [ ] **Step 3: Add exception handler for `NoDriftDetectedException`** (new exception class).

- [ ] **Step 4: Run + commit**

---

## Task 26: SystemUserResolver test + seed verification

**Note:** `SystemUserResolver` was created in Part 1 Task 8 Step 0. This task only adds the unit test and verifies the seed user row exists.

**Files:**
- Create: `backend/src/test/java/com/slparcelauctions/backend/admin/audit/SystemUserResolverTest.java`
- Modify (if necessary): existing seed migration / R__seed_system_user.sql

- [ ] **Step 1: Unit test**

`getSystemUser_returnsConfiguredUser_orThrowsIfMissing`.

- [ ] **Step 2: Verify the seed user exists**

Run `./mvnw test -Dtest=SystemUserResolverTest`. If the test fails because no user with `id=1` exists, add a Flyway repeatable migration `R__seed_system_user.sql` that idempotently `INSERT ... ON CONFLICT DO NOTHING`'s the system user row.

- [ ] **Step 3: Run + commit**

---

## Task 27: AdminActionService.recordSystemAction helper

**Files (modify):**
- `backend/src/main/java/com/slparcelauctions/backend/admin/audit/AdminActionService.java`
- `backend/src/test/java/com/slparcelauctions/backend/admin/audit/AdminActionServiceTest.java`

- [ ] **Step 1: Add overload**

```java
public AdminAction recordSystemAction(AdminActionType type, Map<String, Object> evidence) {
    return record(systemUserResolver.getSystemUser().getId(), type, evidence);
}
```

Wire `SystemUserResolver` in.

- [ ] **Step 2: Test**

`recordSystemAction_writesRowWithSystemUserActor`.

- [ ] **Step 3: Run + commit**

---

## Task 28: NotificationPublisher additions

**Files (modify):**
- `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisher.java`
- `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisherImpl.java`
- `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationCategory.java` — add `REALTY_GROUP_SUSPENDED`, `REALTY_GROUP_UNSUSPENDED`, `REALTY_GROUP_SL_GROUP_DRIFT_DETECTED`, `LISTING_AUTO_CANCELLED_FROM_BULK_SUSPEND` (only the ones missing; reuse where possible)
- `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationDataBuilder.java`
- `backend/src/main/java/com/slparcelauctions/backend/notification/slim/SlImLinkResolver.java` — add link routing for new categories
- `backend/src/test/java/com/slparcelauctions/backend/notification/NotificationPublisherImplTest.java`

- [ ] **Step 1: Add helpers**

```java
void realtyGroupSuspended(long memberUserId, long groupId, String groupName, String reason, OffsetDateTime expiresAt);
void realtyGroupUnsuspended(long memberUserId, long groupId, String groupName);
void realtyGroupSlGroupDriftDetected(long leaderUserId, long groupId, String slGroupName, String driftReason);
void listingAutoCancelledFromBulkSuspend(long sellerUserId, long auctionId, String parcelName);
```

- [ ] **Step 2: Wire each through to the existing publish primitive**

Each helper builds a category-specific data map via `NotificationDataBuilder` + calls the existing `notify(userId, category, title, body, data, linkUrl)` method.

- [ ] **Step 3: Run + commit**

End of Part 2.
