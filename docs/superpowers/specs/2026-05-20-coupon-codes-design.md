# Coupon Codes / Specials

**Date:** 2026-05-20
**Issue:** [#165](https://github.com/TheCodeLlama/slparcelauctions/issues/165)
**Status:** Awaiting user review.

## 1. Goal

A coupon system that lets admins create promotional codes which discount either the listing fee or the commission rate (or both) for users who redeem them. Codes can be public, allowlisted to specific users, or auto-granted to users who joined within a date window. The same template supports time-bounded, count-bounded, or both-bounded grants.

The feature exists to give the platform a lever for onboarding promos ("free listings for 30 days when you join in May"), VIP arrangements ("0% commission on next 5 sales for user X"), and bulk discount campaigns ("first 100 users get 50% off listing fees"). It must not interfere with any existing fee or commission code paths beyond the snapshot fields already on `Auction`.

## 2. Data model

Three new tables plus two FKs on `auctions`.

### `coupons` — the template

```
id                     BIGSERIAL PRIMARY KEY
public_id              UUID NOT NULL UNIQUE
code                   VARCHAR(64) NOT NULL UNIQUE         -- the redemption key
description            TEXT                                -- internal admin note
-- LIFETIME (at least one required):
duration_days          INTEGER                             -- grant remains usable for N days from grant creation
use_count              INTEGER                             -- grant can power N listing activations
-- REDEMPTION CONTROLS:
redeemable_until       TIMESTAMPTZ                         -- admin cutoff for new redemptions
max_total_redemptions  INTEGER                             -- null = unlimited
max_per_user           INTEGER NOT NULL DEFAULT 1
-- AUTO-GRANT:
signup_window_start    DATE
signup_window_end      DATE
-- ADMIN:
active                 BOOLEAN NOT NULL DEFAULT TRUE
notify_on_grant        BOOLEAN NOT NULL DEFAULT TRUE
created_by_user_id     BIGINT NOT NULL REFERENCES users(id)
-- BaseMutableEntity columns: created_at, updated_at, version
```

Constraints:
- `CHECK (duration_days IS NOT NULL OR use_count IS NOT NULL)` — at least one lifetime axis must be set, otherwise the grant is infinite.
- `CHECK ((signup_window_start IS NULL) = (signup_window_end IS NULL))` — both or neither.

Indexes:
- `LOWER(code)` for case-insensitive lookup at redemption.
- `(signup_window_start, signup_window_end) WHERE signup_window_start IS NOT NULL` for the auto-grant matcher.

### `coupon_discounts` — 1:N bundle child

A single coupon can carry multiple discount lines that all apply together. Lifetime + counts live on the parent.

```
id          BIGSERIAL PRIMARY KEY
coupon_id   BIGINT NOT NULL REFERENCES coupons(id) ON DELETE CASCADE
target      VARCHAR(32) NOT NULL                  -- LISTING_FEE | COMMISSION_RATE
op          VARCHAR(32) NOT NULL                  -- OVERRIDE | PERCENT_OFF | FLAT_OFF
value       NUMERIC(12,4) NOT NULL
sort_order  INTEGER NOT NULL DEFAULT 0            -- stable display order in admin UI
```

Interpretation table (`apply(target, op, value)` against the configured default):

| target | op | value | result |
|---|---|---|---|
| LISTING_FEE | OVERRIDE | 0 | fee = L$0 (free) |
| LISTING_FEE | OVERRIDE | 50 | fee = L$50 |
| LISTING_FEE | PERCENT_OFF | 50 | fee = default x 0.5 |
| LISTING_FEE | FLAT_OFF | 25 | fee = max(0, default - 25) |
| COMMISSION_RATE | OVERRIDE | 3.0 | rate = 3% |
| COMMISSION_RATE | OVERRIDE | 0 | rate = 0% (no commission) |
| COMMISSION_RATE | PERCENT_OFF | 50 | rate = default x 0.5 |
| COMMISSION_RATE | FLAT_OFF | 2.0 | rate = max(0, default - 2.0 pts) |

New `op` values can be added by extending the enum + a branch in the calculator. No schema migration required.

### `coupon_allowed_users` — 1:N junction

```
coupon_id  BIGINT NOT NULL REFERENCES coupons(id) ON DELETE CASCADE
user_id    BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE
PRIMARY KEY (coupon_id, user_id)
```

Empty for a given coupon = anyone with the code can redeem. Non-empty = enforced allowlist on redemption.

### `coupon_grants` — per-user instance

Created on redemption, admin direct-grant, or signup-window match.

```
id                BIGSERIAL PRIMARY KEY
public_id         UUID NOT NULL UNIQUE
coupon_id         BIGINT NOT NULL REFERENCES coupons(id)
user_id           BIGINT NOT NULL REFERENCES users(id)
granted_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
expires_at        TIMESTAMPTZ                                -- granted_at + coupon.duration_days, null if duration_days null
remaining_count   INTEGER                                    -- starts at coupon.use_count, null if use_count null
state             VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'      -- ACTIVE | EXHAUSTED | EXPIRED | REVOKED
source            VARCHAR(32) NOT NULL                       -- REDEMPTION | ADMIN_GRANT | SIGNUP_WINDOW
-- BaseMutableEntity columns: created_at, updated_at, version
```

Indexes:
- `(user_id, state)` — hot path for "user's active grants" lookup at listing creation.
- `(expires_at) WHERE state = 'ACTIVE' AND expires_at IS NOT NULL` — for the sweeper.
- `(coupon_id, user_id)` non-unique — supports the `count(*) WHERE coupon_id = ? AND user_id = ?` check that the service layer does before insert to enforce `max_per_user`. No unique constraint here so that `max_per_user > 1` works without a special path.

### `auctions` additions

```
listing_fee_coupon_grant_id   BIGINT REFERENCES coupon_grants(id)
commission_coupon_grant_id    BIGINT REFERENCES coupon_grants(id)
```

Nullable. Stamped onto the auction at listing creation so the activation hook knows which grant(s) to decrement.

## 3. Apply / consume logic

### Resolution at listing creation

`CouponDiscountResolver.resolve(userId)` returns a snapshot:

```java
record DiscountSnapshot(
  long listingFeeLindens,
  BigDecimal commissionRate,
  Long listingFeeCouponGrantId,
  Long commissionCouponGrantId
) {}
```

Algorithm:
1. Load all `CouponGrant` rows where `user_id = $user AND state = 'ACTIVE'`. Filter in memory: drop any with `expires_at < now` (the sweeper hasn't caught them yet) or `remaining_count = 0`.
2. Load each grant's `Coupon.discounts` collection.
3. For `LISTING_FEE`: across every grant's matching discount lines, compute the apply result against the configured default fee (`slpa.listing-fee.amount-lindens`). Pick the line with the lowest result. Tiebreak: prefer the grant with `use_count IS NULL`; then FIFO by `expires_at NULLS LAST, granted_at ASC`. Stamp that grant's id.
4. For `COMMISSION_RATE`: same algorithm against the default commission rate (`slpa.commission.default-rate`, currently 0.05). Stamp that grant's id.
5. Both stamps can point at the same grant when a bundle wins both targets.

### Snapshot onto Auction

`AuctionCreationService` calls `resolve(userId)` exactly once per listing creation and writes:
- `Auction.listingFeeAmt` = `snapshot.listingFeeLindens`
- `Auction.commissionRate` = `snapshot.commissionRate`
- `Auction.listingFeeCouponGrantId` = `snapshot.listingFeeCouponGrantId`
- `Auction.commissionCouponGrantId` = `snapshot.commissionCouponGrantId`

These are immutable for the life of the listing.

### Listing fee payment

`ListingFeePaymentService.acceptPayment()` checks `Auction.listingFeeAmt`:
- If `0`: auto-transition DRAFT to DRAFT_PAID without any terminal payment. Write a `LISTING_FEE_PAYMENT` ledger row with `amount = 0` and a memo referencing the grant's `public_id`. No L$ moves.
- If `> 0`: existing terminal flow, just with the discounted amount.

### Consumption at activation

The transition from DRAFT_PAID (post-verification) to ACTIVE is the consumption point. `AuctionActivationService.activate()` (existing) gets a new hook: for each non-null stamped grant id on the auction, load the grant and decrement `remaining_count` by 1 if non-null. If both stamps point at the same grant, decrement only once. If `remaining_count` reaches 0, transition state to EXHAUSTED. Wrap in the same transaction as the ACTIVE transition.

### Cancellation pre-activation

Any DRAFT, DRAFT_PAID, or VERIFICATION_PENDING cancellation path does **not** touch the stamped grants. The grant ids stay on the auction row for audit, but no count moves. (Per Q5 = A: consume on activation, not creation.)

### Sweeper

`CouponGrantSweeper.sweep()` runs hourly via `@Scheduled(cron = "0 0 * * * *", zone = "UTC")`:

```sql
UPDATE coupon_grants
SET state = 'EXPIRED', updated_at = NOW()
WHERE state = 'ACTIVE'
  AND expires_at IS NOT NULL
  AND expires_at < NOW();
```

Idempotent. The resolver also filters expired grants defensively so the sweeper lag never produces incorrect snapshots.

## 4. Redemption flow

`POST /api/v1/me/coupons/redeem` body `{ code }`:

1. Lookup coupon by `LOWER(code) = LOWER($code)`. Not found: 404 `UNKNOWN_CODE`.
2. Check `active = true`. False: 409 `PAUSED`.
3. Check `redeemable_until` (if set) > now. Stale: 409 `EXPIRED`.
4. Check `max_total_redemptions` (if set) > current grant count for this coupon. Reached: 409 `MAX_REACHED`.
5. Check allowlist: if `coupon_allowed_users` non-empty for this coupon, user must be in it. Otherwise: 403 `NOT_ELIGIBLE`.
6. Check `max_per_user`: count existing grants for `(coupon_id, user_id)`. If `>= max_per_user`: 409 `ALREADY_REDEEMED`.
7. Create grant atomically. Populate `expires_at`, `remaining_count`, `source = REDEMPTION`. Skip notification (`source = REDEMPTION` is silent per Section 6).
8. Return `CouponGrantDto`.

All error responses use the existing `ApiError` shape with a stable `code` field for frontend i18n.

## 5. Auto-grant on signup window

### On coupon save (admin POST `/api/v1/admin/coupons`)

If `signup_window_start` and `signup_window_end` are set, the create-coupon transaction runs:

```sql
INSERT INTO coupon_grants (public_id, coupon_id, user_id, granted_at, expires_at, remaining_count, state, source)
SELECT
  gen_random_uuid(),
  $coupon_id,
  u.id,
  NOW(),
  CASE WHEN $duration_days IS NOT NULL THEN NOW() + ($duration_days || ' days')::INTERVAL ELSE NULL END,
  $use_count,
  'ACTIVE',
  'SIGNUP_WINDOW'
FROM users u
WHERE u.created_at::DATE BETWEEN $signup_window_start AND $signup_window_end
  AND NOT EXISTS (
    SELECT 1 FROM coupon_grants g
    WHERE g.coupon_id = $coupon_id AND g.user_id = u.id
  );
```

Each row created in the backfill emits a `couponGranted` notification per Section 6 (after the transaction commits).

### On user creation

`UserService.create()` gets a post-commit hook `applySignupWindowCoupons(newUser)`:

```java
List<Coupon> matches = couponRepository.findActiveSignupWindowCouponsMatching(
    newUser.getCreatedAt().toLocalDate());
for (Coupon c : matches) {
  if (!grantExists(c.getId(), newUser.getId())) {
    createGrant(c, newUser, Source.SIGNUP_WINDOW);
  }
}
```

The matcher query is:

```sql
SELECT * FROM coupons
WHERE active = TRUE
  AND signup_window_start IS NOT NULL
  AND signup_window_start <= $today
  AND signup_window_end   >= $today
  AND (redeemable_until IS NULL OR redeemable_until > NOW());
```

Both paths share `CouponService.createGrant(coupon, user, source)` so notification + ledger emission is uniform.

## 6. Notifications

A new method on `NotificationPublisher`:

```java
void couponGranted(long userId, UUID couponPublicId, GrantSource source);
```

Fired from `CouponService.createGrant(...)` whenever a grant is created, **except** when `source = REDEMPTION` (the user just typed the code and the wallet UI updates inline; no surprise to notify). REDEMPTION still emits an admin-audit ledger row but no user-facing notification.

Channels (matches existing notification patterns):
- **In-app**: bell-icon feed; deep-link `/wallet#coupons`
- **Email**: template `coupon-granted.html`; subject "You received a coupon"; body lists the bundle's discount lines + expiry
- **SL IM**: short body via the dispatcher bot — `You received a coupon on SLParcels: {CODE} ({summary}). View at slparcels.com/wallet`

User preferences (`UserNotificationPreferences`) gets a new channel key `COUPON_GRANTED`, default on for all three channels. Preferences UI on `/dashboard/notifications` gets a row for it.

## 7. Backend endpoints

### User-facing

| Method | Path | Notes |
|---|---|---|
| GET | `/api/v1/me/coupons` | list active + history grants; paginated `?status=active|history&page=...` |
| POST | `/api/v1/me/coupons/redeem` | body `{ code }`; returns `CouponGrantDto` or error |

### Admin-facing

| Method | Path | Notes |
|---|---|---|
| GET | `/api/v1/admin/coupons` | paged list; filters: `q` (code search), `active`, `discount_target` |
| GET | `/api/v1/admin/coupons/{publicId}` | single coupon + aggregate metrics (`totalGrants`, `activeGrants`, `exhaustedGrants`, `expiredGrants`) |
| POST | `/api/v1/admin/coupons` | create coupon with discounts + allowed users + signup-window in one body; triggers signup-window backfill in the same transaction |
| PATCH | `/api/v1/admin/coupons/{publicId}` | edit `description`, toggle `active`, edit `allowed_users`, edit `notify_on_grant`, edit `redeemable_until`, edit `max_total_redemptions`. Always blocked: `code` (renaming would orphan any external comms already published), and the discount-bundle (rows in `coupon_discounts`). Blocked once `totalGrants > 0`: `duration_days`, `use_count`, `signup_window_start`, `signup_window_end`, `max_per_user`. Block with 409 `IMMUTABLE_FIELD`. |
| DELETE | `/api/v1/admin/coupons/{publicId}` | soft-archive when `totalGrants > 0` (sets `active = false` and `redeemable_until = NOW()`); hard-delete when zero grants |
| GET | `/api/v1/admin/coupons/{publicId}/grants` | paged grants for this coupon; filters: `state`, `source`, `user_id` |
| POST | `/api/v1/admin/coupons/{publicId}/grants` | body `{ userPublicIds: [...] }`; direct-grant to each user; skips code redemption; sets `source = ADMIN_GRANT` |
| POST | `/api/v1/admin/coupons/{publicId}/grants/{grantPublicId}/revoke` | sets grant state REVOKED; subsequent listings ignore it; existing snapshot on activated auctions unchanged |

All admin endpoints are gated by `ROLE_ADMIN` in `SecurityConfig`. Admin-mutation endpoints emit an admin-audit ledger row (existing `AdminAudit` pattern).

## 8. Frontend UI

### Wallet page (`frontend/src/app/wallet/page.tsx`)

New `WalletCouponsCard` rendered directly under the existing balance card, above ledger. Q6 = A.

- Header: "Coupons"
- Redeem row: `<input>` for code + Redeem button. Inline error states from the typed `CouponRedemptionError` codes.
- "Active (N)" section: `CouponGrantCard` for each ACTIVE grant — shows discount-line summary, expires-at (relative), remaining count.
- Collapsible "History (M)" expander: EXHAUSTED + EXPIRED + REVOKED grants.

`useCoupons()` hook wraps `GET /api/v1/me/coupons`; mutation hook `useRedeemCoupon()` wraps `POST /redeem` with optimistic refresh of the list on success.

### Create-listing page (`frontend/src/app/listings/(verified)/create/page.tsx`)

Q6 = A.

Summary block (existing) gets two coupon badges when active:
- Listing fee row: `~~L$100~~ L$0 - WELCOME30` when discounted
- Commission row: `~~5%~~ 3% - SPRING3` when discounted

Below the summary: a "Have a code? Click to redeem" expander. Clicking reveals inline input + Apply. On success, the form re-fetches discount state and the badges update.

The summary block reads from a new server-side endpoint `GET /api/v1/me/listings/prospective-discounts` that calls `CouponDiscountResolver.resolve(userId)` to return what would apply if the user created a listing right now. The page hits this on mount and after a successful redeem.

### Admin

- `/admin/coupons` — list page. Table columns: code, description, status (active/paused/expired/archived), discounts (collapsed pill list), `activeGrants / totalGrants / max_total_redemptions or unlimited`, `redeemable_until`.
- `/admin/coupons/new` — create form. Sections:
  1. **Identity**: code (text + Generate button), description
  2. **Discount bundle**: repeatable rows `[target select, op select, value input]` with "Add another discount" button; remove buttons per row
  3. **Lifetime**: `duration_days`, `use_count`; UI validation requires at least one set
  4. **Redemption controls**: `redeemable_until`, `max_total_redemptions`, `max_per_user`, allowed users (multi-select user-picker with autocomplete on email/sl-username)
  5. **Auto-grant**: `signup_window_start`, `signup_window_end` (both or neither)
  6. **Status**: `active` (default on), `notify_on_grant` (default on)
- `/admin/coupons/{publicId}` — detail page. Tabs: Overview (config + metrics), Grants (paged table + Direct-grant button), Edit (only patchable fields per Section 7)
- Sidebar link "Coupons" added to the admin navigation, alphabetically positioned

## 9. LSL / In-world

No LSL changes required. Coupons are a web-only concept; the in-world surface only sees the final L$ amount on the terminal payment (which is already discounted by the time it reaches the deposit handler). The `LISTING_FEE_PAYMENT` ledger row at L$0 (when fully discounted) is recorded by the backend without any terminal involvement.

## 10. Configuration

New `application.yml` keys (none strictly required — all coupon behavior is data-driven):

```yaml
slpa:
  coupons:
    sweeper-cron: "0 0 * * * *"   # hourly EXPIRED-state sweep, UTC
```

The default listing fee (`slpa.listing-fee.amount-lindens`) and default commission rate (`slpa.commission.default-rate`) remain authoritative defaults. The discount calculator reads them at apply time.

## 11. Migration plan

Single Flyway migration `V37__coupon_codes.sql`. Embeds every schema change from Section 2 in one transaction:

```sql
CREATE TABLE coupons (
  id                     BIGSERIAL PRIMARY KEY,
  public_id              UUID NOT NULL UNIQUE,
  code                   VARCHAR(64) NOT NULL UNIQUE,
  description            TEXT,
  duration_days          INTEGER,
  use_count              INTEGER,
  redeemable_until       TIMESTAMPTZ,
  max_total_redemptions  INTEGER,
  max_per_user           INTEGER NOT NULL DEFAULT 1,
  signup_window_start    DATE,
  signup_window_end      DATE,
  active                 BOOLEAN NOT NULL DEFAULT TRUE,
  notify_on_grant        BOOLEAN NOT NULL DEFAULT TRUE,
  created_by_user_id     BIGINT NOT NULL REFERENCES users(id),
  created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  version                BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT coupon_lifetime_required
    CHECK (duration_days IS NOT NULL OR use_count IS NOT NULL),
  CONSTRAINT coupon_signup_window_paired
    CHECK ((signup_window_start IS NULL) = (signup_window_end IS NULL))
);
CREATE INDEX coupons_code_lower_idx ON coupons(LOWER(code));
CREATE INDEX coupons_signup_window_idx ON coupons(signup_window_start, signup_window_end)
  WHERE signup_window_start IS NOT NULL;

CREATE TABLE coupon_discounts (
  id          BIGSERIAL PRIMARY KEY,
  coupon_id   BIGINT NOT NULL REFERENCES coupons(id) ON DELETE CASCADE,
  target      VARCHAR(32) NOT NULL,
  op          VARCHAR(32) NOT NULL,
  value       NUMERIC(12,4) NOT NULL,
  sort_order  INTEGER NOT NULL DEFAULT 0,
  CONSTRAINT coupon_discount_target_valid
    CHECK (target IN ('LISTING_FEE','COMMISSION_RATE')),
  CONSTRAINT coupon_discount_op_valid
    CHECK (op IN ('OVERRIDE','PERCENT_OFF','FLAT_OFF'))
);
CREATE INDEX coupon_discounts_coupon_id_idx ON coupon_discounts(coupon_id);

CREATE TABLE coupon_allowed_users (
  coupon_id  BIGINT NOT NULL REFERENCES coupons(id) ON DELETE CASCADE,
  user_id    BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  PRIMARY KEY (coupon_id, user_id)
);

CREATE TABLE coupon_grants (
  id                BIGSERIAL PRIMARY KEY,
  public_id         UUID NOT NULL UNIQUE,
  coupon_id         BIGINT NOT NULL REFERENCES coupons(id),
  user_id           BIGINT NOT NULL REFERENCES users(id),
  granted_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  expires_at        TIMESTAMPTZ,
  remaining_count   INTEGER,
  state             VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  source            VARCHAR(32) NOT NULL,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  version           BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT coupon_grant_state_valid
    CHECK (state IN ('ACTIVE','EXHAUSTED','EXPIRED','REVOKED')),
  CONSTRAINT coupon_grant_source_valid
    CHECK (source IN ('REDEMPTION','ADMIN_GRANT','SIGNUP_WINDOW'))
);
CREATE INDEX coupon_grants_user_state_idx ON coupon_grants(user_id, state);
CREATE INDEX coupon_grants_expires_at_idx ON coupon_grants(expires_at)
  WHERE state = 'ACTIVE' AND expires_at IS NOT NULL;
CREATE INDEX coupon_grants_coupon_user_idx ON coupon_grants(coupon_id, user_id);

ALTER TABLE auctions
  ADD COLUMN listing_fee_coupon_grant_id  BIGINT REFERENCES coupon_grants(id),
  ADD COLUMN commission_coupon_grant_id   BIGINT REFERENCES coupon_grants(id);
CREATE INDEX auctions_listing_fee_grant_idx
  ON auctions(listing_fee_coupon_grant_id)
  WHERE listing_fee_coupon_grant_id IS NOT NULL;
CREATE INDEX auctions_commission_grant_idx
  ON auctions(commission_coupon_grant_id)
  WHERE commission_coupon_grant_id IS NOT NULL;
```

Existing data: untouched. The two new `auctions` columns are nullable and default null on existing rows; no backfill needed because grants only exist going forward.

## 12. Testing

### Backend unit tests

- `CouponDiscountCalculatorTest` — covers every `(target, op, value)` interpretation against the configured defaults, including edge cases (negative results clamped to 0, integer-cast issues for fee values, BigDecimal scale for commission)
- `CouponDiscountResolverTest`:
  - No grants returns config defaults
  - Single grant covers one target; other target reads default
  - Single bundle covers both targets in one grant; stamp the same `grant_id` on both
  - Multi-grant tiebreak: same numeric result, no-count grant preferred over use_count grant
  - Multi-grant tiebreak: same result + both no-count, FIFO by `granted_at`
  - EXPIRED / EXHAUSTED / REVOKED grants ignored
  - Expired-by-clock but still ACTIVE-by-DB grants ignored (sweeper lag scenario)
- `CouponGrantSweeperTest` — marks expired ACTIVE grants; idempotent on second run; respects `expires_at IS NULL`

### Backend integration tests (`@SpringBootTest + @AutoConfigureMockMvc`)

- `CouponRedemptionControllerIntegrationTest` — happy path + each error code from Section 4
- `AdminCouponControllerIntegrationTest`:
  - Create coupon with bundle, allowlist, signup-window; verify backfill creates grants for matching pre-existing users; non-matching users get nothing; users already with a grant don't get a duplicate
  - GET list pagination + filters
  - PATCH blocks immutable fields when `totalGrants > 0`; allows when 0
  - DELETE hard-deletes when 0 grants, soft-archives when >0
- `AdminCouponGrantControllerIntegrationTest` — direct-grant to multiple users; revoke transitions state; revoked grant no longer matches in resolver
- `UserCreationCouponHookTest` — new user matching active signup-window gets grant atomically with user create; non-matching window skipped; both `signup_window_start <= today` and `signup_window_end >= today` must hold
- `ListingFeePaymentServiceCouponTest`:
  - L$0 listing fee auto-transitions DRAFT to DRAFT_PAID without terminal hit
  - Partial discount runs through terminal at discounted amount
- `AuctionCreationCouponSnapshotTest` — fee + commission stamped; grant ids stamped; same grant for both targets stamps both columns with the same id
- `AuctionActivationCouponConsumptionTest`:
  - Activation decrements `remaining_count` by 1 per stamped grant
  - When both stamps reference the same grant, decrement only once
  - Activation transitions grant to EXHAUSTED when count hits 0
  - Cancellation before ACTIVE does not decrement
- `CouponGrantSweeperIntegrationTest` — sweeper marks expired grants and resolver ignores them next call

### Frontend tests (Vitest + RTL)

- `WalletCouponsCard.test.tsx` — renders active + history; redeem happy path adds new grant inline; each error code renders the right user-facing message
- `CreateListingSummary.test.tsx` — badges render when discounts apply; expander toggles redeem input; successful inline redeem updates badges
- `AdminCouponForm.test.tsx` — discount-bundle add/remove; lifetime validation (at least one of duration/use_count); signup-window paired-or-empty rule; submit success and error
- `AdminCouponList.test.tsx` — pagination, filters, status pill rendering
- `AdminCouponDetail.test.tsx` — tabs render; Direct-grant modal user-picker happy path; Revoke confirmation

### Postman

- Add new requests mirroring every endpoint in Section 7 to the SLPA Postman collection
- Variable chaining: capture `couponPublicId` from create response, `couponGrantPublicId` from redeem/direct-grant responses, thread into subsequent requests
- Cover the happy-path admin flow: create coupon -> direct-grant -> verify user sees it -> revoke -> verify user no longer sees it as active

### Manual smoke (release checklist)

- Admin creates a `LISTING_FEE OVERRIDE 0, duration_days=30` coupon, no allowlist; a non-admin user redeems on wallet; same user creates a listing and sees L$0 fee badge; listing activates; same user creates a second listing within 30 days and still sees L$0; sweeper hasn't run, but resolver still applies
- Admin creates a `COMMISSION_RATE OVERRIDE 0.0, use_count=1` coupon for one allowlisted user; that user redeems, creates a listing, activates, sees 0% commission on the Auction snapshot; tries to redeem same code again, gets `ALREADY_REDEEMED`
- Admin creates a `signup_window_start=2026-05-01, signup_window_end=2026-05-31` coupon; pre-existing users in window get grants; one new user signed up on 2026-05-25 gets a grant on registration; one new user signed up 2026-06-01 does not

## 13. Out of scope

- Coupons that discount in-world wallet deposits (only listing fee + commission rate are covered).
- Coupons that apply to bidder-side fees (there are none today).
- Gift/transfer of grants between users.
- Multi-tier coupons (e.g. "first 50 redemptions at 10%, next 50 at 5%") — admins can express this by creating two coupons with different `max_total_redemptions`.
- Auto-redemption from a URL parameter (`?coupon=WELCOME30`) — user types codes manually in v1.
- Bulk-generated unique codes per recipient (`max_total=1` per unique code) — admin uses one shared code with `max_total = N` instead.
- Per-listing manual coupon application UI (Q2 = A: no user opt-out; auto-apply with deterministic tiebreaks).

## 14. Decision log

Captured during brainstorming (2026-05-19, 2026-05-20):

- **Discount shape** = `(target, op, value)` tuple on a child table `coupon_discounts`, 1:N from coupon. Lets the model carry partial discounts, overrides, percent-off, and flat-off in both LISTING_FEE and COMMISSION_RATE dimensions without column changes. Rejected per-type fields (`free_listing_days`, `commission_days`, etc.) as inflexible — they hardcode the discount form into the schema.
- **Lifetime** = `duration_days` + `use_count` on the parent coupon, either or both. Rejected separate "validity window" terminology — it was the same field with a confusing name.
- **Stacking** = pure auto-apply with deterministic tiebreaks; no user opt-out. Rejected per-listing manual selection (Q2 option B/C) — users hoarding count-coupons for high-fee parcels is a real failure mode the admin doesn't want.
- **Code surface** = template-level allowlist + universal code field; same code is the redemption identifier across user-typed, admin direct-grant, and signup-window paths. Rejected bulk-unique-codes (Q3a = no) as YAGNI; admin uses `max_total_redemptions` instead.
- **Signup-window auto-grant** = backfill on coupon save + forward hook on user creation. Rejected forward-only (Q4 = A) — "any user who joins between x and y" is a population statement, not a temporal one.
- **Consumption point** = at listing activation (DRAFT_PAID -> ACTIVE), not at listing creation. Rejected on-creation (Q5 = A) — protects users from foot-guns where a typo cancellation burns a scarce count-coupon.
- **Notifications** = in-app + email + SL IM on grant creation; REDEMPTION path is silent (user just acted; no surprise to surface).
- **Apply-time UI** = inline expander on create-listing summary (Q6 = A); coupons block under balance on wallet (Q6 = A). Rejected separate wallet tab and always-visible redeem strip.
- **Admin form** = sectioned form with repeatable discount-bundle rows. Rejected per-discount-type forms — too many forms for the same underlying entity.
