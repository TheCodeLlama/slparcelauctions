# Postman Collection — Public ID Migration Checklist

After the BaseEntity + public UUID refactor (branch `task/base-entity-uuid`), the SLPA Postman
collection needs the following updates. Apply via the Postman UI at
`https://scatr-devs.postman.co`.

**Collection:** SLPA (`8070328-cf3e1190-cb71-4af3-8e2e-70c82dfee288`)

---

## 1. SLPA Dev environment — variable renames

Add the new variables listed below. Do NOT delete the old `xxxId` variables until the full
chain runs cleanly end-to-end (you may want to keep both during transition).

| Old variable | New variable | Captured from |
|---|---|---|
| `userId` | `userPublicId` | Auth/Login test script (see section 3) |

Variables that are unchanged (Long IDs used only on internal/bot/admin endpoints, or non-ID
values):

- `accessToken`, `refreshToken` — JWT strings
- `botTaskId` — bot endpoints still use Long IDs
- `verificationCode` — a 6-digit code string, not an entity ID
- `parcelVerificationCode` — parcel verification code value
- `parcelUuid`, `sellerAvatarUuid`, `slAvatarUuid` — SL-native UUIDs, unchanged
- `slEscrowSharedSecret`, `terminalId`, `terminalSharedSecret` — unchanged
- `auctionStatus`, `walletBalance`, `walletReserved`, `walletAvailable`,
  `walletPenaltyOwed`, `walletTermsAccepted`, `withdrawalQueueId`,
  `parcelVerificationCode`, `botTaskId` — all unchanged
- `avatarSize` — not an entity ID

Variables to add for auction chaining (currently missing — see section 2):

| New variable | Captured from |
|---|---|
| `auctionPublicId` | POST /api/v1/auctions test script (add one — see section 3) |

---

## 2. Requests missing a capture script (need one added)

The following requests create entities and are used in downstream requests via `{{auctionId}}`
in URLs, but they have no test script today that captures the ID.

### POST Create auction (`/api/v1/auctions`)

Currently the body uses `parcelId: 1` (a stale Long field). The backend now expects
`slParcelUuid` (a UUID). Update the body AND add a test script:

**Updated body:**

```json
{
  "slParcelUuid": "00000000-0000-0000-0000-000000000001",
  "title": "Test Parcel Auction",
  "startingBid": 1000,
  "reservePrice": 2500,
  "buyNowPrice": 10000,
  "durationHours": 168,
  "snipeProtect": true,
  "snipeWindowMin": 5,
  "sellerDesc": "Lovely waterfront parcel, close to road and infohub.",
  "tags": ["WATERFRONT", "PROTECTED_ADJ"]
}
```

**Add test script:**

```js
const r = pm.response.json();
pm.collectionVariables.set("auctionPublicId", r.publicId);
pm.test("auctionPublicId captured", () => {
    pm.expect(pm.collectionVariables.get("auctionPublicId")).to.be.a("string").and.not.empty;
});
```

---

## 3. Test script changes — requests with pm.environment.set

### Auth / Login

**Folder:** Auth/Login
**URL:** `POST {{baseUrl}}/api/v1/auth/login`

Current test script:
```js
const r = pm.response.json();
pm.environment.set("accessToken", r.accessToken);
pm.environment.set("userId", r.user.id);         // <-- CHANGE THIS LINE
const refreshCookie = pm.cookies.get("refreshToken");
if (refreshCookie) { pm.environment.set("refreshToken", refreshCookie); }
pm.test("access token captured", () => {
    pm.expect(pm.environment.get("accessToken")).to.be.a("string").and.not.empty;
});
```

Updated test script:
```js
const r = pm.response.json();
pm.environment.set("accessToken", r.accessToken);
pm.environment.set("userPublicId", r.user.publicId);   // publicId (UUID), not id (Long)
const refreshCookie = pm.cookies.get("refreshToken");
if (refreshCookie) { pm.environment.set("refreshToken", refreshCookie); }
pm.test("access token captured", () => {
    pm.expect(pm.environment.get("accessToken")).to.be.a("string").and.not.empty;
});
```

---

## 4. URL template changes — requests using `{{auctionId}}`

The `auctionId` variable is referenced in URLs throughout the collection but is never actually
set by any existing test script (it must be set manually today). Replace every `{{auctionId}}`
with `{{auctionPublicId}}` once the Create auction capture script (section 2) is in place.

| Request name | Folder | Old URL | New URL |
|---|---|---|---|
| Auction / Verify | Parcel & Listings | `…/auctions/{{auctionId}}/verify` | `…/auctions/{{auctionPublicId}}/verify` |
| Get auction | Parcel & Listings | `…/auctions/{{auctionId}}` | `…/auctions/{{auctionPublicId}}` |
| Update auction | Parcel & Listings | `…/auctions/{{auctionId}}` | `…/auctions/{{auctionPublicId}}` |
| Cancel auction | Parcel & Listings | `…/auctions/{{auctionId}}/cancel` | `…/auctions/{{auctionPublicId}}/cancel` |
| Preview auction | Parcel & Listings | `…/auctions/{{auctionId}}/preview` | `…/auctions/{{auctionPublicId}}/preview` |
| Place bid | Parcel & Listings | `…/auctions/{{auctionId}}/bids` | `…/auctions/{{auctionPublicId}}/bids` |
| Bid history | Parcel & Listings | `…/auctions/{{auctionId}}/bids` | `…/auctions/{{auctionPublicId}}/bids` |
| Set proxy bid | Parcel & Listings | `…/auctions/{{auctionId}}/proxy-bid` | `…/auctions/{{auctionPublicId}}/proxy-bid` |
| Update proxy max | Parcel & Listings | `…/auctions/{{auctionId}}/proxy-bid` | `…/auctions/{{auctionPublicId}}/proxy-bid` |
| Cancel proxy | Parcel & Listings | `…/auctions/{{auctionId}}/proxy-bid` | `…/auctions/{{auctionPublicId}}/proxy-bid` |
| Get my proxy | Parcel & Listings | `…/auctions/{{auctionId}}/proxy-bid` | `…/auctions/{{auctionPublicId}}/proxy-bid` |
| Upload (photo) | Photos | `…/auctions/{{auctionId}}/photos` | `…/auctions/{{auctionPublicId}}/photos` |
| Delete (photo) | Photos | `…/auctions/{{auctionId}}/photos/{{photoId}}` | `…/auctions/{{auctionPublicId}}/photos/{{photoId}}` |
| Get bytes (photo) | Photos | `…/auctions/{{auctionId}}/photos/{{photoId}}/bytes` | `…/auctions/{{auctionPublicId}}/photos/{{photoId}}/bytes` |
| Get escrow status | Escrow | `…/auctions/{{auctionId}}/escrow` | `…/auctions/{{auctionPublicId}}/escrow` |
| File escrow dispute | Escrow | `…/auctions/{{auctionId}}/escrow/dispute` | `…/auctions/{{auctionPublicId}}/escrow/dispute` |
| Pay listing fee from wallet | Wallet | `…/me/auctions/{{auctionId}}/pay-listing-fee` | `…/me/auctions/{{auctionPublicId}}/pay-listing-fee` |
| Simulate listing fee payment (dev) | Dev | `…/dev/auctions/{{auctionId}}/pay` | `…/dev/auctions/{{auctionPublicId}}/pay` |
| Force-close single auction (dev) | Dev | `…/dev/auctions/{{auctionId}}/close` | `…/dev/auctions/{{auctionPublicId}}/close` |
| Simulate escrow payment (dev) | Dev | `…/dev/escrow/{{auctionId}}/simulate-payment` | `…/dev/escrow/{{auctionPublicId}}/simulate-payment` |

Also update `{{userId}}` to `{{userPublicId}}` in these URLs:

| Request name | Folder | Old URL | New URL |
|---|---|---|---|
| Get user by id | Users | `…/users/{{userId}}` | `…/users/{{userPublicId}}` |
| Get user avatar | Users | `…/users/{{userId}}/avatar/{{avatarSize}}` | `…/users/{{userPublicId}}/avatar/{{avatarSize}}` |
| Get user active listings | Users | `…/users/{{userId}}/auctions?…` | `…/users/{{userPublicId}}/auctions?…` |

URLs that stay unchanged (bot/admin endpoints use Long IDs internally):

- `…/bot/tasks/{{botTaskId}}/verify` — bot internal, Long ID unchanged
- `…/bot/tasks/{{botTaskId}}/monitor` — bot internal, Long ID unchanged
- `…/dev/bot/tasks/{{botTaskId}}/complete` — dev stub, Long ID unchanged

---

## 5. Request body field changes

### POST Simulate escrow payment (dev) — `Dev/Simulate escrow payment (dev)`

**URL:** `POST {{baseUrl}}/api/v1/dev/escrow/{{auctionId}}/simulate-payment`

The body contains a hardcoded `"auctionId": 1` (Long). This field is sent in the body AND in
the URL. Once the URL is updated to `{{auctionPublicId}}` (section 4), the body field should
also change if the backend still reads it:

```json
{
  "auctionId": 1,        // <-- check if backend still reads this; if so it becomes auctionPublicId (UUID)
  "payerUuid": "a0b1c2d3-0000-4000-8000-000000000001",
  "terminalId": "term-001"
}
```

Verify against the `DevEscrowController` — if the body field was also renamed, update to
`"auctionPublicId": "{{auctionPublicId}}"`.

---

## 6. Saved example response bodies — need refresh

The following saved examples have stale response shapes (they show `"id": 1` where the live
backend now returns `"publicId": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"`). After exercising
the updated collection against a running backend, re-save the responses to update the examples.

| Request | Saved response name |
|---|---|
| POST `/api/v1/auth/register` | 201 Created |
| POST `/api/v1/auth/login` | 200 OK |
| POST `/api/v1/auth/refresh` | 200 OK |
| POST `/api/v1/users` | 201 Created |
| GET `/api/v1/users/me` | 200 OK |
| GET `/api/v1/users/{{userPublicId}}` | 200 OK |
| PUT `/api/v1/users/me` | 200 OK |
| POST `/api/v1/users/me/avatar` | 200 OK |
| POST `/api/v1/sl/verify` | 200 OK - Verified (`userId` field → `userPublicId`) |
| POST `/api/v1/dev/sl/simulate-verify` | 200 verified (`userId` field → `userPublicId`) |

Note: `SlVerifyResponse` uses `userId: Long` (internal use only — the LSL caller doesn't need
the UUID). That field name is unchanged intentionally; only the saved example's shape matters
for documentation accuracy.

---

## 7. Auction / Verify test script — already correct, but note

**Folder:** Parcel & Listings / Auction / Verify

The existing test script captures `botTaskId` and `parcelVerificationCode` via
`pm.collectionVariables.set`. These are unchanged — `botTaskId` stays a Long (bot-internal
endpoint), and `parcelVerificationCode` is a code value. No changes needed here.

---

## 8. Verification workflow

After applying all changes:

1. Set environment to `SLPA Dev`.
2. Run the Auth/Login request — confirm `userPublicId` is captured as a UUID string.
3. Run Verification/Generate code — `verificationCode` still captured as before.
4. Run Parcel & Listings/Create auction — confirm `auctionPublicId` is captured as a UUID string.
5. Run Parcel & Listings/Get auction — confirm 200 OK with `publicId` (UUID) in the response body.
6. Run Parcel & Listings/Auction / Verify — confirm the verify flow completes.
7. Run Escrow/Get escrow status — confirm 200 with `escrowPublicId` and `auctionPublicId` in response.
8. Run Wallet/Get wallet — confirm 200 OK; ledger entries have `publicId` (UUID) not `id` (Long).
9. Run the Bot folder with a live dev backend to confirm `botTaskId` (Long) still chains correctly.

Failures after applying changes almost always mean a URL still contains `{{auctionId}}` or
`{{userId}}` rather than the renamed variable — search the failing request for the old variable
name.
