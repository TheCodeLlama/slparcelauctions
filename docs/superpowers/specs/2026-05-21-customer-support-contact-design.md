# Customer Support Contact

**Date:** 2026-05-21
**Issue:** [#167](https://github.com/TheCodeLlama/slparcelauctions/issues/167)
**Status:** Awaiting user review.

## 1. Goal

A user-facing contact channel for support requests, fully on-platform: logged-in users open a ticket from the header dropdown, admins triage from a queue mirroring the existing dispute / fraud-flag admin surfaces, and the back-and-forth conversation happens as a thread in the SLParcels site itself. Notifications keep both sides aware without email — in-app feed entries plus SL IM for the user side.

The feature exists because today there is no on-platform way for a user to reach the platform owner with an account / bidding / escrow problem. The unstated fallback ("IM SLPABot1 in-world or DM the operator on Discord") is fine for outliers but is invisible to most users and lossy as a paper trail. This builds the missing primary channel.

## 2. Data model

Three new tables in a new `support` package.

### `support_tickets`

```
id                       BIGSERIAL PRIMARY KEY
public_id                UUID NOT NULL UNIQUE
user_id                  BIGINT NOT NULL REFERENCES users(id)
subject                  VARCHAR(160) NOT NULL
category                 VARCHAR(32) NOT NULL          -- ACCOUNT | BIDDING | LISTING | ESCROW | WALLET | OTHER
status                   VARCHAR(16) NOT NULL DEFAULT 'OPEN'  -- OPEN | RESOLVED
assigned_admin_id        BIGINT REFERENCES users(id)   -- nullable
last_message_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
last_message_author      VARCHAR(16) NOT NULL          -- USER | ADMIN
resolved_at              TIMESTAMPTZ                   -- nullable; set on resolve, cleared on reopen
-- BaseMutableEntity columns: created_at, updated_at, version
```

Constraints:
- `CHECK (status IN ('OPEN','RESOLVED'))`
- `CHECK (category IN ('ACCOUNT','BIDDING','LISTING','ESCROW','WALLET','OTHER'))`
- `CHECK (last_message_author IN ('USER','ADMIN'))`

Indexes:
- `(user_id, status)` — "my tickets" list
- `(status, last_message_author, last_message_at DESC) WHERE status = 'OPEN'` — admin queue hot path; the partial-index `WHERE` clause shrinks the index to only currently-open rows
- `(assigned_admin_id) WHERE assigned_admin_id IS NOT NULL` — "Mine" filter

State model rationale: only two persisted states; the "needs admin attention" admin-queue filter is derived from `last_message_author = 'USER' AND status = 'OPEN'`. No `AWAITING_USER` or `AWAITING_ADMIN` columns — drift between such columns and reality is the failure mode they introduce.

### `support_ticket_messages`

```
id                BIGSERIAL PRIMARY KEY
public_id         UUID NOT NULL UNIQUE
ticket_id         BIGINT NOT NULL REFERENCES support_tickets(id) ON DELETE CASCADE
author_user_id    BIGINT NOT NULL REFERENCES users(id)
author_role       VARCHAR(16) NOT NULL                  -- USER | ADMIN (snapshot at write time)
body              TEXT NOT NULL                          -- 1..10000 chars (app-level)
visible_to_user   BOOLEAN NOT NULL DEFAULT TRUE          -- false = admin internal note
-- BaseMutableEntity columns: created_at, updated_at, version
```

Constraints:
- `CHECK (author_role IN ('USER','ADMIN'))`
- App layer enforces `LENGTH(body) BETWEEN 1 AND 10000` (no DB CHECK because TEXT max is unbounded; bean validation `@Size(min=1,max=10000)` plus an explicit service guard).

Indexes:
- `(ticket_id, created_at)` — thread render path

`author_role` is snapshotted at write time so promotion / demotion later doesn't rewrite historical authorship. Internal notes (`visible_to_user = false`) are an admin-only scratch surface and are filtered out of every user-facing query.

### `support_ticket_attachments`

```
id            BIGSERIAL PRIMARY KEY
public_id     UUID NOT NULL UNIQUE
message_id    BIGINT NOT NULL REFERENCES support_ticket_messages(id) ON DELETE CASCADE
storage_key   VARCHAR(255) NOT NULL                     -- S3 object key
mime_type     VARCHAR(64) NOT NULL
size_bytes    INTEGER NOT NULL
width         INTEGER
height        INTEGER
created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
```

Indexes:
- `(message_id)`

Storage key convention: `support-attachments/{userPublicId}/{uuid}.{ext}` inside the existing `slpa.storage.bucket` (same bucket as dispute evidence and listing photos). `width` / `height` are post-validation snapshots from `ImageUploadValidator` so the frontend can size thumbnails without re-reading the file.

## 3. Apply / lifecycle logic

### Create ticket (user, authed)

`POST /api/v1/me/support-tickets`:

1. Rate limiter (`SupportTicketRateLimiter`, keyed on `userId`) checks the user has < 5 new tickets created in the last 60 minutes. If over → 429 `ProblemDetail` with `code = RATE_LIMITED`. Replies on existing tickets are NOT counted toward this cap.
2. Validate `subject` (1-160 chars), `category` (one of the six enum values), `body` (1-10000 chars).
3. Validate `attachmentKeys[]` (0-3 entries), each must resolve to a temp upload that belongs to this user (see §3 Attachments).
4. In a single `@Transactional`:
   - Insert `support_tickets` row with `status = OPEN`, `last_message_author = USER`, `last_message_at = now`, `assigned_admin_id = null`.
   - Insert the initial `support_ticket_messages` row with `author_role = USER`, `visible_to_user = true`.
   - Promote the temp attachment uploads into `support_ticket_attachments` rows referencing the new message id.
5. Fire `supportTicketOpened(...)` notification fan-out → in-app feed entry to every admin user (no SL IM to admins).
6. Return 200 + `SupportTicketDto`.

### Reply (user OR admin)

Two endpoints, slightly different rules:

- `POST /api/v1/me/support-tickets/{publicId}/messages` — user reply
- `POST /api/v1/admin/support-tickets/{publicId}/messages` — admin reply

Shared body shape: `{ body, attachmentKeys?: string[], internalNote?: boolean }`. `internalNote` is admin-only — when set true by an admin, the message persists with `visible_to_user = false`. When a user request includes `internalNote = true`, reject with 400 `INTERNAL_NOTE_FROM_USER`.

Algorithm:
1. Lookup ticket by public id. User path: must own it (else 404 `UNKNOWN_TICKET`). Admin path: must exist.
2. Validate body (1-10000) and attachments.
3. Insert `support_ticket_messages` row with `author_role = USER` or `ADMIN` per path, `visible_to_user` set from the request (default true; user requests are ALWAYS true).
4. Insert attachment rows.
5. Update parent ticket:
   - `last_message_at = now`
   - `last_message_author = USER | ADMIN` per path
   - **If status was `RESOLVED` AND the author is the user** → flip status to `OPEN`, clear `resolved_at`. This is the auto-reopen path.
6. Fire notifications based on author + visibility:
   - User reply (always public) → `supportTicketUserReplied(...)` fan-out to all admins.
   - Admin reply with `visible_to_user = true` → `supportTicketAdminReplied(...)` to the ticket owner.
   - Admin reply with `visible_to_user = false` (internal note) → NO notifications. Internal notes are silent.
7. Return 200 + `SupportTicketMessageDto`.

### Mark resolved (admin)

`POST /api/v1/admin/support-tickets/{publicId}/resolve`:

1. Look up ticket (must exist).
2. If already `RESOLVED` → return current state, no-op. Idempotent.
3. Set `status = RESOLVED`, `resolved_at = now`.
4. Insert a synthetic system message: `author_user_id = acting admin id`, `author_role = ADMIN`, `visible_to_user = true`, `body = "Marked resolved by admin"`. The user sees the resolution in the thread without confusion.
5. Update `last_message_at = now`, `last_message_author = ADMIN`.
6. Fire `supportTicketResolved(...)` notification → ticket owner (in-app + SL IM).

### Reopen (admin manual)

`POST /api/v1/admin/support-tickets/{publicId}/reopen`:

Rare path. The normal reopen route is user-reply auto-reopen. Manual reopen is for admin error-correction.

1. Set `status = OPEN`, `resolved_at = null`.
2. Insert synthetic system message: `body = "Reopened by admin"`, `visible_to_user = true`, `author_role = ADMIN`.
3. Update `last_message_at` and `last_message_author = ADMIN`.
4. Fire NO notification (admin reopened, not user; nothing surprising for the user to know).

### Assign / unassign (admin)

`POST /api/v1/admin/support-tickets/{publicId}/assign`, body `{ adminPublicId?: string | null }`:

1. Resolve `adminPublicId` to a user id (must have `ROLE_ADMIN`). Null = unassign.
2. Set `assigned_admin_id`. No message inserted (process flag, not a conversation event). No notification.

### Category change (admin)

`PATCH /api/v1/admin/support-tickets/{publicId}` body `{ category: SupportTicketCategory }`:

Rare admin correction path. Updates `category` on the ticket; no message inserted; no notification. Validation rejects values outside the six-element enum.

## 4. Attachments

Reuses the dispute-evidence upload primitive at the service layer. New surface at the API layer for naming clarity.

### Pre-upload endpoint

`POST /api/v1/me/support-tickets/attachments`:

Multipart form upload. Authed users only.

1. Validate MIME (`image/png | image/jpeg | image/webp | image/gif`).
2. Validate size (<= 5 MiB).
3. Validate dimensions via `ImageUploadValidator` (max 4096x4096; rejects polyglots).
4. Store at `support-attachments/pending/{userPublicId}/{uuid}.{ext}` in the configured S3 bucket. The `pending/` prefix is intentional — a bucket lifecycle rule (configured by Terraform separately, with documentation in this spec's deploy notes) deletes any object under `support-attachments/pending/` older than 1 day. That gives us automatic orphan cleanup without an application sweeper.
5. Cache pending metadata in Redis under key `support:upload:{attachmentKey} → { userId, storageKey, mime, size, width, height }` with TTL = `slpa.support.attachments.pending-ttl-seconds` (default 3600 = 1 hour). The `attachmentKey` is a fresh UUID, NOT the storage key — keeps the storage path hidden from the client.
6. Return `{ attachmentKey: string }`.

When a create or reply call references `attachmentKeys`, the service:
1. For each key, read the Redis entry. Missing entry → 400 `ATTACHMENT_NOT_FOUND` (the upload either expired or was never made).
2. Confirm `userId` on the entry matches the caller. Mismatch → 403 `NOT_OWNER`.
3. Inside the same transaction as the message insert: copy the S3 object from `support-attachments/pending/{userPublicId}/{uuid}.{ext}` to `support-attachments/{userPublicId}/{messageId}/{uuid}.{ext}` (S3 server-side copy), then delete the pending object. Insert the `support_ticket_attachments` row pointing at the promoted storage key.
4. Delete the Redis entry.

Failure mode: if the S3 copy succeeds but the DB insert fails, the bucket lifecycle rule will eventually evict the orphaned promoted object (impossible — the rule is on `pending/` only). To prevent durable orphan: wrap the copy in a `try/catch` that, on any post-copy failure, deletes the promoted object before re-throwing. This is best-effort cleanup; durable orphan recovery is a separate concern (post-MVP audit).

No application-level sweeper is required for the pending storage — the S3 lifecycle rule handles it. Redis TTL handles pending metadata expiration. The `slpa.support.attachment-sweeper-cron` key from §9 is therefore removed; see the updated §9.

### Max attachments per message

3. Enforced at create + reply time. Validation error code `INVALID_ATTACHMENT` with detail `"max 3 attachments per message"`.

### Image fetch

`GET /api/v1/support-tickets/attachments/{publicId}` — issues a 5-minute pre-signed URL to the S3 object. Authorization: the requester must either be the ticket's owner OR an admin. Other authenticated users get 403.

## 5. Backend endpoints

### User-facing

| Method | Path | Notes |
|---|---|---|
| GET | `/api/v1/me/support-tickets` | paged list; filters: `status`, `q` (subject substring); sort: `last_message_at DESC` |
| GET | `/api/v1/me/support-tickets/{publicId}` | full detail + visible messages + attachments; 404 if not owner |
| POST | `/api/v1/me/support-tickets` | body `{ subject, category, body, attachmentKeys?: string[] }`; rate-limited |
| POST | `/api/v1/me/support-tickets/{publicId}/messages` | reply; `internalNote` ignored / rejected if true |
| POST | `/api/v1/me/support-tickets/attachments` | multipart upload; returns `{ attachmentKey }` |
| GET | `/api/v1/support-tickets/attachments/{publicId}` | pre-signed S3 URL; owner or admin |

### Admin-facing

| Method | Path | Notes |
|---|---|---|
| GET | `/api/v1/admin/support-tickets` | paged queue; filters: `status`, `category`, `assignee` (`mine` / `unassigned` / `userPublicId`), `last_author` (`USER` / `ADMIN`), `q`; sort `last_message_at DESC` |
| GET | `/api/v1/admin/support-tickets/{publicId}` | full detail including internal notes + ALL attachments |
| POST | `/api/v1/admin/support-tickets/{publicId}/messages` | admin reply; `internalNote` flag respected |
| POST | `/api/v1/admin/support-tickets/{publicId}/resolve` | mark resolved; idempotent |
| POST | `/api/v1/admin/support-tickets/{publicId}/reopen` | manual reopen; idempotent |
| POST | `/api/v1/admin/support-tickets/{publicId}/assign` | body `{ adminPublicId?: string \| null }` |
| PATCH | `/api/v1/admin/support-tickets/{publicId}` | body `{ category?: SupportTicketCategory }`; future-extensible |
| GET | `/api/v1/admin/support-tickets/queue-stats` | returns `{ openNeedingAdminReply, openTotal }`; powers sidebar badge |

All `/admin/**` routes gated by `@PreAuthorize("hasRole('ADMIN')")`. All `/me/**` routes gated by JWT authentication (existing global rule). The `/support-tickets/attachments/{publicId}` route is authenticated but allows admin OR owner.

**LazyInit defense (lesson from coupon PR #388):** every controller method that returns an entity-derived DTO is annotated `@Transactional(readOnly = true)` or `@Transactional` (for writes). The repos' single-row finders use `@EntityGraph(attributePaths = {"messages", "messages.attachments"})` where the controller will need the thread. Tests follow the dual-pattern from `AdminCouponLazyInitRegressionTest`: in-tx integration coverage plus a non-`@Transactional` regression test class per controller surface.

**Error handling:** `SupportTicketException` with a `SupportTicketError` enum (`UNKNOWN_TICKET`, `NOT_OWNER`, `INTERNAL_NOTE_FROM_USER`, `RATE_LIMITED`, `INVALID_ATTACHMENT`, `INVALID_CATEGORY`, `ATTACHMENT_NOT_FOUND`), mapped to RFC 9457 `ProblemDetail` by a new `SupportTicketExceptionHandler` (`@RestControllerAdvice` scoped to the support package, mirroring `CouponExceptionHandler`). Status mapping:
- `UNKNOWN_TICKET`, `ATTACHMENT_NOT_FOUND` → 404
- `NOT_OWNER` → 403
- `INTERNAL_NOTE_FROM_USER`, `INVALID_ATTACHMENT`, `INVALID_CATEGORY` → 400
- `RATE_LIMITED` → 429

## 6. Notifications

Four new `NotificationCategory` enum values, all in `NotificationGroup.SYSTEM`:

```
SUPPORT_TICKET_ADMIN_REPLIED       -- user-facing
SUPPORT_TICKET_RESOLVED            -- user-facing
SUPPORT_TICKET_OPENED              -- admin-facing
SUPPORT_TICKET_USER_REPLIED        -- admin-facing
```

Four new methods on `NotificationPublisher`:

```java
void supportTicketAdminReplied(long userId, UUID ticketPublicId,
                                 String subject, String adminDisplayName);
void supportTicketResolved(long userId, UUID ticketPublicId, String subject);
void supportTicketOpened(List<Long> adminUserIds, UUID ticketPublicId,
                          String subject, String submitterDisplayName, String category);
void supportTicketUserReplied(List<Long> adminUserIds, UUID ticketPublicId,
                                String subject, String submitterDisplayName);
```

Channel routing:

| Category | In-app | Email | SL IM |
|---|---|---|---|
| `SUPPORT_TICKET_ADMIN_REPLIED` | yes | NO | yes |
| `SUPPORT_TICKET_RESOLVED` | yes | NO | yes |
| `SUPPORT_TICKET_OPENED` | yes | NO | NO |
| `SUPPORT_TICKET_USER_REPLIED` | yes | NO | NO |

Per-channel bodies are inline strings in `NotificationPublisherImpl` (same pattern as every other notification today — there is no template engine in the project):

- `SUPPORT_TICKET_ADMIN_REPLIED`: `"{adminDisplayName} replied to your support ticket: {subject}. View at slparcels.com/support/{publicId}"`
- `SUPPORT_TICKET_RESOLVED`: `"Your support ticket has been marked resolved: {subject}. View at slparcels.com/support/{publicId}"`
- `SUPPORT_TICKET_OPENED` (in-app only, no SL IM body needed): `"{submitterDisplayName} opened a new {category} support ticket: {subject}"`
- `SUPPORT_TICKET_USER_REPLIED` (in-app only): `"{submitterDisplayName} replied to a support ticket: {subject}"`

`SlImLinkResolver` gets four entries — admin categories link to `/admin/support/{publicId}`, user categories to `/support/{publicId}`.

Existing `notify_email` / `notify_sl_im` user prefs already gate the SYSTEM group's channel routing; no new user-pref columns required.

Internal notes (`visible_to_user = false`) fire NO notification on any channel.

## 7. Frontend UI

### Header / nav (Q5 = B)

- `UserMenuDropdown`: add a "Support" item between "Wallet" and "Sign out". Visible only when authed (the dropdown itself is only rendered for authed users).
- `MobileMenu`: add "Support" to the footer link row alongside Contact / About / Terms.

### `/support` page (Q6 = A — list first)

- File: `frontend/src/app/support/page.tsx` with `export const dynamic = "force-dynamic"` per the SSR caveat.
- Renders a server-component shell wrapping a client `<SupportTicketList />`.
- Top bar: page title "Support", "New ticket" primary button (top-right).
- Filter bar: status dropdown (All / Open / Resolved). No category filter on the user side (small per-user N expected).
- Table columns: Subject (link), Category badge, Status pill + "admin replied" sub-label when `lastMessageAuthor=ADMIN` AND status is `OPEN`, Last updated (relative).
- Empty state: centered card with "No tickets yet. Need help? Click 'New ticket'." + same button.

### `/support/new` page

- File: `frontend/src/app/support/new/page.tsx`.
- Form sections: Subject (Input, max 160), Category (Select, six options), Message (Textarea, max 10000 chars + char counter), Attachments (drop zone up to 3, reusing `DisputeEvidenceUpload`-style component).
- Submit → `useCreateSupportTicket()` mutation → on success, redirect to `/support/{publicId}`.
- Add `Textarea` UI primitive at `frontend/src/components/ui/Textarea.tsx` (does not exist today). Same shape as `Input`: `label`, `helperText`, `error`, `rows`. Sibling test required.

### `/support/[publicId]` page

- File: `frontend/src/app/support/[publicId]/page.tsx`. Next.js 16 async-params route (`params: Promise<{ publicId: string }>`).
- Header: subject, category badge, status pill, last-updated timestamp.
- Message list: alternating bubbles. User-authored messages right-aligned with a subtle brand-color background; admin-authored messages left-aligned with surface-raised background. Each carries author name, role label, timestamp, body, and attachment thumbnails. Attachment click → lightbox with pre-signed URL fetched on demand.
- Reply composer: Textarea + attachment drop zone (max 3) + Send button.
- If `status = RESOLVED`: composer remains rendered but with a leading helper text "Replying will reopen this ticket." Composer functions normally.

### Admin queue (`/admin/support`)

- File: `frontend/src/app/admin/support/page.tsx`.
- Mirrors `AdminDisputeListPage` shape.
- Table columns: Subject (link), Submitter (displayName + sl avatar), Category, Status pill, Last activity (relative), Assigned-to (displayName or "Unassigned").
- Filter row: Status select, Category select, Assignee select (Mine / Unassigned / All), Last-author select (Needs admin reply / Waiting on user / All), Subject search.
- URL-synced filters via `useSearchParams`.
- Sidebar link "Support" added to `AdminShell.tsx`, alphabetically between "Reports" and "Users". Badge displays `queueStats.openNeedingAdminReply`, polled every 30s via `useAdminSupportQueueStats()`.

### Admin detail (`/admin/support/[publicId]`)

- Full thread including internal notes (rendered with a distinct warning-bg + "Internal note" label so they never look like a normal reply).
- Composer with a "Send" button + an "Internal note" checkbox. When checked, the bubble preview swaps to the internal-note styling so the admin sees what they're about to send.
- Top bar: Resolve / Reopen button (mutually exclusive, status-driven), "Assign to me" / "Unassign" buttons, Category dropdown for the rare admin correction.

### Notification feed integration

The existing `NotificationBell` + feed renderer handles the four new categories — they just need:
- A category icon mapping (use the `MessageSquare` lucide icon for all four).
- A deep-link function that returns `/support/{publicId}` for user-side and `/admin/support/{publicId}` for admin-side categories.
- A short-body renderer string (mirroring the SL IM body, minus the absolute URL).

## 8. Migration plan

Single Flyway migration `V42__support_tickets.sql` per spec §2. Three new tables in one transaction; no changes to existing tables. Migration number is **V42** (V41 = coupon codes is the current latest).

Hibernate `validate` mode requires the matching JPA entities ship in the same commit; the implementation plan will sequence migration + entity creation in Task 1.

## 9. Configuration

New `application.yml` keys:

```yaml
slpa:
  support:
    rate-limit:
      tickets-per-hour: 5
    attachments:
      max-per-message: 3
      max-file-bytes: 5242880
      allowed-mime-types: "image/png,image/jpeg,image/webp,image/gif"
      pending-ttl-seconds: 3600
```

Reuses (no new values):
- `slpa.storage.bucket`, `slpa.storage.endpoint`, etc. — same S3 wiring as dispute evidence and listing photos.
- `slpa.notifications.sl-im.*` — existing dispatcher.
- Existing Redis connection — used for attachment pending cache.

No new SSM Parameter Store secrets.

## 10. Testing

### Backend unit

- `SupportTicketRateLimiterTest` — per-user 5/hour bucket; replies uncounted; sliding 60-minute window.

### Backend integration (`@SpringBootTest + @AutoConfigureMockMvc`)

- `MeSupportTicketControllerIntegrationTest`:
  - GET list filters by status and q; pagination correct
  - GET detail returns only `visible_to_user = true` messages; admin internal notes are absent from user response
  - GET detail rejects with 404 when caller is not owner
  - POST create happy path; rate limiter returns 429 on the 6th submission within 60 min
  - POST reply with empty body and 10001-char body both rejected (400)
  - POST reply on a `RESOLVED` ticket auto-reopens (status flips back to OPEN; resolved_at cleared)
  - POST reply rejects `internalNote = true` from user with 400 `INTERNAL_NOTE_FROM_USER`
- `AdminSupportTicketControllerIntegrationTest`:
  - GET queue filters work independently: status, category, assignee (mine/unassigned/specific user), last_author, q
  - GET detail returns ALL messages including internal notes
  - POST reply happy path (public + internal); internal note fires NO `supportTicketAdminReplied` notification (mocked publisher)
  - POST resolve transitions status, writes system message, fires `supportTicketResolved`
  - POST reopen flips back, writes system message, fires NO notification
  - POST assign / unassign updates `assigned_admin_id`
  - PATCH category updates value
  - Non-admin token receives 403 on every admin endpoint
- `SupportTicketAttachmentIntegrationTest`:
  - Pre-upload happy path returns `attachmentKey`
  - Rejects file > 5 MiB
  - Rejects MIME outside the allowlist
  - Rejects > 3 keys per message at create / reply
  - Promotion happy path: pending object is copied to the message-scoped path, pending object is deleted, Redis entry cleared
  - Promotion failure (mocked DB failure post-copy) deletes the just-copied object so we don't leak
  - GET signed URL works for owner; 403 for non-owner non-admin; OK for admin
  - Expired Redis entry (deliberately set TTL = 0 for the test) returns 400 ATTACHMENT_NOT_FOUND on promotion
- `SupportTicketLazyInitRegressionTest` (non-`@Transactional` test class, mirrors `AdminCouponLazyInitRegressionTest`):
  - GET `/me/support-tickets/{id}` outside enclosing tx → 200, messages array populated
  - GET `/admin/support-tickets/{id}` outside enclosing tx → 200, internal notes + attachments present
- `SupportTicketNotificationTest`:
  - Admin reply (visible_to_user=true) fires `supportTicketAdminReplied` to ticket owner only
  - User reply fires `supportTicketUserReplied` to all admins
  - Resolve fires `supportTicketResolved` to ticket owner
  - Internal-note admin reply fires NO notification
- `SupportTicketReopenIntegrationTest`:
  - End-to-end: user creates → admin replies → admin resolves → user replies → ticket OPEN again, resolved_at cleared, admins receive `supportTicketUserReplied`

### Frontend (Vitest + RTL)

- `Textarea.test.tsx` (new UI primitive) — render, label, error state, char counter integration.
- `SupportTicketList.test.tsx` — empty state, table rendering, status pill + sub-label, pagination, filter URL sync.
- `NewSupportTicketForm.test.tsx` — required-field validation, body char counter, attachment drop zone max-3 enforcement, submit happy path navigates to detail.
- `SupportTicketThread.test.tsx` — alternating bubble layout; lightbox-on-thumbnail-click; reply composer "will reopen" helper when status=RESOLVED; user view hides admin internal notes (defense in depth).
- `AdminSupportTicketQueue.test.tsx` — filter URL sync, queue-stats badge counter, status pill rendering.
- `AdminSupportTicketDetail.test.tsx` — internal-note checkbox toggles bubble preview style; resolve / reopen button visibility tracks status; assign dropdown happy path.
- `useAdminSupportQueueStats.test.tsx` — polling cadence with `vi.useFakeTimers`.

### Postman

Mirror every endpoint from §5 into the SLPA Postman collection in a new "Support tickets" folder. Variable-chain `supportTicketPublicId`, `supportTicketMessagePublicId`, `supportTicketAttachmentPublicId` across the happy-path admin↔user flow.

### Manual smoke (release checklist)

- User opens "Support" from header dropdown → /support empty state → "New ticket" → fills form → submitted → redirected to detail page.
- Admin signs in → "Support" sidebar item shows badge "1" → opens queue → ticket visible with "Needs admin reply" filter → opens detail → replies.
- User receives in-app notification + (if SL IM prefs on) SL IM → opens thread → sees reply → replies back → admin notified.
- Admin marks resolved → user sees notification + system message in thread.
- User replies on resolved ticket → status flips back to OPEN, admins re-notified.
- Spam test: open 6 tickets in 5 minutes → 6th returns 429 with rate-limit message.
- Attachment test: upload 3 images, all preview, all fetch on lightbox click; 4th attachment rejected.

## 11. Out of scope

Items explicitly NOT in MVP:

- Email notifications. Issue says "email not necessary for MVP"; the four notification categories above route in-app and SL IM only.
- Anonymous ticket submission. Q2 = authed-only.
- File attachments other than images. Q9 = images only.
- Auto-close timer on resolved tickets. Tickets stay where they are until a user replies (auto-reopen) or admin manual action.
- SLA / response-time tracking.
- Admin canned-response templates.
- Multi-admin assignment. Single nullable `assignedAdminId` only.
- User-side ticket merging or splitting.
- Public knowledge-base or FAQ articles.
- Search across ticket bodies (only subject substring search in MVP).

## 12. Decision log

Captured during brainstorming (2026-05-21):

- **Threaded back-and-forth** (Q1 = A). Rejected fire-and-forget — the "email not necessary" hint implies users expect a reply somewhere; if not email, then in-app threads.
- **Logged-in only** (Q2 = A). Rejected anon with magic-link — contradicts the no-email guidance. Rejected anon one-way — defeats the threaded model. Login-locked-out users fall back to in-world IM or the operator's Discord; signage on the login page handles the edge case without a feature.
- **Six fixed categories** (Q3 = B). Rejected free-form (admin triage burden) and free tags (filter noise).
- **Two states, last-author derived** (Q4 = B). Rejected multi-state machines as overengineered for the volume; rejected single-state + manual archive as too sparse.
- **Help in user dropdown** (Q5 = B). Rejected inline header link (crowds main nav) and floating action button (sets a live-chat expectation we can't meet).
- **List-first /support page** (Q6 = A). Rejected new-ticket-default (existing tickets buried) and two-tab layout (more clicks for both paths).
- **In-app + SL IM for user; in-app only for admin** (Q7 = B + Yes). Rejected in-app-only on user side (under-surfaces replies) and full three-channel (contradicts email guidance).
- **Internal notes via `visible_to_user` flag** (Q8 = A). Rejected separate notes table (over-decomposed) and no notes (forces external scratchpad).
- **Image attachments up to 3 per message** (Q9 = B). Rejected text-only (rare but real "show me the screenshot" need) and any-MIME (security risk for executables).
- **Per-user rate limit, 5 new tickets/hour, replies uncapped** (Q10 = A). Rejected per-IP (shared NAT false positives) and no-limit (trust-without-verify).
- **Optional assignment** (Q11 = B). Rejected shared-only (no coordination signal when team grows) and required-assignment (overhead while team is one admin).

## 13. Deploy notes

### S3 lifecycle rule

The pending-attachment cleanup relies on an S3 bucket lifecycle rule that the backend does not create automatically. Configure once via Terraform (or AWS console for dev):

- Rule name: `support-attachments-pending-cleanup`
- Bucket: `slpa.storage.bucket` (production: `slpa-prod-uploads`)
- Prefix filter: `support-attachments/pending/`
- Action: Expire current versions of objects 1 day after creation
- Status: Enabled

Without this rule, orphaned pending uploads remain in S3 indefinitely (they are also not referenced by any DB row, so they don't appear in any application listing). The application-side Redis cache TTL expires the metadata after 1 hour, but the actual S3 objects are only cleaned by this lifecycle rule.

Promoted attachments live under `support-attachments/{messageId}/{attachmentKey}.{ext}` (NOT under `pending/`) and are NOT subject to the lifecycle rule. They persist for the life of the ticket.
