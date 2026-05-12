# Realty Groups F — Implementation Plan — Part 3 (Tasks 29–45)

Slice 5b (bulk commission edit + analytics), reputation/rating, audit filter, frontend, polish.

---

## Task 29: RealtyGroupBulkCommissionService

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/service/RealtyGroupBulkCommissionService.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/dto/BulkCommissionRatesRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/exception/MemberNotInGroupException.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/realty/service/RealtyGroupBulkCommissionServiceTest.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/RealtyGroupController.java` (or wherever leader endpoints live) — add the PATCH endpoint
- Create: `backend/src/test/java/com/slparcelauctions/backend/realty/RealtyGroupCommissionControllerSliceTest.java`

**Spec references:** §6.7, §15.1.

- [ ] **Step 1: Failing test**

Test cases:
- `update_happyPath_writesAllRates`
- `update_oneInvalidRate_rollsBackBatch`
- `update_memberNotInGroup_throwsMemberNotInGroup`
- `update_negativeRate_throwsValidationException`
- `update_requiresManageMembersPermission`
- `update_callsRealtyGroupGuardForSuspensionCheck`

- [ ] **Step 2: Implement**

```java
@Service
@RequiredArgsConstructor
public class RealtyGroupBulkCommissionService {
    private final RealtyGroupRepository groupRepo;
    private final RealtyGroupMemberRepository memberRepo;
    private final RealtyGroupGuard guard;
    private final RealtyGroupPermissionChecker permissionChecker;
    private final Clock clock;

    @Transactional
    public void updateRates(UUID groupPublicId, Long callerUserId, BulkCommissionRatesRequest req) {
        RealtyGroup group = groupRepo.findByPublicId(groupPublicId).orElseThrow();
        guard.requireGroupCanOperate(group.getId());
        permissionChecker.requirePermission(group.getId(), callerUserId, RealtyGroupPermission.MANAGE_MEMBERS);
        for (var entry : req.memberRates()) {
            RealtyGroupMember m = memberRepo.findByGroupAndMemberPublicId(group.getId(), entry.memberPublicId())
                .orElseThrow(() -> new MemberNotInGroupException(entry.memberPublicId()));
            if (entry.rate().compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("rate must be >= 0");
            }
            m.setAgentCommissionRate(entry.rate());
        }
    }
}
```

`BulkCommissionRatesRequest`: `{ memberRates: List<MemberRate> }` where `MemberRate` is `{ memberPublicId: UUID, rate: BigDecimal }`.

- [ ] **Step 3: Wire into controller**

```java
@PatchMapping("/{publicId}/members/commission-rates")
public void updateCommissionRates(
    @PathVariable UUID publicId,
    @Valid @RequestBody BulkCommissionRatesRequest req,
    @AuthenticationPrincipal AuthPrincipal caller
) {
    bulkCommissionService.updateRates(publicId, caller.userId(), req);
}
```

- [ ] **Step 4: Run + commit**

---

## Task 30: GroupCommissionAnalyticsService

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/analytics/GroupCommissionAnalyticsService.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/analytics/dto/MemberCommissionRowDto.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/analytics/RealtyGroupAnalyticsController.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/realty/analytics/GroupCommissionAnalyticsServiceTest.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/realty/analytics/RealtyGroupAnalyticsControllerSliceTest.java`

**Spec references:** §6.8, §15.2.

- [ ] **Step 1: Failing test**

Test cases:
- `compute_returnsRowsPerMemberWithLifetimeAndLast30Days`
- `compute_filtersToCase1AndCase3AuctionsBelongingToGroup`
- `compute_emptyResult_whenNoCommissions`
- `compute_requiresLeaderOrManageMembers`

- [ ] **Step 2: Implement**

Use a native query (the spec query is JPA-friendly enough; use `@Query(nativeQuery = true)`):

```java
@Service
@RequiredArgsConstructor
public class GroupCommissionAnalyticsService {
    private final EntityManager em;
    private final RealtyGroupRepository groupRepo;
    private final Clock clock;

    public List<MemberCommissionRowDto> compute(UUID groupPublicId) {
        RealtyGroup group = groupRepo.findByPublicId(groupPublicId).orElseThrow();
        return em.createNativeQuery("""
            SELECT u.public_id, u.display_name,
                   COALESCE(SUM(ul.amount), 0) AS lifetime,
                   COALESCE(SUM(ul.amount) FILTER (
                       WHERE ul.created_at > now() - INTERVAL '30 days'
                   ), 0) AS last_30_days
              FROM realty_group_members m
              JOIN users u ON u.id = m.user_id
              LEFT JOIN user_ledger ul
                ON ul.user_id = m.user_id
               AND ul.entry_type = 'AGENT_COMMISSION_CREDIT'
               AND ul.idempotency_key LIKE 'AGCOMM-%'
               AND EXISTS (
                 SELECT 1 FROM auctions a
                  WHERE 'AGCOMM-' || a.id = ul.idempotency_key
                    AND (a.realty_group_id = :groupId
                         OR EXISTS (
                           SELECT 1 FROM realty_group_sl_groups rsg
                            WHERE rsg.id = a.realty_group_sl_group_id
                              AND rsg.realty_group_id = :groupId
                         ))
               )
             WHERE m.realty_group_id = :groupId
             GROUP BY u.public_id, u.display_name
             ORDER BY lifetime DESC
            """, Tuple.class)
            .setParameter("groupId", group.getId())
            .getResultStream()
            .map(t -> new MemberCommissionRowDto(
                (UUID) t.get(0),
                (String) t.get(1),
                ((Number) t.get(2)).longValue(),
                ((Number) t.get(3)).longValue()))
            .toList();
    }
}
```

- [ ] **Step 3: Run + commit**

---

## Task 31: GroupRatingService + cache + Review listener

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/rating/GroupRatingService.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/rating/dto/GroupRatingDto.java`
- Modify: existing `Review` creation pipeline to publish a `ReviewCreatedEvent` (or extend if event already exists)
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/rating/GroupRatingCacheInvalidator.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/realty/rating/GroupRatingServiceTest.java`

**Spec references:** §16.

- [ ] **Step 1: Failing test**

Test cases:
- `computeRating_aggregatesCase1AndCase3Reviews`
- `computeRating_emptyResult_whenNoReviews`
- `computeRating_cachesResultInRedis`
- `invalidate_evictsCacheOnNewReview`

- [ ] **Step 2: Implement**

```java
@Service
@RequiredArgsConstructor
public class GroupRatingService {
    private final EntityManager em;
    private final StringRedisTemplate redis;

    private static final String CACHE_KEY = "realty_groups_rating:";
    private static final Duration TTL = Duration.ofHours(1);

    public GroupRatingDto computeRating(Long groupId) {
        String cached = redis.opsForValue().get(CACHE_KEY + groupId);
        if (cached != null) return parse(cached);
        var result = em.createNativeQuery("""
            SELECT AVG(r.star_rating)::float AS avg_rating, COUNT(*) AS review_count
              FROM reviews r
              JOIN auctions a ON a.id = r.auction_id
             WHERE a.realty_group_id = :groupId
                OR EXISTS (
                  SELECT 1 FROM realty_group_sl_groups rsg
                   WHERE rsg.id = a.realty_group_sl_group_id
                     AND rsg.realty_group_id = :groupId
                )
            """, Tuple.class)
            .setParameter("groupId", groupId)
            .getSingleResult();
        Tuple t = (Tuple) result;
        Double avg = t.get(0) == null ? null : ((Number) t.get(0)).doubleValue();
        long count = ((Number) t.get(1)).longValue();
        GroupRatingDto dto = new GroupRatingDto(avg, count);
        redis.opsForValue().set(CACHE_KEY + groupId, serialize(dto), TTL);
        return dto;
    }

    public void invalidate(Long groupId) {
        redis.delete(CACHE_KEY + groupId);
    }
}
```

`GroupRatingCacheInvalidator` listens for `ReviewCreatedEvent` (publish from existing Review creation), looks up the auction's group association, and invalidates the cache for that group:
```java
@Component
@RequiredArgsConstructor
public class GroupRatingCacheInvalidator {
    private final GroupRatingService ratingService;
    private final AuctionRepository auctionRepo;

    @EventListener
    public void on(ReviewCreatedEvent event) {
        auctionRepo.findById(event.auctionId()).ifPresent(a -> {
            if (a.getRealtyGroup() != null) {
                ratingService.invalidate(a.getRealtyGroup().getId());
            } else if (a.getRealtyGroupSlGroupId() != null) {
                // resolve indirect via sl_group
                slGroupRepo.findById(a.getRealtyGroupSlGroupId()).ifPresent(rsg ->
                    ratingService.invalidate(rsg.getRealtyGroup().getId())
                );
            }
        });
    }
}
```

- [ ] **Step 3: Wire into existing Review creation**

If `ReviewService.create(...)` doesn't already publish an `ApplicationEventPublisher` event, add one. Mirror existing event-publishing patterns in the codebase.

- [ ] **Step 4: Surface in API**

Add a method on the existing `RealtyGroupService.findByPublicId` (or its DTO mapper) to populate a `rating` field. Add to `RealtyGroupDto`:
```java
private GroupRatingDto rating;
```

- [ ] **Step 5: Run + commit**

---

## Task 32: Audit filter extension

**Files (modify):**
- `backend/src/main/java/com/slparcelauctions/backend/admin/audit/AdminAuditController.java` (or equivalent — Grep for the existing `/admin/audit` endpoint)
- `backend/src/main/java/com/slparcelauctions/backend/admin/audit/AdminActionRepository.java`
- `backend/src/test/java/com/slparcelauctions/backend/admin/audit/AdminAuditControllerSliceTest.java`

**Spec references:** §17.

- [ ] **Step 1: Add query params + filter**

Extend the existing `GET /api/v1/admin/audit` query to accept:
- `entityType=REALTY_GROUP` — filter to any AdminActionType matching `REALTY_GROUP_*`.
- `entityId={groupPublicId}` — narrow further to actions where the `evidenceJson` contains the group's `publicId`.

Implementation: extend the repository query (use a `LIKE` on the AdminActionType name, plus JSONB query on evidence).

- [ ] **Step 2: Test**

Slice test confirming the filter narrows correctly.

- [ ] **Step 3: Run + commit**

---

## Task 33: Frontend types + API clients + hooks (Slice 1+2+4+5)

**Files:**
- Modify: `frontend/src/types/realty.ts` — add `RealtyGroupSuspension`, `RealtyGroupReport`, `BulkSuspendResult`, `SlGroupDriftReason`, `BulkCommissionRatesRequest`, `MemberCommissionRow`, `GroupRating`, etc.
- Create: `frontend/src/lib/api/realtyGroupModeration.ts`
- Create: `frontend/src/lib/api/realtyGroupReports.ts`
- Create: `frontend/src/lib/api/realtyGroupBulkListings.ts`
- Create: `frontend/src/lib/api/realtyGroupBulkCommission.ts`
- Create: `frontend/src/lib/api/realtyGroupCommissionAnalytics.ts`
- Create: `frontend/src/lib/api/realtyGroupSlGroupAdmin.ts`
- Create: `frontend/src/hooks/realty/useGroupSuspensions.ts`
- Create: `frontend/src/hooks/realty/useGroupReports.ts`
- Create: `frontend/src/hooks/realty/useBulkSuspendListings.ts`
- Create: `frontend/src/hooks/realty/useBulkCommissionEdit.ts`
- Create: `frontend/src/hooks/realty/useCommissionAnalytics.ts`
- Create: `frontend/src/hooks/realty/useSlGroupRecheck.ts`
- Modify: `frontend/src/test/msw/handlers.ts` — add MSW handlers for each new endpoint

- [ ] **Step 1: Add the types**

For each new entity / DTO from the backend, declare its TypeScript shape. Mirror the existing realty types module's conventions (`UUID = string`, `BigDecimal = string`, dates as `string` ISO timestamps).

- [ ] **Step 2: Implement API clients**

Each module exposes typed `async` wrappers over `fetch(apiUrl(...))`. Mirror existing realty API clients (e.g., `realtySlGroup.ts` from E).

- [ ] **Step 3: Implement TanStack Query hooks**

Each hook wraps the appropriate API client. Use `useQuery` for reads, `useMutation` for writes.

- [ ] **Step 4: MSW handlers**

Add handlers for the happy paths + a few error cases. Mirror existing handler files.

- [ ] **Step 5: Frontend `verify` + commit**

```bash
cd frontend && npm run verify && git add -A && cd ..
git commit -m "feat(realty-f): frontend types + API clients + hooks + MSW handlers"
```

---

## Task 34: GroupStatusPill + GroupRatingBadge (components)

**Files:**
- Create: `frontend/src/components/realty/GroupStatusPill.tsx`
- Create: `frontend/src/components/realty/GroupStatusPill.test.tsx`
- Create: `frontend/src/components/realty/GroupRatingBadge.tsx`
- Create: `frontend/src/components/realty/GroupRatingBadge.test.tsx`

**Spec references:** §7, §16.3.

- [ ] **Step 1: Failing tests**

`GroupStatusPill.test.tsx` covers: active (no badge) / suspended (badge + countdown) / banned (badge "Banned"); tooltip shows reason.
`GroupRatingBadge.test.tsx` covers: "★★★★☆ 4.2 (12 reviews)" / "No reviews yet"; clicks link to leader's user-reviews path as stopgap.

- [ ] **Step 2: Implement**

Use icons from `@/components/ui/icons.ts` (no emojis per memory). Star rendering via Lucide's `Star` / `StarHalf` icons.

- [ ] **Step 3: Run + commit**

---

## Task 35: Admin tabs — Suspensions + Bulk-Listings + Reports + SL-Groups + Audit

**Files (parallel-safe — each tab is its own file):**
- Create: `frontend/src/components/admin/realty-groups/AdminGroupSuspensionsTab.tsx` + `.test.tsx`
- Create: `frontend/src/components/admin/realty-groups/AdminGroupSuspensionModal.tsx` + `.test.tsx`
- Create: `frontend/src/components/admin/realty-groups/AdminGroupBulkListingsTab.tsx` + `.test.tsx`
- Create: `frontend/src/components/admin/realty-groups/AdminGroupReportsTab.tsx` + `.test.tsx`
- Create: `frontend/src/components/admin/realty-groups/AdminGroupSlGroupsTab.tsx` + `.test.tsx`
- Create: `frontend/src/components/admin/realty-groups/AdminSlGroupDriftRow.tsx` + `.test.tsx`
- Create: `frontend/src/components/admin/realty-groups/AdminGroupAuditTab.tsx` + `.test.tsx`
- Modify: `frontend/src/components/admin/realty-groups/AdminRealtyGroupDetailPage.tsx` — render the new tabs

**Spec references:** §7.

- [ ] **Step 1: Compose each tab** using the hooks from Task 33.

`AdminGroupSuspensionsTab` shows current suspension status (pill) + history table + "Issue suspension/ban" button → opens `AdminGroupSuspensionModal`.

`AdminGroupSuspensionModal` form: reason dropdown, notes textarea, timed-vs-permanent toggle, conditional `expiresAt` datetime input, "also bulk-suspend listings" checkbox.

`AdminGroupBulkListingsTab` lists `ADMIN_GROUP_BULK`-cause `listing_suspensions` for the group with their suspended-at + countdown-to-48h-cancel; "Suspend all active listings" + "Reinstate all" buttons.

`AdminGroupReportsTab` lists `realty_group_reports` for this specific group.

`AdminGroupSlGroupsTab` lists SL group registrations with drift status + per-row actions (Recheck / Ack drift / Force unregister).

`AdminGroupAuditTab` pre-filters the audit endpoint by `entityType=REALTY_GROUP&entityId=<this group's publicId>`.

- [ ] **Step 2: Each tab has its own component test** with MSW mocks.

- [ ] **Step 3: Wire tabs into the detail page** (`AdminRealtyGroupDetailPage.tsx`).

- [ ] **Step 4: Verify + commit**

```bash
cd frontend && npm run verify && cd ..
git add frontend/src
git commit -m "feat(realty-f): admin realty-group detail tabs"
```

---

## Task 36: Admin reports queue page

**Files:**
- Create: `frontend/src/app/admin/realty-groups/reports/page.tsx` (`export const dynamic = "force-dynamic"`)
- Create: `frontend/src/components/admin/realty-groups/AdminGroupReportsQueuePage.tsx` + `.test.tsx`
- Create: `frontend/src/components/admin/realty-groups/AdminGroupReportDetailPage.tsx` + `.test.tsx`
- Create: `frontend/src/app/admin/realty-groups/reports/[publicId]/page.tsx`

**Spec references:** §7, §12.2, §12.3.

- [ ] **Step 1: Page + component** with status filter + paginated list.

- [ ] **Step 2: Tests** for happy paths, filter application, and resolve/dismiss buttons.

- [ ] **Step 3: Add Reports nav link** in the admin shell (next to existing "Realty Groups" link).

- [ ] **Step 4: Verify + commit**

---

## Task 37: Public ReportGroupModal

**Files:**
- Create: `frontend/src/components/realty/ReportGroupModal.tsx` + `.test.tsx`
- Modify: `frontend/src/app/realty/groups/[publicId]/page.tsx` (or whatever the public group page is) — add the "Report group" button + modal integration

**Spec references:** §7, §12.1.

- [ ] **Step 1: Modal form** with reason dropdown + details textarea + submit button.

- [ ] **Step 2: Tests** for happy path, validation errors, rate-limited 429 toast, already-reported 409 toast.

- [ ] **Step 3: Integration on public group page** — show the button only for authenticated non-member users.

- [ ] **Step 4: Verify + commit**

---

## Task 38: Leader bulk commission edit drawer

**Files:**
- Create: `frontend/src/components/realty/group/BulkMemberCommissionEditDrawer.tsx` + `.test.tsx`
- Modify: `frontend/src/components/realty/group/MembersTab.tsx` — add the "Bulk edit commission rates" button

**Spec references:** §7, §15.1.

- [ ] **Step 1: Drawer** with one row per member showing the existing rate + an editable `CommissionRateInput` (from E).

- [ ] **Step 2: "Save all" button** triggers `useBulkCommissionEdit` mutation. On success: close drawer + invalidate the group query.

- [ ] **Step 3: Tests** for happy path, validation failure, server-side 400.

- [ ] **Step 4: Verify + commit**

---

## Task 39: Commission analytics page

**Files:**
- Create: `frontend/src/app/realty/groups/[publicId]/analytics/commissions/page.tsx` (`force-dynamic`)
- Create: `frontend/src/components/realty/analytics/GroupCommissionAnalyticsPage.tsx` + `.test.tsx`
- Create: `frontend/src/components/realty/analytics/MemberCommissionBars.tsx` + `.test.tsx`

**Spec references:** §7, §15.2.

- [ ] **Step 1: Table with sortable columns** (member, lifetime L$, last-30-day L$) + bar chart below using simple Tailwind divs.

- [ ] **Step 2: Empty state** when no data.

- [ ] **Step 3: Tests** for happy path, empty state, permission-denied (non-leader user).

- [ ] **Step 4: Verify + commit**

---

## Task 40: SL group registration UI simplification (founder-terminal only)

**Files (modify):**
- `frontend/src/components/realty/slgroup/RegisterSlGroupModal.tsx` — drop method radio group
- `frontend/src/components/realty/slgroup/SlGroupVerificationInstructionsCard.tsx` — founder-terminal only
- `frontend/src/types/realty.ts` — drop ABOUT_TEXT from `SlGroupVerifyMethod` union (already covered by Task 21 if done before; verify)
- Existing `.test.tsx` files — update to remove ABOUT_TEXT cases

**Spec references:** §14.3.

- [ ] **Step 1: Trim the UI to a single-path flow.**

- [ ] **Step 2: Update existing tests.**

- [ ] **Step 3: Verify + commit**

---

## Task 41: README sweep

**Files (modify):**
- `README.md` — add "Sub-project F: Admin Moderation" section after the E section

- [ ] **Step 1: Append the F section**

Document:
- Five-slice toolkit (suspension entity, bulk listing suspend with 48h timer, fraud-flag extension, group reports, SL-group admin).
- New admin pages (Suspensions / Bulk-Listings / Reports / SL-Groups / Audit tabs on the detail page; standalone reports queue page).
- Re-verify cadence + drift detection.
- About-text verification path removed.
- Parser bug fixed.
- Configurable: `slpa.realty.group-bulk-suspend.auto-cancel-hours` (default 48), `slpa.realty.sl-group.reverify-cadence-days` (default 30).

- [ ] **Step 2: Commit**

---

## Task 42: DEFERRED_WORK.md sweep

**Files (modify):**
- `docs/implementation/DEFERRED_WORK.md`

**Spec references:** §22.

- [ ] **Step 1: Mark E-deferred items resolved**

In the "Realty Groups: SL-group-owned listings (Sub-project E)" section, remove or strike the entries closed by F:
- Admin re-verify of an SL group registration
- Admin force-unregister of an SL group
- Re-verify cadence for verified SL groups
- Bulk per-member commission-rate edit
- Per-member commission-rate analytics dashboard

- [ ] **Step 2: Append F-deferred items**

New section "Realty Groups: Admin Moderation (Sub-project F, 2026-05-12)":
- Realtime ban broadcast / forced-logout WebSocket for groups — deferred (same as User-side).
- Report-threshold notification fan-out — deferred (same as listing-side).
- Per-listing admin-suspend timer — kept indefinite by direction; could unify later.
- Dedicated group-reviews page — `GroupRatingBadge` links to leader's user-reviews page as stopgap.
- Reputation-driven bid-eligibility gating — Phase 8 feature, deferred.
- Reverse-search by SL group UUID for ban evasion — deferred fraud-signal item.
- `AdminActionType` UI label localization — twelve new action types render raw enum names; no localization for now.

- [ ] **Step 3: Commit**

---

## Task 43: FOOTGUNS.md additions

**Files (modify):**
- `docs/implementation/FOOTGUNS.md`

- [ ] **Step 1: Add new footguns**

- **Pre-F SL group About-text verification rows were re-labelled as FOUNDER_TERMINAL at migration time.** V28 normalizes `verified_via = 'ABOUT_TEXT'` rows to `'FOUNDER_TERMINAL'`. The verification stays valid (the SL group was confirmed to exist + a marker was found), but the audit trail is slightly fictionalized — those rows weren't actually founder-terminal-verified. Document as a known artifact; no remediation planned.
- **SL group page parser was broken from E ship date to F ship date.** The `.details h1` / `.details p.desc` selectors that worked against live HTML were not the ones E shipped (E used `meta name="groupname"` / `meta name="charter"` which don't exist on the real page). Founder UUID extraction was always correct. Visible footprint: any `aboutText` field on `GroupPageData` returned `null` between E and F — only impacts code paths that read `aboutText`, which were primarily the About-text verification poll task (now removed in F).
- **48 h bulk-suspend timer does not have a UI countdown on the seller side.** Sellers see "Auction paused by admin" but no countdown to auto-cancel. If sellers complain about surprise cancellations, add a countdown indicator. Currently relies on the seller seeing the notification at suspend time + checking back.

- [ ] **Step 2: Commit**

---

## Task 44: Postman collection updates

**Files:** the Postman collection lives in the Postman workspace (collection id `8070328-cf3e1190-cb71-4af3-8e2e-70c82dfee288`). Updates done via the Postman MCP server.

**Spec references:** project convention (Postman is the canonical manual-test surface).

- [ ] **Step 1: Add new requests under `SLPA / Realty Groups`**

- `POST /realty-groups/{groupPublicId}/reports` (public — "Report group")

- [ ] **Step 2: Add new requests under `SLPA / Admin / Realty Groups`**

- `GET / POST / DELETE` for `/admin/realty-groups/{publicId}/suspensions[/...]`
- `POST` for `/admin/realty-groups/{publicId}/listings/suspend-all` and `reinstate-all`
- `GET / POST` for `/admin/realty-groups/reports[/...]`
- `POST` for `/admin/realty-groups/{publicId}/sl-groups/{slGroupPublicId}/recheck` and `ack-drift`
- `DELETE` for `/admin/realty-groups/{publicId}/sl-groups/{slGroupPublicId}?force=true`
- Test scripts chain `groupPublicId`, `suspensionPublicId`, `groupReportPublicId`, etc.

- [ ] **Step 3: Add `PATCH /realty-groups/{publicId}/members/commission-rates` under `SLPA / Realty Groups`**

- [ ] **Step 4: Add `GET /realty-groups/{publicId}/analytics/commissions` under `SLPA / Realty Groups`**

- [ ] **Step 5: Variables update**

Ensure `groupSuspensionPublicId`, `groupReportPublicId`, `bulkActionId` are threaded through environment variables for chained requests.

- [ ] **Step 6: Commit (note: Postman is external; commit a small `docs/superpowers/specs/...` note or the README sweep already covers this — no local files to add).**

---

## Task 45: Final PR

- [ ] **Step 1: Run full backend tests**

```bash
cd backend && ./mvnw test
```

- [ ] **Step 2: Run full frontend tests + verify**

```bash
cd frontend && npm test && npm run verify
```

- [ ] **Step 3: Open PR into `dev`** (NOT main):

```bash
gh pr create --base dev --head feat/realty-groups-admin-moderation \
  --title "feat(realty): sub-project F — admin moderation" \
  --body "$(cat <<'EOF'
## Summary
- Group suspension + ban (one entity, audit trail, hourly expiry job)
- Bulk listing force-suspend with 48 h auto-cancel timer (configurable)
- Group fraud flagging via existing AdminFraudFlagService polymorphism
- User-submitted reports against groups + admin queue + reporter visibility
- SL-group admin: drift-detecting re-verify (periodic + on-demand), force-unregister with cascade
- Per-member bulk commission edit + commission analytics page
- Group reputation/rating display
- Audit log filter extension
- Parser bug fix + About-text verification path removal
- V28 Flyway migration; rollback documented

Closes E-deferred items (admin re-verify, force-unregister, cadence, bulk-commission edit, commission analytics).

## Test plan
- [ ] Backend tests pass
- [ ] Frontend tests + verify pass
- [ ] Manual: suspend a group, confirm guard blocks create-listing / withdraw / member-changes
- [ ] Manual: bulk-suspend listings, advance clock past 48 h, confirm auto-cancel + refund
- [ ] Manual: submit a report, see it in admin queue, resolve + dismiss
- [ ] Manual: SL group recheck + ack drift + force-unregister cascade
- [ ] Manual: bulk commission edit + analytics page
EOF
)"
```

The user holds standing authorization for the dev merge only. Do NOT open a dev → main PR without explicit per-task authorization.

End of plan.
