# Postman additions — sub-project G (Realty Groups final cleanup)

The SLPA Postman collection (id `8070328-cf3e1190-cb71-4af3-8e2e-70c82dfee288` in workspace SLPA at `https://scatr-devs.postman.co`) is the canonical manual-test surface. Sub-project G added three folders and fixed one existing request via Postman MCP; the cloud collection is the live source of truth.

This document mirrors those additions for **disaster-recovery** purposes: if the cloud collection is ever lost or rebuilt from scratch, recreate the requests below.

Environment used: `SLPA Dev` (variables `accessToken`, `userId`, `groupPublicId`, `auctionPublicId`, `slGroupVerificationCode`, `founderAvatarUuid`, `slOwnerKey`, `baseUrl`, etc.).

---

## 1. `Realty Groups → List as Group` folder

### Create case-3 listing (POST `/auctions` with `listAsGroupPublicId`)

- **Method / URL**: `POST {{baseUrl}}/api/v1/auctions`
- **Auth**: Bearer `{{accessToken}}`
- **Body** (raw JSON):

  ```json
  {
    "parcelUuid": "{{slParcelUuid}}",
    "title": "{{auctionTitle}}",
    "description": "Sample G listing",
    "startingBid": 1000,
    "buyNowPrice": null,
    "scheduledEndAt": "2026-06-01T00:00:00Z",
    "listAsGroupPublicId": "{{groupPublicId}}"
  }
  ```

- **Test script** (chain `auctionPublicId`):

  ```javascript
  const body = pm.response.json();
  if (body.publicId) pm.environment.set("auctionPublicId", body.publicId);
  ```

### Listing-eligible groups (wizard data source)

- **Method / URL**: `GET {{baseUrl}}/api/v1/realty/me/listing-eligible-groups?slParcelUuid={{slParcelUuid}}`
- **Auth**: Bearer `{{accessToken}}`
- Returns `[{ publicId, name, slug, logoUrl, agentCommissionRate }]` — sub-project G renamed `agentFeeRate` → `agentCommissionRate`.

---

## 2. `Realty Groups → Dissolve gate` folder

The backend ships dissolve-blockers as 409 responses on the actual `DELETE` attempt — there is no separate `can-dissolve` endpoint. Three variants:

### Dissolve group (clean — 204)

- **Method / URL**: `DELETE {{baseUrl}}/api/v1/realty-groups/{{groupPublicId}}`
- **Auth**: Bearer `{{accessToken}}` (must be the leader)

### Dissolve group (409 — has active listings)

- Same as above; the response is 409 with `{ code: "ACTIVE_LISTINGS_BLOCK_DISSOLVE", ... }` when listings remain.

### Dissolve group (409 — has registered SL group)

- Same as above; the response is 409 with `{ code: "SL_GROUP_REGISTERED_BLOCKS_DISSOLVE", ... }` while a registration exists. Force-unregister the SL group via the admin endpoint first.

---

## 3. `Realty Groups → Wallet` folder

### Get group wallet

- **Method / URL**: `GET {{baseUrl}}/api/v1/realty/groups/{{groupPublicId}}/wallet`
- **Auth**: Bearer `{{accessToken}}` (caller must have `VIEW_GROUP_TRANSACTIONS`)
- Returns `{ balance, reserved, available, leaderTermsAcceptedAt, recentLedger }`.

### Get group ledger

- **Method / URL**: `GET {{baseUrl}}/api/v1/realty/groups/{{groupPublicId}}/wallet/ledger?limit=20`
- Returns a cursor-paginated ledger page.

### Withdraw from group wallet (AVATAR)

- **Method / URL**: `POST {{baseUrl}}/api/v1/realty/groups/{{groupPublicId}}/wallet/withdraw`
- **Body**:

  ```json
  {
    "amount": 1000,
    "idempotencyKey": "{{$guid}}",
    "recipient": "AVATAR"
  }
  ```

### Withdraw from group wallet (SL_GROUP)

- Same URL/method as AVATAR variant.
- **Body**:

  ```json
  {
    "amount": 1000,
    "idempotencyKey": "{{$guid}}",
    "recipient": "SL_GROUP"
  }
  ```

- Backend rejects with 422 `SL_GROUP_NOT_REGISTERED` if no SL group is registered, or 422 `SL_GROUP_REGISTRATION_SUSPENDED` if the realty group has an active suspension. Drift alone is allowed.

### Admin adjust group wallet (credit)

- **Method / URL**: `POST {{baseUrl}}/api/v1/admin/realty-groups/{{groupPublicId}}/wallet/adjust`
- **Auth**: Bearer admin `{{accessToken}}`
- **Body**:

  ```json
  {
    "amount": 2500,
    "reason": "Reimburse fee from incident SLPA-1234"
  }
  ```

### Admin adjust group wallet (debit)

- Same URL/method.
- **Body**:

  ```json
  {
    "amount": -2500,
    "reason": "Revert accidental escrow double-payout"
  }
  ```

- 422 `ADMIN_ADJUST_AMOUNT_OUT_OF_RANGE` if `|amount| > slpa.realty.admin-wallet-adjust-max-l` (default 10,000,000). 422 `INSUFFICIENT_GROUP_BALANCE` if debit would push below zero.

### Admin get group wallet

- **Method / URL**: `GET {{baseUrl}}/api/v1/admin/realty-groups/{{groupPublicId}}/wallet`
- **Auth**: Bearer admin `{{accessToken}}`
- Bypasses leader-tier `VIEW_GROUP_TRANSACTIONS`. Returns the same `GroupWalletDto` shape.

### Pay listing fee (group-routed)

- **Method / URL**: `POST {{baseUrl}}/api/v1/me/auctions/{{auctionPublicId}}/pay-listing-fee`
- **Auth**: Bearer `{{accessToken}}`
- Backend routes the fee to the group wallet when the auction's `realtyGroupId` is non-null (case-3 listings).

---

## 4. Founder Terminal Verify — header fix

Existing request `SL → SL Group → Founder Terminal Verify` was created during sub-project E with the wrong header name. Sub-project G corrects it to match `SlGroupVerifyController`:

- **Method / URL**: `POST {{baseUrl}}/api/v1/sl/sl-group/verify`
- **Headers**:
  - `Content-Type: application/json`
  - `X-SecondLife-Shard: Production`
  - `X-SecondLife-Owner-Key: {{slOwnerKey}}`
- **Body**:

  ```json
  {
    "verificationCode": "{{slGroupVerificationCode}}",
    "founderAvatarUuid": "{{founderAvatarUuid}}"
  }
  ```

(Replace the earlier `X-Slpa-Terminal-Auth` header that did not exist on the actual filter.)

---

## Maintenance

- **Source of truth**: the cloud Postman collection. Edits there override this document.
- **Recovery procedure**: if the workspace is lost, run through each section above and recreate via Postman MCP (`mcp__postman__createCollectionFolder` + `mcp__postman__createCollectionRequest`) or by hand.
- **When endpoints change**: update both the cloud collection AND this file in the same task.
