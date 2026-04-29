# Epic 11 — LSL Scripts (In-World Terminals) Design

**Status:** Spec drafted 2026-04-28. All four LSL deliverables for Phase 1 launch.

## 1. Goal

Ship the in-world LSL scripts that consume SLPA's already-shipped backend SL endpoints. Phase 11 closes the last MVP gap: every backend endpoint that was waiting for an in-world client gets one. After Epic 11 ships, the platform's end-to-end loops (verify → list → bid → pay → confirm → review → admin) all run against real SL traffic.

## 2. Scope

Four LSL deliverables. The SL IM dispatcher already shipped (`lsl-scripts/sl-im-dispatcher/`); Epic 11 adds the remaining three plus a unified payment terminal that subsumes the originally-planned escrow / listing-fee / penalty terminals into a single object.

| Deliverable | Endpoints called | Trust posture | Deployment posture |
|---|---|---|---|
| **SLPA Verification Terminal** | `POST /api/v1/sl/verify` | header gate only | Marketplace + SLPA HQ + allied venues; many instances OK |
| **SLPA Parcel Verifier** (rezzable) | `POST /api/v1/sl/parcel/verify` | header gate only | Marketplace + given via SLPA Terminal menu; self-destructs |
| **SLPA Terminal** (unified) | `POST /api/v1/sl/terminal/register`, `/sl/escrow/payment`, `/sl/listing-fee/payment`, `/sl/penalty-lookup`, `/sl/penalty-payment`, `/sl/escrow/payout-result` | header gate **+** body shared secret | SLPA-team-only at SLPA HQ + auction venues; ≥1 active required |

The unified SLPA Terminal also gives a `SLPA Parcel Verifier` inventory copy on touch (a 4th menu option), so sellers don't have to detour to Marketplace mid-listing.

### Out of scope (deferred — see DEFERRED_WORK.md)

- HMAC-SHA256 per-request terminal auth (Phase 2 hardening, after Epic 11 dogfooding stable)
- Smart regional routing for `TerminalCommand` dispatch (Phase 2)
- `terminal_commands.shared_secret_version` provenance stamping (post-MVP)
- HTTP-in push from backend → SL IM dispatcher (Phase 2 latency optimization)
- Sub-day SL IM dispatcher health monitoring (post-MVP ops)
- Quiet hours UI for SL IM (post-MVP)
- Terminal locator UI on the website (Epic 11 ops checklist, frontend-deferred)
- Parcel layout map generator (DESIGN.md §5.5 — separate further-design TODO)
- Bot pool (DESIGN.md §5.4 — separate Epic)

## 3. Architecture

### 3.1 Shared pattern (all four scripts)

Codify once, reuse per script:

- **Notecard config** — file named exactly `config` in the prim's contents. Format: `key=value`, one per line, `#` for comments, whitespace trimmed. Same parser shape as `lsl-scripts/sl-im-dispatcher/dispatcher.lsl`.
- **Mainland-only grid guard** — at startup, `if (llGetEnv("sim_channel") != "Second Life Server") halt`. Note: this is the *in-world env value*, distinct from the `X-SecondLife-Shard` HTTP header value (`"Production"`) the backend's `SlHeaderValidator` checks. Both names must appear together as a comment in each script so an implementer doesn't conflate them.
- **Inventory-edit auto-reset** — `changed(CHANGED_INVENTORY) → llResetScript()`. This handles both notecard updates and the SLPA Terminal's parcel-verifier-inventory updates.
- **Listen-handle hygiene** — `llListen` returns a handle stored in a script global; `llListenRemove(handle)` is called on every exit path (success, error, timeout, lock release). LSL has a 65-listen cap; not cleaning up is a slow leak that bites in production.
- **Owner-say diagnostic format** — `<TerminalKind>: <event>: <fields>` so all four scripts log uniformly. Examples: `SLPA Verification Terminal: verify ok: userId=42`, `SLPA Terminal: payment retry 3/5: 5xx`.
- **Bounded HTTP retry** — applies *only* to payment scripts where L$ is already absorbed. Schedule: 10s / 30s / 90s / 5m / 15m (~22 minutes total). After exhaustion: `llOwnerSay` "CRITICAL: payment from {payer} L${amount} key {tx} not acknowledged" + stop retrying.

### 3.2 Directory layout

Each script gets its own subdirectory mirroring `sl-im-dispatcher/`:

```
lsl-scripts/<name>/
├── README.md                # Purpose, Architecture summary, Deployment, Configuration,
│                            # Operations, Troubleshooting, Limits, Security
├── <name>.lsl               # the script
└── config.notecard.example  # template notecard, real values replaced with placeholders
```

The top-level `lsl-scripts/README.md` index lists all four scripts with one-line summaries (updates only on add/remove/rename per existing convention).

## 4. SLPA Verification Terminal

### 4.1 Purpose

Touch-driven account-linking kiosk. Player gets a 6-digit PLAYER code from the website, touches the terminal, types the code, terminal collects avatar metadata, POSTs, and reports success/failure. Distributed widely (Marketplace, allied venues) — header trust only, no shared secret, no L$.

### 4.2 Endpoint contract

`POST /api/v1/sl/verify` (existing, Epic 02).

**Request body** (`SlVerifyRequest`):
```json
{
  "verificationCode": "123456",
  "avatarUuid": "<llDetectedKey(0)>",
  "avatarName": "<llDetectedName(0)>",
  "displayName": "<llGetDisplayName(uuid)>",
  "username": "<llGetUsername(uuid)>",
  "bornDate": "YYYY-MM-DD",
  "payInfo": 0
}
```

`bornDate` from `llRequestAgentData(DATA_BORN)` is already `YYYY-MM-DD` (LSL native format) — JSON-serialize as-is. `payInfo` is the `DATA_PAYINFO` integer (0–3).

**Response** (200): `SlVerifyResponse{verified, userId, slAvatarName}`.
**Errors** (4xx): RFC 7807 ProblemDetail body — script speaks `title` + `detail`.

### 4.3 State machine

```
state IDLE:
  floating text:  "SLPA Verification Terminal\nTouch to link your account"
  object name:    "SLPA Verification Terminal"
  white text

on touch_start(N):
  toucher = llDetectedKey(0)
  if locked && expiresAt > now:
    llRegionSayTo(toucher, 0, "Terminal busy — currently verifying " + holderName + ".")
    return
  acquire lock(holder=toucher, holderName=llDetectedName(0), expiresAt=now+60s)
  set_busy_chrome():
    llSetText("SLPA Verification Terminal\n<In Use>", <1.0,0.2,0.2>, 1.0)  // red
    llSetObjectName("SLPA Verification Terminal <In Use>")
  listenHandle = llListen(menuChan, "", toucher, "")
  llTextBox(toucher, "Enter your 6-digit SLPA code:", menuChan)
  start data_timeout 30s

on listen(menuChan, _, fromKey, code):
  llListenRemove(listenHandle); listenHandle = -1
  if !regex("^[0-9]{6}$"):
    llRegionSayTo(toucher, 0, "✗ Code must be 6 digits.")
    release_lock(); return
  store code
  bornReqKey = llRequestAgentData(toucher, DATA_BORN)
  payReqKey  = llRequestAgentData(toucher, DATA_PAYINFO)

on dataserver(reqKey, payload):
  if reqKey == bornReqKey: bornDate = payload
  if reqKey == payReqKey:  payInfo  = (integer)payload
  if both arrived:
    cancel data_timeout
    POST /sl/verify with {code, avatarUuid, avatarName, displayName, username, bornDate, payInfo}

on data_timeout (30s):
  llRegionSayTo(toucher, 0, "✗ Couldn't read your avatar data — please try again.")
  release_lock()

on http_response(req, status, body):
  if status == 200:
    parse {verified, userId, slAvatarName}
    if verified:
      llRegionSayTo(toucher, 0, "✓ Linked SLPA #" + userId + " to " + slAvatarName + ".")
    else:
      llRegionSayTo(toucher, 0, "✗ Verification failed. Code may be expired — generate a new one on slparcelauctions.com.")
  else if status >= 400 && status < 500:
    title = llJsonGetValue(body, ["title"])
    detail = llJsonGetValue(body, ["detail"])
    llRegionSayTo(toucher, 0, "✗ " + title + ": " + detail)
  else:  // 5xx, 0 (timeout)
    llRegionSayTo(toucher, 0, "✗ Backend unreachable. Try again in a moment.")
  release_lock()

on lock_timeout (60s no progress):
  if listenHandle != -1: llListenRemove(listenHandle)
  release_lock()

release_lock():
  set_idle_chrome()  // restore white text + object name "SLPA Verification Terminal"
  clear lock state
  state → IDLE
```

**No retry** — verification is idempotent and low-stakes; the user just re-touches.

### 4.4 Notecard

```
# config notecard for SLPA Verification Terminal
VERIFY_URL=https://api.slparcelauctions.com/api/v1/sl/verify
DEBUG_OWNER_SAY=true
```

Required: `VERIFY_URL`. Optional: `DEBUG_OWNER_SAY` (default `true`).

### 4.5 Deployment

- Distributed free via Marketplace.
- Multiple instances OK at busy locations (each runs its own per-object lock — congestion mitigated by deploying more terminals, not by adding queue logic to one).
- Ownership: SLPA service account (so the SL-injected `X-SecondLife-Owner-Key` matches a trusted-owner-keys config entry; `SlHeaderValidator` rejects everything else).

## 5. SLPA Parcel Verifier (rezzable)

### 5.1 Purpose

Single-use rezzable object. Seller rezzes it on the parcel they're listing, types their 6-digit PARCEL code in an auto-popped dialog, object reads parcel metadata + POSTs + self-destructs. Distributed via Marketplace and the SLPA Terminal's "Get Parcel Verifier" menu.

### 5.2 Endpoint contract

`POST /api/v1/sl/parcel/verify` (existing, Epic 03).

**Request body** (`SlParcelVerifyRequest`):
```json
{
  "verificationCode": "654321",
  "parcelUuid": "<PARCEL_DETAILS_ID>",
  "ownerUuid":  "<PARCEL_DETAILS_OWNER>",
  "parcelName": "<PARCEL_DETAILS_NAME>",
  "areaSqm":    1024,
  "description":"<PARCEL_DETAILS_DESC>",
  "primCapacity":468,
  "regionPosX": 128.0,
  "regionPosY": 64.0,
  "regionPosZ": 24.0
}
```

**Response** (204 No Content) on success. **Errors** (4xx): ProblemDetail body — script speaks `title` + `detail`.

### 5.3 Pattern: auto-prompt-on-rez

Generic verifier object (one Marketplace listing serves all sellers). On rez: opens an `llTextBox` targeted at `llGetOwner()` (the rezzer is in earshot by definition). User types code → POST → die. No touch required, no per-listing parameterization.

### 5.4 Flow

```
on_rez(start_param):
  if (llGetEnv("sim_channel") != "Second Life Server"):
    llOwnerSay("✗ Wrong grid."); llDie()

  // Read parcel data once
  parcelData = llGetParcelDetails(llGetPos(), [
      PARCEL_DETAILS_ID, PARCEL_DETAILS_OWNER, PARCEL_DETAILS_GROUP,
      PARCEL_DETAILS_NAME, PARCEL_DETAILS_DESC, PARCEL_DETAILS_AREA,
      PARCEL_DETAILS_PRIM_CAPACITY])
  parcelUuid    = (key)llList2String(parcelData, 0)
  ownerUuid     = (key)llList2String(parcelData, 1)
  groupUuid     = (key)llList2String(parcelData, 2)
  parcelName    = llList2String(parcelData, 3)
  description   = llList2String(parcelData, 4)
  areaSqm       = (integer)llList2String(parcelData, 5)
  primCapacity  = (integer)llList2String(parcelData, 6)
  pos           = llGetPos()
  rezzer        = llGetOwner()

  // Client-side short-circuit (saves backend round-trip on common mistakes)
  if (ownerUuid != rezzer && groupUuid == NULL_KEY):
    llOwnerSay("✗ This parcel isn't yours. Please rez on land you own.")
    llDie()

  // Auto-prompt for code
  listenHandle = llListen(channel, "", rezzer, "")
  llTextBox(rezzer, "Enter your 6-digit PARCEL code:", channel)
  start code_entry_timeout 90s

on listen(channel, _, fromKey, code):
  llListenRemove(listenHandle)
  if !regex("^[0-9]{6}$"):
    llOwnerSay("✗ Code must be 6 digits.")
    llDie()
  POST /sl/parcel/verify with body{verificationCode=code, parcelUuid, ownerUuid, parcelName, areaSqm, description, primCapacity, regionPosX=pos.x, regionPosY=pos.y, regionPosZ=pos.z}
  start http_timeout 30s

on http_response(req, status, body):
  if status == 204:
    llOwnerSay("✓ Parcel verified — your listing is live on slparcelauctions.com.")
  else if status >= 400 && status < 500:
    title  = llJsonGetValue(body, ["title"])
    detail = llJsonGetValue(body, ["detail"])
    llOwnerSay("✗ " + title + ": " + detail)
  else:
    llOwnerSay("✗ Backend unreachable. Please rez again in a moment.")
  llDie()

on http_timeout (30s):
  llOwnerSay("✗ Timed out reaching SLPA.")
  llDie()

on code_entry_timeout (90s):
  llListenRemove(listenHandle)
  llOwnerSay("✗ Timed out waiting for code.")
  llDie()
```

**Always llDie().** No script left behind in any path. No retry — seller can re-rez (POST is idempotent; the backend allows multiple verification attempts until the auction transitions out of `VERIFICATION_PENDING`).

### 5.5 Group-owned land

If `ownerUuid != rezzer` AND `groupUuid != NULL_KEY`, the LSL skips its short-circuit and lets the backend authoritatively decide (group ownership is verified server-side via Method C bot, not client-side). The body's `ownerUuid` is the *parcel's* owner UUID (which may be a group UUID); rezzer comes from `X-SecondLife-Owner-Key` header.

### 5.6 Notecard

```
# config notecard for SLPA Parcel Verifier
PARCEL_VERIFY_URL=https://api.slparcelauctions.com/api/v1/sl/parcel/verify
DEBUG_OWNER_SAY=true
```

Required: `PARCEL_VERIFY_URL`. Optional: `DEBUG_OWNER_SAY`.

### 5.7 Permissions

Marketplace listing should ship with: transfer YES, copy YES, modify NO. The "modify NO" prevents accidental edits that would void the SLPA-service-account ownership; "copy YES" lets a seller re-rez without buying again.

## 6. SLPA Terminal (unified payments)

### 6.1 Purpose

Single object, four touch-menu options:
- **Escrow Payment** — winner pays escrow on a won auction
- **Listing Fee** — seller pays listing fee on a DRAFT auction
- **Pay Penalty** — anyone clears a cancellation-penalty balance
- **Get Parcel Verifier** — `llGiveInventory` of the SLPA Parcel Verifier object

Plus an HTTP-in command surface for backend-initiated PAYOUT/REFUND/WITHDRAW.

### 6.2 Endpoint contracts

| Direction | Endpoint / inbound shape | Auth |
|---|---|---|
| Outbound | `POST /api/v1/sl/terminal/register` (startup + region restart) | header + `sharedSecret` in body |
| Outbound | `POST /api/v1/sl/escrow/payment` (after money() in ESCROW mode) | header + `sharedSecret` in body |
| Outbound | `POST /api/v1/sl/listing-fee/payment` (after money() in LISTING_FEE mode) | header + `sharedSecret` in body |
| Outbound | `POST /api/v1/sl/penalty-lookup` (touch → PENALTY menu) | header only |
| Outbound | `POST /api/v1/sl/penalty-payment` (after money() in PENALTY mode) | header only |
| Outbound | `POST /api/v1/sl/escrow/payout-result` (after `transaction_result`) | header + `sharedSecret` in body |
| Inbound (HTTP-in) | `TerminalCommandBody{action: PAYOUT\|REFUND\|WITHDRAW, purpose, recipientUuid, amount, escrowId, listingFeeRefundId, idempotencyKey, sharedSecret}` | constant-time `sharedSecret` compare |

**Action enum values come straight from `TerminalCommandAction` (PAYOUT, REFUND, WITHDRAW); purpose values from `TerminalCommandPurpose` (AUCTION_ESCROW, LISTING_FEE_REFUND, ADMIN_WITHDRAWAL).** The script doesn't need to reason about purpose — it executes `llTransferLindenDollars` and reports back. Purpose is operator-facing telemetry.

### 6.3 State machine

```
state INIT (state_entry):
  load notecard:
    REGISTER_URL, ESCROW_PAYMENT_URL, LISTING_FEE_URL,
    PENALTY_LOOKUP_URL, PENALTY_PAYMENT_URL, PAYOUT_RESULT_URL,
    SHARED_SECRET, TERMINAL_ID (default (string)llGetKey()),
    REGION_NAME (default llGetRegionName()), DEBUG_OWNER_SAY
  guard sim_channel == "Second Life Server"
  llRequestPermissions(llGetOwner(), PERMISSION_DEBIT)
  on run_time_permissions:
    if !(perm & PERMISSION_DEBIT): llOwnerSay("CRITICAL: PERMISSION_DEBIT denied"); halt
    llRequestURL()
  on http_request(URL_REQUEST_GRANTED, url):
    httpInUrl = url
    POST /sl/terminal/register with {terminalId, httpInUrl, regionName, sharedSecret}
    on success → IDLE
    on failure → bounded retry; eventual CRITICAL log + halt

state IDLE:
  floating text: "SLPA Terminal\nTouch for options"
  object name:   "SLPA Terminal"
  white text
  llSetPayPrice(PAY_HIDE, [PAY_HIDE,PAY_HIDE,PAY_HIDE,PAY_HIDE])  // no payment in IDLE

  on touch_start(N):
    toucher = llDetectedKey(0)
    if locked && expiresAt > now:
      llRegionSayTo(toucher, 0, "Terminal busy with " + holderName + ". Try again in 60s.")
      return
    acquire lock(holder=toucher, holderName=llDetectedName(0), expiresAt=now+60s)
    set_busy_chrome()
    listenHandle = llListen(menuChan, "", toucher, "")
    llDialog(toucher, "What do you need?",
             ["Escrow Payment", "Listing Fee", "Pay Penalty", "Get Parcel Verifier"], menuChan)

  on listen(menuChan, _, fromKey, choice):
    llListenRemove(listenHandle); listenHandle = -1
    switch choice:
      "Escrow Payment":
         selectedKind = ESCROW
         llTextBox(holder, "Enter the Auction ID from your auction page:", auctionIdChan)
         extend lock 60s; state → AWAITING_AUCTION_ID
      "Listing Fee":
         selectedKind = LISTING_FEE
         llTextBox(holder, "Enter the Auction ID from your draft listing:", auctionIdChan)
         extend lock 60s; state → AWAITING_AUCTION_ID
      "Pay Penalty":
         POST /sl/penalty-lookup with {slAvatarUuid: holder, terminalId}
         state → LOOKUP_INFLIGHT
      "Get Parcel Verifier":
         llGiveInventory(holder, "SLPA Parcel Verifier")
         llRegionSayTo(holder, 0, "Sent! Rez it on your parcel and enter your 6-digit PARCEL code.")
         release_lock()  // no payment expected — release immediately

state AWAITING_AUCTION_ID:
  on listen(auctionIdChan, _, fromKey, raw):
    llListenRemove(listenHandle)
    if !regex("^[0-9]+$") || (integer)raw <= 0:
      llRegionSayTo(holder, 0, "✗ Invalid auction ID — must be a positive number.")
      release_lock(); return
    selectedAuctionId = (integer)raw
    if selectedKind == ESCROW:
      llRegionSayTo(holder, 0, "Pay the L$ escrow amount shown on auction #" + raw + ".")
    else:
      llRegionSayTo(holder, 0, "Pay the L$ listing fee shown on auction #" + raw + ".")
    llSetPayPrice(PAY_DEFAULT, [PAY_HIDE, PAY_HIDE, PAY_HIDE, PAY_HIDE])
    extend lock 60s; state → AWAITING_PAYMENT

  on lock_timeout (60s):
    llListenRemove(listenHandle); release_lock()

state LOOKUP_INFLIGHT:
  on http_response from /penalty-lookup:
    if status == 404 || (200 && owed == 0):
      llRegionSayTo(holder, 0, "✓ No penalty on file.")
      release_lock()
    else if status == 200 && owed > 0:
      selectedKind = PENALTY
      expectedAmount = owed
      llRegionSayTo(holder, 0, "Penalty owed: L$" + owed + ". Pay below — full or partial OK.")
      llSetPayPrice(owed, [owed, owed/2, owed/4, PAY_HIDE])
      extend lock 60s; state → AWAITING_PAYMENT
    else:  // 5xx or timeout
      llRegionSayTo(holder, 0, "✗ Lookup failed — try again.")
      release_lock()

state AWAITING_PAYMENT:
  on money(payer, amount):
    if payer != holder:
      // Backend disambiguates by payerUuid; best-effort POST.
      llOwnerSay("Note: payment from " + (string)payer + " is not the menu user " + (string)holder)
    // LSL's money() event does not expose the SL grid's L$ transaction id —
    // we synthesize a per-transaction unique key with llGenerateKey() (UUID)
    // and use it as the slTransactionKey. Backend idempotency on this field
    // means a retry of a successful POST is a no-op return.
    slTransactionKey = (string)llGenerateKey()
    POST endpoint per selectedKind:
      ESCROW       → POST /sl/escrow/payment      with {auctionId=selectedAuctionId, payerUuid=(string)payer, amount, slTransactionKey, terminalId, sharedSecret}
      LISTING_FEE  → POST /sl/listing-fee/payment with same shape (auctionId=selectedAuctionId)
      PENALTY      → POST /sl/penalty-payment     with {slAvatarUuid=(string)payer, slTransactionId=slTransactionKey, amount, terminalId}
    state → PAYMENT_INFLIGHT
    release_lock()  // lock releases on first POST attempt; retries run in background

  on lock_timeout (60s):  // money never came
    llSetPayPrice(PAY_HIDE, [PAY_HIDE,PAY_HIDE,PAY_HIDE,PAY_HIDE])
    release_lock()

state PAYMENT_INFLIGHT (background — does not hold lock):
  on http_response 200:
    if (selectedKind == ESCROW || selectedKind == LISTING_FEE):
      reason  = llJsonGetValue(body, ["reason"])
      message = llJsonGetValue(body, ["message"])
      status  = llJsonGetValue(body, ["status"])
      if status == "OK":
        llRegionSayTo(payer, 0, "✓ Payment of L$" + amount + " accepted.")
      else if status == "REFUND":
        llRegionSayTo(payer, 0, "Payment refused: " + message + ". Refund will be issued.")
      else:  // ERROR
        llRegionSayTo(payer, 0, "✗ Payment error: " + message)
    if (selectedKind == PENALTY):
      remaining = (integer)llJsonGetValue(body, ["remainingBalance"])
      if remaining == 0: llRegionSayTo(payer, 0, "✓ Penalty cleared.")
      else:              llRegionSayTo(payer, 0, "✓ L$" + amount + " applied. L$" + remaining + " still owed.")
  on http_response 4xx:
    title = llJsonGetValue(body, ["title"])
    llOwnerSay("CRITICAL: payment 4xx from {payer} L${amount} key {tx}: " + title)
  on transient (5xx, 0, timeout):
    bounded retry: 10s / 30s / 90s / 5m / 15m
    on each: llOwnerSay("SLPA Terminal: payment retry N/5: " + (string)status)
    on exhaustion: llOwnerSay("CRITICAL: payment from {payer} L${amount} key {tx} not acknowledged after 5 retries")

// HTTP-in command handler — parallel to state machine, lock-independent
on http_request(method=POST, body):
  parse TerminalCommandBody JSON
  if !constantTimeEquals(body.sharedSecret, SHARED_SECRET):
    llHTTPResponse(reqId, 403, "secret mismatch"); return
  switch action:
    PAYOUT, REFUND, WITHDRAW:
      llHTTPResponse(reqId, 200, "{\"ack\":true}")  // ack receipt; result follows via /payout-result
      txKey = llTransferLindenDollars((key)recipientUuid, amount)
      track inflight: {idempotencyKey, txKey}

on transaction_result(txKey, success, data):
  match to inflight idempotencyKey
  POST /sl/escrow/payout-result with {idempotencyKey, success, slTransactionKey: data, errorMessage: data on fail, terminalId, sharedSecret}
  remove from inflight

// Region restart handling
on changed(CHANGED_REGION_START):
  llRequestURL()
  // on URL_REQUEST_GRANTED: re-POST /sl/terminal/register

on changed(CHANGED_INVENTORY):
  llResetScript()  // notecard or parcel-verifier inventory updated
```

### 6.4 Pay-price matrix (callout — easy to misread)

| State | First arg (text input) | Quick buttons | Effect |
|---|---|---|---|
| IDLE | `PAY_HIDE` | all `PAY_HIDE` | Pay dialog has no field, no buttons — payment refused |
| ESCROW / LISTING_FEE selected | `PAY_DEFAULT` | all `PAY_HIDE` | Empty text field, no quick buttons — user types custom amount |
| PENALTY selected (owed=N) | `N` | `[N, N/2, N/4, PAY_HIDE]` | Text field defaulted to N, three quick buttons (full/half/quarter) |

`llSetPayPrice(PAY_HIDE, [PAY_HIDE×4])` hides the text input field entirely — that's the IDLE behavior. Using it on the AWAITING_PAYMENT entry would silently break payment (right-click → Pay shows nothing).

### 6.5 Lock semantics

- Acquired on first touch.
- Auto-cleared after 60s of inactivity (handled by `expiresAt` check on every state transition; each user-driven step extends 60s).
- Released eagerly: as soon as money() arrives and the first POST attempt fires, the lock releases so a second user can start a new menu while retries run in background.
- "Get Parcel Verifier" branch releases immediately after `llGiveInventory`.
- AWAITING_AUCTION_ID state lock timeout (user types a bad ID, walks away, etc.) covered by the 60s TTL.
- Busy chrome: floating text turns red and includes `<In Use>`; object name appended `<In Use>`. Both restored on release.

### 6.6 HTTP-in command handler — parallel and lock-independent

The HTTP-in handler runs in parallel to the touch-driven state machine. A backend-initiated PAYOUT/REFUND/WITHDRAW does not consume the touch lock and does not depend on terminal state. The two flows share only the script's debit permission and the `inflightCommands` map.

`inflightCommands` is keyed by the `txKey` from `llTransferLindenDollars` and stores `{idempotencyKey, recipientUuid, amount}` so the `transaction_result` callback can look up the record and POST `/payout-result`. Map is size-bounded (e.g., 16 entries — owner-say if exceeded; the backend's retry budget covers transient overflow).

### 6.7 Notecard

```
# config notecard for SLPA Terminal (unified payments)
REGISTER_URL=https://api.slparcelauctions.com/api/v1/sl/terminal/register
ESCROW_PAYMENT_URL=https://api.slparcelauctions.com/api/v1/sl/escrow/payment
LISTING_FEE_URL=https://api.slparcelauctions.com/api/v1/sl/listing-fee/payment
PENALTY_LOOKUP_URL=https://api.slparcelauctions.com/api/v1/sl/penalty-lookup
PENALTY_PAYMENT_URL=https://api.slparcelauctions.com/api/v1/sl/penalty-payment
PAYOUT_RESULT_URL=https://api.slparcelauctions.com/api/v1/sl/escrow/payout-result
SHARED_SECRET=<rotate via slpa.escrow.terminal-shared-secret>
TERMINAL_ID=<optional; defaults to (string)llGetKey()>
REGION_NAME=<optional; defaults to llGetRegionName()>
DEBUG_OWNER_SAY=true
```

Required: all six URLs + `SHARED_SECRET`. Optional: `TERMINAL_ID`, `REGION_NAME`, `DEBUG_OWNER_SAY`.

### 6.8 Deployment posture

- SLPA-team-deployed only. Operator owns the prim, holds debit permission, sets the notecard.
- ≥1 active SLPA Terminal must be live for the auction-completion path (PAYOUT) — admin tooling alerts if zero terminals in `terminals` table are `active=true` and seen recently.
- Multi-instance OK. Backend `TerminalCommandService` dispatcher picks any active terminal for any command.
- Marketplace distribution: NO. This script holds a shared secret and PERMISSION_DEBIT — never published.

### 6.9 Updating

**Two-place rule**: changing the parcel-verifier script requires updating it in:
1. **Marketplace listing** (republish a new revision)
2. **The inventory of every deployed SLPA Terminal** (drag-drop the new `.lsl` into the prim's contents; `CHANGED_INVENTORY` auto-resets which re-registers the terminal)

Forgetting place 2 leaves users with stale verifier objects from the give-on-touch menu while Marketplace customers get the new version. The README's "Updating" section will spell this out explicitly.

Updating the SLPA Terminal script itself: drag-drop new `.lsl` → `CHANGED_INVENTORY` reset → re-register. Updating the notecard: edit values → `CHANGED_INVENTORY` reset → re-register.

## 7. Trust model

| Script | Outbound auth | Inbound auth |
|---|---|---|
| Verification Terminal | `X-SecondLife-Shard` + `X-SecondLife-Owner-Key` (auto-injected, validated by backend `SlHeaderValidator`) | n/a |
| Parcel Verifier | same | n/a |
| SLPA Terminal | header gate **+** `sharedSecret` field in body of payment/register/payout-result requests | header gate **+** `sharedSecret` field in body of `TerminalCommandBody` (constant-time compare) |

Note that the penalty endpoints (`/sl/penalty-lookup` and `/sl/penalty-payment`) are header-gate-only on the backend — they don't carry a shared secret in the body. The SLPA Terminal still has its shared secret loaded for the other endpoints; it just doesn't include it in the penalty bodies.

### 7.1 Shared-secret rotation

Mirrors `sl-im-dispatcher` pattern:
1. Update `slpa.escrow.terminal-shared-secret` in the deployment's secret store.
2. Restart the backend so it picks up the new secret.
3. Edit the `config` notecard on each deployed SLPA Terminal — `CHANGED_INVENTORY` auto-resets the script, which re-registers with the new secret.
4. In-flight `TerminalCommand` rows: anything dispatched on the old secret will reject (terminal returns 403); the dispatcher's existing retry budget (4 attempts with 1m/5m/15m backoff per `EscrowRetryPolicy`) covers the brief rotation window.

The `terminal_commands.shared_secret_version` column exists but is not populated in Phase 1 (deferred — see DEFERRED_WORK.md).

## 8. Failure-handling matrix

| Failure | Verification Terminal | Parcel Verifier | SLPA Terminal |
|---|---|---|---|
| HTTP timeout / 5xx / status=0 | speak "✗ Backend unreachable" + release lock; user re-touches | speak + `llDie()` | bounded retry (10s/30s/90s/5m/15m) on payment POSTs; immediate fail on register/lookup |
| HTTP 4xx | parse problem-detail; speak `title`+`detail`; release lock | same; `llDie()` | parse + speak + CRITICAL log if payment-related; lock already released |
| dataserver timeout (DATA_BORN/PAYINFO) | 30s timeout; speak + release lock + `llListenRemove` | n/a | n/a |
| Code-entry timeout | covered by 60s lock | 90s on `llTextBox`; speak + `llDie()` | covered by 60s lock |
| Lock holder walks away | 60s lock auto-clears; busy chrome restored | n/a | 60s lock auto-clears; busy chrome restored |
| Region restart | not load-bearing (no HTTP-in) | not load-bearing | `changed(CHANGED_REGION_START)` → `llRequestURL` → re-POST `/terminal/register` |
| Notecard or inventory edit | auto-reset (`CHANGED_INVENTORY`) | same | same |
| `transaction_result` failure on payout | n/a | n/a | POST `/payout-result` with `success=false` + `errorMessage`; backend retry budget handles |
| HTTP-in `sharedSecret` mismatch | n/a | n/a | reply 403; no debit fired |
| Inflight commands map full | n/a | n/a | owner-say "queueing payment retry"; backend retry budget covers |
| `PERMISSION_DEBIT` denied at startup | n/a | n/a | CRITICAL owner-say + halt; manual operator re-grant required |

## 9. Testing strategy

LSL doesn't have a native unit-test framework, so testing is integration-driven:

- **Local dev** — run a Beta Grid (Aditi) instance with a sandbox sim. Override the `sim_channel` guard with a debug-build flag (or simply test on the Beta grid where `llGetEnv("sim_channel")` returns `"Second Life Beta Server"` — the script's startup guard will refuse, so for dev we point the script at a beta-grid backend deployment with the `slpa.sl.expected-shard` config set to `"Second Life Beta Server"` for that environment).
- **Endpoint dev** — backend already exposes dev-mode simulators (`/api/v1/dev/sl/simulate-verify`, `/api/v1/dev/auctions/{id}/pay`, `/api/v1/dev/escrow/{auctionId}/simulate-payment`). These remain in-place and provide a parallel test path that doesn't require an actual Beta-grid avatar.
- **Production validation** — staged rollout: deploy each script to one SLPA HQ instance first; monitor owner-say + backend logs (the existing `LSL_VERIFY` / `LSL_PARCEL_VERIFY` / `ESCROW_PAYMENT` log patterns) for one week; promote to additional instances after 0 anomalies.
- **Failure-mode exercises** — one-time before launch: deliberately stale the shared secret (HTTP 403), kill the backend mid-payment (5xx → bounded retry path), simulate region restart (script must re-register), simulate avatar pays then walks away (lock TTL).

LSL syntax verification is manual: paste each script into the SL viewer's script editor; compile-error wins are caught immediately.

## 10. Rollout plan

Order of deployment:

1. **SLPA Verification Terminal** — first, because it's the lowest-risk script (header-only trust) and the upstream user-acquisition step. Marketplace listing + one HQ instance.
2. **SLPA Parcel Verifier** — second, after at least a few accounts are verified to test it. Marketplace listing + verify it's also in SLPA Terminal inventory before step 3.
3. **SLPA Terminal** — last (highest-risk: shared secret + debit + L$). One HQ instance, manual end-to-end test of each menu option (with small L$), then add a second instance after 1 week of stable operation.

Each script's README ships with a "first-deploy checklist" enumerating: notecard values to set, ownership, permissions, expected startup messages, smoke-test path.

## 11. Acceptance criteria

- [ ] `lsl-scripts/verification-terminal/` directory exists with `verification-terminal.lsl`, `config.notecard.example`, `README.md` covering Purpose / Architecture / Deployment / Configuration / Operations / Troubleshooting / Limits / Security.
- [ ] `lsl-scripts/parcel-verifier/` directory exists with the same artifacts.
- [ ] `lsl-scripts/slpa-terminal/` directory exists with the same artifacts plus an "Updating" section that spells out the two-place rule.
- [ ] Top-level `lsl-scripts/README.md` index lists all four scripts.
- [ ] All three new scripts compile in the SL viewer's script editor with zero errors.
- [ ] Verification terminal: touch → code → POST → success/failure speech, with 60s lock + busy chrome.
- [ ] Parcel verifier: rez → auto-prompt for code → POST → llDie in all paths (success, failure, timeout).
- [ ] SLPA Terminal: all 4 menu options work end-to-end on a Beta Grid backend (Escrow Payment + Listing Fee require an `llTextBox` Auction ID prompt first, then pay; Pay Penalty does lookup-then-pay; Get Parcel Verifier delivers via `llGiveInventory`); HTTP-in command handler executes a test PAYOUT and posts result; region-restart re-registration verified.
- [ ] Pay-price matrix: ESCROW/LISTING_FEE selection shows custom-amount text field; PENALTY selection shows balance + half/quarter quick buttons; IDLE refuses payment.
- [ ] Listen-handle hygiene: every script tracks `llListenRemove` calls on every exit path (no leak under repeat-use).
- [ ] Bounded HTTP retry on payment POSTs: 10s/30s/90s/5m/15m, then CRITICAL owner-say (verified manually by killing backend mid-test).
- [ ] Shared-secret rotation procedure tested: edit notecard → script auto-resets → re-registers; old secret rejected.
- [ ] No script left behind on `llDie()` paths in parcel verifier (manual visual verification on rez).

## 12. References

- DESIGN.md §5.1 (Verification Terminal), §5.2 (Escrow Terminal), §5.3 (Parcel Verifier), §9 (LSL Communication Protocol), §10 (REST endpoints), §11 (Notification System)
- Existing backend: `SlVerificationController`, `SlParcelVerifyController`, `EscrowPaymentController`, `ListingFeePaymentController`, `PenaltyTerminalController`, `TerminalRegistrationController`, `PayoutResultController`, `TerminalHttpClientImpl`, `EscrowRetryPolicy`
- Existing LSL: `lsl-scripts/sl-im-dispatcher/dispatcher.lsl` (notecard parser pattern, owner-say cadence, `CHANGED_INVENTORY` reset, retry posture for low-stakes calls)
- DEFERRED_WORK.md sections under "LSL script for in-world verification terminal", "HMAC-SHA256 terminal auth", "Shared-secret version rotation provenance on TerminalCommand", "Smart regional routing for TerminalCommand dispatch", "HTTP-in push from backend to dispatcher for urgency"
- Epic 11 task breakdowns at `docs/implementation/epic-11/task-{01,02,03,04}-*.md` — superseded by this spec; the four task breakdowns predate Epics 04, 07, 08, 10's added LSL surfaces and are folded into the unified design here.
