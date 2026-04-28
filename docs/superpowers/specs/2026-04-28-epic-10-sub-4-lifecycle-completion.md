# Epic 10 Sub-spec 4 — Lifecycle Completion

**Spec date:** 2026-04-28
**Branch:** `task/10-sub-4-lifecycle-completion`
**Builds on:** sub-specs 1 (admin role + JWT + audit log + admin shell), 2 (ban/report/user-mgmt + AdminAuctionService primitives + admin user-detail page), 3 (escrow & infrastructure admin tooling)
**Target:** PR against `dev`, do NOT auto-merge

---

## 1. Goal

Close Epic 10. Ship the four remaining "lifecycle" admin and user-facing capabilities deferred from earlier epics:

1. **Account deletion** — `DELETE /me` currently returns 501; users have no in-app way to delete their account, and admins have no way to delete one on behalf of a user.
2. **Admin audit log viewer** — `admin_actions` table is populated by sub-specs 1, 2, and 3 but has no read surface; admins can't review historical actions in-app.
3. **`ESCROW_TRANSFER_REMINDER` scheduler** — publisher exists from Epic 09 sub-spec 1 but no `@Scheduled` job calls it.
4. **`REVIEW_RESPONSE_WINDOW_CLOSING` scheduler** — same pattern; if Epic 09 didn't ship the publisher, sub-spec 4 adds it alongside the cron.

After this sub-spec lands, Epic 10 is closed.

## 2. Scope summary

**One new admin page:**
- `/admin/audit-log` — paginated table + filters + CSV export

**Existing user-facing surface extended:**
- `/settings` (or wherever the canonical user settings/profile editor lives — implementer locates via grep) — adds a "Delete account" section at the bottom

**Existing admin surface extended:**
- `/admin/users/{id}` (sub-spec 2) — adds a "Delete user" admin button + modal + audit-logged

**Capabilities (4):**
1. Account deletion — soft-delete model, cascade matrix, dual triggers (self-service via settings; admin-driven via `/admin/users/{id}`)
2. Admin audit log viewer — paginated table, filter set, inline JSONB expansion, CSV export endpoint
3. `ESCROW_TRANSFER_REMINDER` scheduler — cron daily 09:00 UTC, fires existing publisher, once-per-escrow guarantee via `Escrow.reminderSentAt`
4. `REVIEW_RESPONSE_WINDOW_CLOSING` scheduler — cron daily 09:05 UTC (offset to avoid DB lock contention with the escrow scheduler), once-per-review guarantee via `Review.responseClosingReminderSentAt`

**Execution pattern:** lean Subagent-Driven Development matching sub-specs 1-3. Sonnet implementers, skip formal reviewers, spot-verify between tasks via diff inspection, subagent stages files, parent commits. Push branch + open PR against `dev`, do NOT auto-merge.

## 3. Architecture overview

```
                            ┌──────────────────────────────────────┐
                            │              /admin                  │
                            │  Sidebar (existing) + Audit log      │
                            └──────────────────────────────────────┘
                                            │
                                            ▼
                                  /admin/audit-log (new)
                                            │
                                  AdminAuditLogService
                                  ├── list (paginated)
                                  └── exportCsv (streaming)
                                            │
                                            ▼
                                  admin_actions table (read-only)

                            ┌──────────────────────────────────────┐
                            │             /settings                │
                            │  Existing profile sections + new     │
                            │  DeleteAccountSection at the bottom  │
                            └──────────────────────────────────────┘
                                            │
                                            ▼
                                  DELETE /api/v1/users/me
                                            │
                                  UserDeletionService.deleteSelf
                                            │
                                            ▼
                                  Cascade matrix (single transaction)

                            ┌──────────────────────────────────────┐
                            │         /admin/users/{id}            │
                            │  Existing user-detail page + new     │
                            │  "Delete user" button (admin)        │
                            └──────────────────────────────────────┘
                                            │
                                            ▼
                                  DELETE /api/v1/admin/users/{id}
                                            │
                                  UserDeletionService.deleteByAdmin
                                            │
                                            ▼
                                  Same cascade matrix +
                                  USER_DELETED_BY_ADMIN audit row

                            ┌──────────────────────────────────────┐
                            │     Background schedulers (cron)     │
                            ├──────────────────────────────────────┤
                            │ EscrowTransferReminderScheduler      │
                            │ ReviewResponseWindowClosingScheduler │
                            └──────────────────────────────────────┘
                                            │
                                            ▼
                                  Existing publishers fire
                                  (NotificationPublisher.escrowTransferReminder /
                                   .reviewResponseWindowClosing)
```

**Reuse from prior epics:**
- `admin_actions` audit table (sub-spec 1) — write/read both
- `AdminActionService.record(...)` (sub-spec 1) — admin-driven deletion writes audit row
- `AdminExceptionHandler` (sub-specs 1+2) — new deletion exception handlers
- `NotificationPublisher.escrowTransferReminder(...)` (Epic 09 sub-spec 1) — already exists; sub-spec 4 wires the scheduler
- `NotificationPublisher.reviewResponseWindowClosing(...)` (Epic 09 sub-spec 1) — implementer verifies presence; if missing, sub-spec 4 adds it
- Existing token-version JWT staleness check — bumping `User.tokenVersion` invalidates outstanding access tokens
- `AdminActionTypeCheckConstraintInitializer` pattern (sub-spec 3) — sync new enum value to Postgres check constraint

## 4. Data model

### 4.1 New columns on existing entities

#### `User` extensions
```java
@Column(name = "deleted_at")
private OffsetDateTime deletedAt;
```

**Existing constraint adjustment:** `User.email` is currently `UNIQUE NOT NULL`. Soft-delete needs to null `email` so the address is freed for re-registration. Change to:
```java
@Column(unique = true)  // nullable; multiple NULLs allowed by default in Postgres
private String email;
```

**Implementer verification:** the existing `User.java` may declare `@Column(nullable = false)` on email — relax to nullable. Postgres' default behavior allows multiple NULLs in a unique index, which is what we want (multiple soft-deleted users can have NULL email simultaneously).

If the implementer finds that the project uses a partial unique index for some reason, `@Index` annotations or an explicit constraint initializer may be needed. Exercise this with a test that soft-deletes two users and asserts both rows persist without unique-constraint violation.

#### `Escrow` extension
```java
@Column(name = "reminder_sent_at")
private OffsetDateTime reminderSentAt;
```

Once-per-escrow guarantee for `ESCROW_TRANSFER_REMINDER`.

#### `Review` extension
```java
@Column(name = "response_closing_reminder_sent_at")
private OffsetDateTime responseClosingReminderSentAt;
```

Once-per-review guarantee for `REVIEW_RESPONSE_WINDOW_CLOSING`. The Review entity's existing field name conventions may differ; implementer adapts.

### 4.2 New enum values

- `AdminActionType.USER_DELETED_BY_ADMIN` — admin-driven deletion only. **Do NOT add `USER_DELETED_SELF`** — `admin_actions` is admin-only; self-deletion is evident from `User.deletedAt IS NOT NULL` with no corresponding `USER_DELETED_BY_ADMIN` row, and an INFO log line provides the audit trail.

### 4.3 No new entities

Sub-spec 4 ships no new entities. All capabilities ride on existing tables.

### 4.4 Constraint sync

Run `AdminActionTypeCheckConstraintInitializer` (existing pattern from sub-spec 3) to propagate the new `USER_DELETED_BY_ADMIN` value to the Postgres check constraint on `admin_actions.action_type`. If the initializer is currently a hardcoded list of values, append the new entry.

## 5. Account deletion domain

### 5.1 State model

Soft-delete only. No hard-delete in Phase 1.

```
            ┌──────────────────────────────────────┐
            │     Active user (deletedAt = null)   │
            └──────────────────────────────────────┘
                              │
              admin or self triggers DELETE
                              │
                              ▼
            ┌──────────────────────────────────────┐
            │   Preconditions checked              │
            │   - active auctions (seller)         │
            │   - open escrows (winner OR seller)  │
            │   - active high bids                 │
            │   - active proxy bids                │
            └──────────────────────────────────────┘
                              │
                fail (any) ───┴─── pass
                  │                  │
                  ▼                  ▼
            409 with structured   Single transaction:
            payload listing       1. Scrub identity fields
            blocking entities     2. Set deletedAt + demote role
                                  3. Bump tokenVersion
                                  4. Hard-delete subscribed entities
                                     (saved searches, watchlists,
                                      notif prefs, in-app notifs,
                                      pending review obligations)
                                  5. Delete refresh tokens
                                  6. (admin path) write audit row
                                  7. (self path) INFO log
                                  8. Commit
                                            │
                                            ▼
                              ┌──────────────────────────────────────┐
                              │ Soft-deleted user (deletedAt set,    │
                              │ identity scrubbed, slAvatarUuid      │
                              │ retained, login disabled)            │
                              └──────────────────────────────────────┘
```

**Re-registration is allowed** with the same email after deletion (email is nulled, so the unique constraint doesn't block). Re-verifying the same SL avatar UUID is also allowed by JPA semantics (the deleted user retains it, but if their `deletedAt IS NOT NULL`, they don't count for the active uniqueness check the verification flow uses — implementer verifies the existing verification flow's check shape).

### 5.2 Cascade matrix

**Preconditions (block deletion until resolved by user/admin):**

| Condition | What blocks |
|---|---|
| Active auctions (seller) | `Auction` rows where `seller = user` AND `status ∈ {ACTIVE, ESCROW_PENDING, ESCROW_FUNDED, TRANSFER_PENDING}`. Returns 409 `ACTIVE_AUCTIONS` with `auctionIds: number[]`. |
| Open escrows | `Escrow` rows where (`winnerUserId = user` OR `auction.seller = user`) AND `state ∈ {FUNDED, TRANSFER_PENDING, DISPUTED, FROZEN}`. Returns 409 `OPEN_ESCROWS` with `escrowIds: number[]`. |
| Active high bids | `Auction` rows where `currentHighBidder = user` AND auction is still active. Returns 409 `ACTIVE_HIGH_BIDS` with `auctionIds: number[]`. |
| Active proxy bids | `ProxyBid` rows where `bidder = user` AND `status = ACTIVE`. Returns 409 `ACTIVE_PROXY_BIDS` with `proxyBidIds: number[]`. **Critical:** without this, proxies fire after deletion and create orphan bid rows referencing a deleted user. |

**Cascades on confirmed delete (single transaction):**

| Entity | Action |
|---|---|
| `User.email` | nulled (frees email for re-registration) |
| `User.displayName` | replaced with `"Deleted user #{id}"` (preserves uniqueness if the column has one; FK targets render readably) |
| `User.bio`, `User.avatarKey` | nulled |
| `User.slAvatarUuid` | **retained** (preserves ban-check coverage; preventing the re-register evasion vector); SL avatar UUIDs are game-platform identifiers, not real-name PII |
| `User.deleted_at` | set to `now()` |
| `User.role` | forced to `USER` (any admin role demoted) |
| `User.tokenVersion` | bumped (invalidates outstanding access tokens via existing JWT staleness check) |
| Refresh tokens | hard-deleted |
| Active bids (user wasn't high bidder) | preserved (auction history intact, attribution renders as "Deleted user") |
| Pending review-write obligations (Epic 08) | dropped — the deleted user no longer owes a review |
| Existing reviews (as reviewer or reviewee) | preserved with anonymized attribution |
| Saved searches, watchlists, notification preferences | hard-deleted |
| In-app notifications (recipient = this user) | hard-deleted |
| Cancellation logs as seller | preserved (admin audit) |
| Ban records | unaffected (live independently on SL UUID + IP) |
| `admin_actions` rows where this user was actor or target | preserved (admin audit) |

**Trigger surfaces:**

| Path | Auth | Audit |
|---|---|---|
| Self-service: `DELETE /api/v1/users/me` body `{password}` | own auth + password re-entry | INFO log: `"User {id} deleted their account via self-service"` — **no admin_actions row** |
| Admin-driven: `DELETE /api/v1/admin/users/{id}` body `{adminNote}` | `hasRole('ADMIN')` | `USER_DELETED_BY_ADMIN` audit row with adminNote in details |

**Idempotency:**
- Calling delete on an already-deleted user returns **410 Gone** with code `USER_ALREADY_DELETED`.

### 5.3 Backend services

#### `UserDeletionService` (new)

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class UserDeletionService {

    @Transactional
    public void deleteSelf(Long userId, String password) {
        // 1. Load user; if deletedAt != null → throw UserAlreadyDeletedException (410)
        // 2. Verify password against User.passwordHash (existing PasswordEncoder bean);
        //    fail → throw InvalidPasswordException (403)
        // 3. Run preconditions check (helper); fail → throw 409 with payload
        // 4. Run cascade transaction (helper)
        // 5. log.info("User {} deleted their account via self-service", userId);
    }

    @Transactional
    public void deleteByAdmin(Long userId, Long adminUserId, String adminNote) {
        // 1. Load user; if deletedAt != null → throw UserAlreadyDeletedException
        // 2. Run preconditions check (helper); fail → throw 409 with payload
        // 3. Run cascade transaction (helper)
        // 4. adminActionService.record(adminUserId, USER_DELETED_BY_ADMIN, USER, userId,
        //    adminNote, Map.of("targetUserId", userId, "adminNote", adminNote));
    }

    private void runCascade(User user) {
        // Single-transaction mutations per the cascade matrix above
    }

    private void checkPreconditions(User user) {
        // Run all 4 precondition queries in parallel-friendly order; collect each
        // failing class into a structured exception with the IDs payload
    }
}
```

#### Exception classes (new)

- `UserAlreadyDeletedException` (410)
- `InvalidPasswordException` (403)
- `ActiveAuctionsException` carrying `List<Long> auctionIds` (409)
- `OpenEscrowsException` carrying `List<Long> escrowIds` (409)
- `ActiveHighBidsException` carrying `List<Long> auctionIds` (409)
- `ActiveProxyBidsException` carrying `List<Long> proxyBidIds` (409)

All implement a common `DeletionPreconditionException` interface so the exception handler can produce a uniform error payload shape.

#### Exception handler additions

In `AdminExceptionHandler` (or a new `UserDeletionExceptionHandler` package-scoped to the `user` package — implementer chooses based on existing patterns):

```java
@ExceptionHandler(UserAlreadyDeletedException.class)
public ResponseEntity<AdminApiError> handleAlreadyDeleted(...) {
    return ResponseEntity.status(HttpStatus.GONE)
        .body(AdminApiError.of("USER_ALREADY_DELETED", ex.getMessage()));
}

@ExceptionHandler(InvalidPasswordException.class)
public ResponseEntity<AdminApiError> handleInvalidPassword(...) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(AdminApiError.of("INVALID_PASSWORD", ex.getMessage()));
}

@ExceptionHandler(ActiveAuctionsException.class)
public ResponseEntity<DeletionPreconditionError> handleActiveAuctions(ActiveAuctionsException ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(new DeletionPreconditionError("ACTIVE_AUCTIONS", ex.getMessage(),
            Map.of("auctionIds", ex.getAuctionIds())));
}

// ... and similar for the other 3 precondition exceptions
```

`DeletionPreconditionError` is a small record-style DTO: `{ code, message, blockingEntities: Map<String, List<Long>> }`.

### 5.4 Backend endpoints

```
DELETE /api/v1/users/me
  Body: { "password": "..." }
  Auth: caller's own JWT
  204 on success
  403 INVALID_PASSWORD
  409 ACTIVE_AUCTIONS / OPEN_ESCROWS / ACTIVE_HIGH_BIDS / ACTIVE_PROXY_BIDS (with payload)
  410 USER_ALREADY_DELETED

DELETE /api/v1/admin/users/{id}
  Body: { "adminNote": "..." }  (1-500 chars)
  Auth: hasRole('ADMIN')
  204 on success
  404 USER_NOT_FOUND
  409 same precondition matrix as self-service
  410 USER_ALREADY_DELETED
```

### 5.5 Frontend

#### Self-service: `DeleteAccountSection`

Add as the bottom section of the existing settings/profile page (implementer locates the canonical surface — likely `frontend/src/app/settings/page.tsx` or `frontend/src/app/profile/page.tsx`). Component:

- Collapsed: red-tinted accordion header "Delete account" with a brief subtitle ("Permanently delete your account and scrub your identity from the platform")
- Expanded:
  - Warning copy in a `bg-error-container` block: "This is irreversible. Your auctions and bids may remain visible as 'Deleted user'. You can register a new account at any time with the same email after deletion."
  - Password input field
  - "Delete my account" button (destructive variant)
- On submit → mutation to `DELETE /api/v1/users/me`. On 409 with `DeletionPreconditionError` payload, render the precondition list inline:
  > "You have 2 active listings: [Beachfront 1024m²], [Sansara Lakefront]. Cancel them first."
  with each listing as a clickable link to `/auction/{id}`.

**Mutation `onSuccess` order (critical — prevents 401 race):**
```typescript
onSuccess: () => {
  clearAccessTokenRef();         // 1. nuke in-memory token first
  queryClient.clear();           // 2. drop all cached queries
  router.push('/goodbye');       // 3. then navigate
}
```

**New public route:** `/goodbye` — no `RequireAuth` wrapper. Static page: "Your account has been deleted. You can register a new one at any time." + Register button.

#### Admin-driven: "Delete user" button on `/admin/users/{id}`

Add to the existing user-detail page (sub-spec 2). Pattern matches existing destructive admin actions (e.g., the demote action from sub-spec 2):

- Button in the destructive-actions section (red border, secondary visual weight) → opens modal
- Modal:
  - Warning copy
  - "Reason / admin note" textarea (required, 1-500 chars)
  - Confirm button (disabled until note is filled)
- On submit → mutation to `DELETE /api/v1/admin/users/{id}`. On 409, render the precondition list inline (same shape as self-service).
- **Admin onSuccess flow:**
  ```typescript
  onSuccess: () => {
    qc.invalidateQueries({ queryKey: adminQueryKeys.users() });
    toast.success('User deleted');
    router.push('/admin/users');  // back to list — detail page is now meaningless
  }
  ```

#### Hooks

- `useDeleteSelf()` — mutation, calls `userApi.deleteSelf(password)`
- `useDeleteUser(userId)` — mutation, calls `adminApi.users.delete(userId, adminNote)`

## 6. Audit log viewer

### 6.1 Backend

```java
@Service
@RequiredArgsConstructor
public class AdminAuditLogService {

    private final AdminActionRepository repo;
    private final UserRepository userRepo;

    @Transactional(readOnly = true)
    public Page<AdminAuditLogRow> list(AdminAuditLogFilters filters, int page, int size) {
        // Build a Specification<AdminAction> from filters; query with PageRequest
        // sorted by occurredAt DESC. Map to AdminAuditLogRow including
        // admin email lookup (join or separate userRepo lookup).
    }

    @Transactional(readOnly = true)
    public Stream<AdminAuditLogRow> exportCsvStream(AdminAuditLogFilters filters) {
        // Stream all rows matching filters (no pagination). Service returns a
        // Stream; controller writes to response output stream as CSV.
    }
}
```

### 6.2 Filters

`AdminAuditLogFilters`:
- `actionType: AdminActionType?` (single-select; null = all)
- `targetType: AdminActionTargetType?` (single-select)
- `adminUserId: Long?` (resolved from a free-text email input on the frontend; backend takes the ID)
- `from: OffsetDateTime?` (inclusive)
- `to: OffsetDateTime?` (inclusive)
- `q: String?` (free-text substring match in `notes`)

### 6.3 DTO

```java
public record AdminAuditLogRow(
        Long id,
        OffsetDateTime occurredAt,
        AdminActionType actionType,
        Long adminUserId,
        String adminEmail,
        AdminActionTargetType targetType,
        Long targetId,
        String notes,
        Map<String, Object> details) {
}
```

### 6.4 Endpoints

```
GET /api/v1/admin/audit-log
  ?actionType=&targetType=&adminUserId=&from=&to=&q=&page=&size=
  Auth: hasRole('ADMIN')
  Returns: Page<AdminAuditLogRow>

GET /api/v1/admin/audit-log/export
  ?actionType=&targetType=&adminUserId=&from=&to=&q=
  Auth: hasRole('ADMIN')
  Returns: Content-Type: text/csv
           Content-Disposition: attachment; filename="audit-log-{ISO date}.csv"
           Streamed body, CSV columns:
             timestamp, action, admin_email, target_type, target_id, notes, details_json
```

The CSV `details_json` column is the entire `details` JSONB serialized as a single escaped string (matches what would appear in the inline expansion).

### 6.5 Frontend

**New files (in `frontend/src/app/admin/audit-log/`):**
- `page.tsx`
- `AdminAuditLogPage.tsx` — page shell, hooks `useAuditLog(filters)`
- `AdminAuditLogFilters.tsx` — action type select, target type select, admin email input, date range, q text input
- `AdminAuditLogTable.tsx` — table rendering, target ID rendered as a link via `targetUrlFor(targetType, targetId)` helper
- `AuditLogRowDetails.tsx` — inline expansion panel showing pretty-printed `details` JSONB

**Hooks (in `frontend/src/lib/admin/auditLogHooks.ts`):**
- `useAuditLog(filters: AdminAuditLogFilters)` — `useQuery`
- No `useExportCsv` hook — the download is triggered by composing the same query string and opening it via `<a href download>`. The browser handles the streamed response natively.

**Sidebar:** add `Audit log` to `AdminShell.tsx` at the bottom of the sidebar item list (after-the-fact reference, not daily triage).

**Target URL helper:**
```typescript
function targetUrlFor(type: AdminActionTargetType, id: number): string | null {
  switch (type) {
    case 'AUCTION': return `/auction/${id}`;
    case 'USER': return `/admin/users/${id}`;
    case 'DISPUTE': return `/admin/disputes/${id}`;
    case 'BAN': return `/admin/bans`;            // no per-ban detail page
    case 'WITHDRAWAL': return `/admin/infrastructure`;
    case 'FRAUD_FLAG': return `/admin/fraud-flags?flagId=${id}`;
    case 'REPORT': return `/admin/reports?reportId=${id}`;
    case 'TERMINAL_SECRET': return `/admin/infrastructure`;
    default: return null;
  }
}
```

Pagination matches sub-spec 2 reports queue: 50/page default, prev/next buttons, total count rendered.

## 7. Schedulers

### 7.1 ESCROW_TRANSFER_REMINDER scheduler

#### New column

```java
@Column(name = "reminder_sent_at")
private OffsetDateTime reminderSentAt;
```

on `Escrow`.

#### Repository method

```java
@Query("SELECT e FROM Escrow e " +
       "WHERE e.state = 'FUNDED' " +
       "AND e.fundedAt BETWEEN :rangeStart AND :rangeEnd " +
       "AND e.reminderSentAt IS NULL")
List<Escrow> findEscrowsApproachingTransferDeadline(
    OffsetDateTime rangeStart, OffsetDateTime rangeEnd);
```

#### Scheduler

```java
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "slpa.escrow-transfer-reminder",
                       name = "enabled", matchIfMissing = true)
@Slf4j
public class EscrowTransferReminderScheduler {

    private final EscrowRepository escrowRepo;
    private final NotificationPublisher publisher;
    private final EscrowConfigProperties escrowConfig;  // implementer verifies the
                                                        // existing config bean for
                                                        // transfer-deadline-hours
    private final Clock clock;

    @Scheduled(cron = "${slpa.escrow-transfer-reminder.cron:0 0 9 * * *}", zone = "UTC")
    @Transactional
    public void run() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long windowHours = escrowConfig.getTransferDeadlineHours();

        // Reminder fires when deadline ∈ [now+12h, now+36h]
        // Equivalently, fundedAt ∈ [now − (windowHours−12h), now − (windowHours−36h)]
        // Note: this uses fundedAt directly because there's no transferDeadline
        // column on Escrow — the deadline is computed as fundedAt + windowHours.
        OffsetDateTime rangeStart = now.minusHours(windowHours - 12);
        OffsetDateTime rangeEnd = now.minusHours(windowHours - 36);

        // rangeStart > rangeEnd by construction: rangeStart = now - (windowHours - 12)
        // is later than rangeEnd = now - (windowHours - 36). Swap for the BETWEEN clause:
        List<Escrow> rows = escrowRepo.findEscrowsApproachingTransferDeadline(
            rangeEnd, rangeStart);  // BETWEEN takes lower, upper

        for (Escrow e : rows) {
            publisher.escrowTransferReminder(
                e.getAuction().getSeller().getId(),
                e.getAuction().getId(),
                e.getId(),
                e.getAuction().getTitle(),
                e.getFundedAt().plusHours(windowHours));
            e.setReminderSentAt(now);
            escrowRepo.save(e);
        }

        log.info("EscrowTransferReminderScheduler: reminded {} escrow(s)", rows.size());
    }
}
```

**Implementer verification before writing the scheduler:**
1. Confirm `slpa.escrow.transfer-deadline-hours` (or similarly-named) property exists in `application.yml` and `EscrowConfigProperties` bean. Use the actual property name found.
2. Confirm `NotificationPublisher.escrowTransferReminder(...)` interface signature matches the args used above. Adapt the call if the signature differs (e.g., if it doesn't take `transferDeadline`, omit that arg).
3. Confirm the `@ConditionalOnProperty` `matchIfMissing=true` default keeps the bean active in production but tests can opt-out.

### 7.2 REVIEW_RESPONSE_WINDOW_CLOSING scheduler

#### Implementer verification step (BEFORE writing the scheduler)

Check whether `NotificationPublisher.reviewResponseWindowClosing(...)` already exists from Epic 09 sub-spec 1. Search:

```bash
grep -rn "reviewResponseWindowClosing\|REVIEW_RESPONSE_WINDOW_CLOSING" backend/src/main/java/
```

- If the publisher method + `NotificationCategory.REVIEW_RESPONSE_WINDOW_CLOSING` enum value exist → reuse them. The deferred-ledger entry suggests they should.
- If they DO NOT exist → sub-spec 4 adds them, following the established pattern (interface signature + impl body + `NotificationDataBuilder.reviewResponseWindowClosing(...)` helper + `SlImLinkResolver` case routing to `/auction/{auctionId}#reviews` or `/dashboard/reviews` — implementer picks based on existing review-link routing). This is small additional scope; bake into the same task as the scheduler.

#### New column

```java
@Column(name = "response_closing_reminder_sent_at")
private OffsetDateTime responseClosingReminderSentAt;
```

on `Review`. Adapt to the existing Review entity's column-naming convention.

#### Repository method

The Review entity probably exposes `responseDeadline` (or computes from `submittedAt + responseWindowHours`). Implementer verifies and writes the predicate accordingly:

```java
// If responseDeadline column exists:
@Query("SELECT r FROM Review r " +
       "WHERE r.responseDeadline BETWEEN :rangeStart AND :rangeEnd " +
       "AND r.responseClosingReminderSentAt IS NULL " +
       "AND r.responseText IS NULL")  // or whatever indicates "no response yet"
List<Review> findReviewsApproachingResponseClose(
    OffsetDateTime rangeStart, OffsetDateTime rangeEnd);

// If only submittedAt + responseWindowHours config:
//   apply the same algebraic rewrite as the escrow scheduler.
```

#### Scheduler

```java
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "slpa.review-response-reminder",
                       name = "enabled", matchIfMissing = true)
@Slf4j
public class ReviewResponseWindowClosingScheduler {

    private final ReviewRepository reviewRepo;
    private final NotificationPublisher publisher;
    private final Clock clock;

    @Scheduled(cron = "${slpa.review-response-reminder.cron:0 5 9 * * *}", zone = "UTC")
    // 5-minute offset from EscrowTransferReminderScheduler so they don't lock the
    // same DB rows simultaneously.
    @Transactional
    public void run() {
        OffsetDateTime now = OffsetDateTime.now(clock);

        // Reminder fires when responseDeadline ∈ [now+24h, now+48h]
        OffsetDateTime rangeStart = now.plusHours(24);
        OffsetDateTime rangeEnd = now.plusHours(48);

        List<Review> rows = reviewRepo.findReviewsApproachingResponseClose(
            rangeStart, rangeEnd);

        for (Review r : rows) {
            publisher.reviewResponseWindowClosing(/* args per existing signature */);
            r.setResponseClosingReminderSentAt(now);
            reviewRepo.save(r);
        }

        log.info("ReviewResponseWindowClosingScheduler: reminded {} review(s)", rows.size());
    }
}
```

### 7.3 Test pattern for both schedulers

Match sub-spec 3's reconciliation cron pattern: `@TestPropertySource(properties = "slpa.escrow-transfer-reminder.enabled=false")` (and similar for the review scheduler) so `@SpringBootTest` doesn't fire the cron during unrelated tests.

For targeted unit tests, the pattern is:

```java
@SpringBootTest
@Transactional
@TestPropertySource(properties = {
    "slpa.escrow-transfer-reminder.enabled=true",   // override default disable here
    "slpa.review-response-reminder.enabled=false"
})
class EscrowTransferReminderSchedulerTest {
    // Seed Escrow rows at various fundedAt offsets.
    // Call scheduler.run() directly.
    // Assert NotificationPublisher mock was called for the right rows
    //  + reminderSentAt was set on each.
}
```

## 8. Notifications

### 8.1 Categories shipping in this sub-spec (0 new — wires existing)

Sub-spec 4 ships **no new notification categories**. Both schedulers fire publishers that exist (or will be added if the implementer's verification step finds them missing — small additional scope, no design change).

### 8.2 Audit log entries

| Action type | When |
|---|---|
| `USER_DELETED_BY_ADMIN` | Admin deletes a user via `/admin/users/{id}` |

Self-service deletion does NOT write an `admin_actions` row (admin_actions is admin-only). It writes an INFO log line: `"User {id} deleted their account via self-service"`.

## 9. Logging discipline

Per memory `feedback_background_job_ops_logging.md` — background jobs surface their state in INFO logs even when no admin is looking.

| Job / event | Cadence | Log level | Format |
|---|---|---|---|
| `EscrowTransferReminderScheduler.run()` | once daily 09:00 UTC | INFO | `"EscrowTransferReminderScheduler: reminded {n} escrow(s)"` |
| `ReviewResponseWindowClosingScheduler.run()` | once daily 09:05 UTC | INFO | `"ReviewResponseWindowClosingScheduler: reminded {n} review(s)"` |
| `UserDeletionService.deleteSelf()` success | per request | INFO | `"User {id} deleted their account via self-service"` |
| `UserDeletionService.deleteByAdmin()` success | per request | INFO | `"User {id} deleted by admin {adminUserId}: {adminNote}"` |
| Both deletion paths on precondition fail | per failed attempt | INFO | `"Deletion blocked for user {id}: {failingPrecondition} ({n} entities)"` — helps spot users repeatedly hitting preconditions |

## 10. Testing strategy

### 10.1 Backend (matches Epic 10 sub-specs 1-3 patterns)

**Unit tests per service:**
- `UserDeletionServiceTest`:
  - precondition matrix × all 4 blocking scenarios + happy path
  - scrub field assertions (`email == null`, `displayName == "Deleted user #{id}"`, `slAvatarUuid` retained, `bio == null`, `avatarKey == null`)
  - `tokenVersion` bumped
  - refresh tokens deleted
  - subscribed-only entities hard-deleted
  - admin path writes `admin_actions` row; self path does NOT
  - already-deleted user → 410
  - wrong password (self path) → 403
  - re-registration with the same email after delete succeeds (free-the-email assertion)
  - re-registration with same SL avatar UUID hits ban if one existed (no evasion vector)
- `AdminAuditLogServiceTest`:
  - filter combinations (action type, target type, admin, date range, q substring)
  - pagination (default size + custom size + last-page partial)
  - CSV streaming returns correct headers + escaping for embedded JSON / commas / quotes / newlines
- `EscrowTransferReminderSchedulerTest`:
  - window math (rows just inside → reminded; rows outside → skipped)
  - once-per-escrow (running twice doesn't re-remind already-marked rows)
  - zero matches → no publisher calls + INFO log with `0`
  - multiple matches → all reminded in same run
- `ReviewResponseWindowClosingSchedulerTest`: same shape

**Integration tests:** `@SpringBootTest` per new controller, focused on auth gate + happy/sad paths. CSV export endpoint tested via `MockMvc` asserting `Content-Type` + `Content-Disposition` + body.

**High-leverage interaction tests:**
- Self-delete → token-version bump invalidates outstanding access tokens
- Self-delete → next refresh attempt fails (token row gone)
- Admin-delete writes `admin_actions` row with the admin note in `details`
- Both schedulers respect `@TestPropertySource` disable

### 10.2 Frontend

**Vitest hooks:** `useAuditLog`, `useDeleteSelf`, `useDeleteUser`.

**Component tests with MSW:**
- Audit log page: filters compose into query string; table renders; expansion toggles; target links resolve to the right route per `targetUrlFor`
- Delete-account section: precondition error rendering with structured payload; password validation; success → `/goodbye` redirect; onSuccess order (clear token → clear cache → navigate)
- Admin delete-user modal: same precondition error rendering; admin note required; success → invalidate `["admin", "users"]` + redirect to `/admin/users`

**Pre-existing flake awareness:** `AuctionRepositoryOwnershipCheckTest` flakes occasionally; `CodeDisplay.test.tsx` Clipboard cast — both unrelated, don't touch.

## 11. Out of scope

### 11.1 Deferred to ledger (indefinite)
- Hard-delete / GDPR right-to-erasure path (Phase 1 YAGNI; manual DB script if it ever comes up)
- 7-day soft-delete recovery window (Phase 1 ships irreversible delete with strong warning + password re-entry)
- "Bulk delete users" admin tool (no operational driver)
- Audit log row-level deletion or redaction (audit logs are immutable in Phase 1)
- Audit log retention / archival policy (Phase 2 — table will accumulate; cleanup is a future ops decision)
- BOT_POOL_DEGRADED notification (sub-spec 3 deferred — stays open)
- WS reconnect telemetry (Epic 04 deferred entry)
- HMAC-SHA256 per-request auth (Phase 2 hardening)
- Smart regional terminal routing (operational trigger)
- Email change flow (dead — email channel was removed from roadmap; the deferred-ledger entry should be marked dead during the close-out commit)

## 12. Execution model

- Lean Subagent-Driven Development matching sub-specs 1, 2, 3
- Sonnet implementers per task, skip formal spec/quality reviewer subagents, spot-verify between tasks via diff inspection
- **Subagent stages files; parent commits.** This avoids the pre-commit-check-tasks hook contention pattern observed in sub-spec 3.
- After all tasks complete: push branch + open PR against `dev`. **Do NOT auto-merge.**
- Bootstrap admin emails carry over from sub-spec 1.

## 13. Acceptance criteria (per Phase 10 brief + sub-spec 4 specifics)

- ☐ User can delete their own account via `/settings`, with password re-entry
- ☐ Admin can delete a user via `/admin/users/{id}`, with admin-note required
- ☐ Soft-delete preserves all FK references; auctions/bids/reviews continue to render with anonymized attribution
- ☐ Active auctions / open escrows / active high bids / active proxy bids all block deletion with structured 409 payloads
- ☐ Email is freed for re-registration after deletion
- ☐ SL avatar UUID is retained (ban enforcement covers re-register attempts)
- ☐ Token version is bumped on deletion (outstanding access tokens invalidated)
- ☐ Self-delete onSuccess clears token + cache before navigating (no 401 race)
- ☐ Admin can browse `/admin/audit-log` with all filters working + CSV export
- ☐ ESCROW_TRANSFER_REMINDER fires once per escrow as the deadline approaches
- ☐ REVIEW_RESPONSE_WINDOW_CLOSING fires once per review as the response window closes
- ☐ Audit log sidebar item is added to `AdminShell.tsx`
- ☐ Capabilities except those explicitly deferred ship behind appropriate auth gates

---

**End of spec.** Implementation plan to follow via the writing-plans skill.
