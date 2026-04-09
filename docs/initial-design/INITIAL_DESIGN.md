# SLPA - Second Life Parcel Auctions

## Design Document

_Version 0.1 - April 9, 2026_

---

## 1. Overview

SLPA is a player-to-player land auction platform for Second Life. There is no existing competition - SL's built-in auction system only handles land repossessed by Linden Lab from unpaid fees. No third-party platform currently exists for players to auction their own parcels.

**Target market:** Any Second Life player who wants to sell land (parcels, homesteads, full regions) to the highest bidder, and any player looking to buy land.

**Revenue model:** Commission on completed sales (percentage of final auction price, exact rate TBD - likely 3-5%).

**This is NOT an Alchymie Labs project.**

---

## 2. Tech Stack

| Layer         | Technology                            | Rationale                                             |
|---------------|---------------------------------------|-------------------------------------------------------|
| Frontend      | Next.js (React)                       | SSR for SEO, fast page loads, strong ecosystem        |
| Backend       | Java (Spring Boot)                    | Heath's primary language, robust for financial logic  |
| Database      | PostgreSQL                            | Relational integrity for auction/financial data       |
| Real-time     | WebSockets (Spring WebSocket / STOMP) | Live bid updates, auction countdowns                  |
| Cache         | Redis                                 | Session management, bid rate limiting, auction timers |
| In-World      | LSL (Linden Scripting Language)       | Verification terminals, escrow payment objects        |
| External APIs | SL World API, SL Map API              | Ownership verification, parcel metadata               |

---

## 3. Architecture Overview

```
┌────────────────────────────────────────────────────────────┐
│                        FRONTEND                            │
│                     (Next.js / React)                      │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐    │
│  │ Register │  │  Browse  │  │  Auction │  │ Dashboard│    │
│  │ /Verify  │  │ Listings │  │  Room    │  │ (My Bids │    │
│  │          │  │          │  │ (Live WS)│  │  /Sales) │    │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘    │
└────────────────────────┬───────────────────────────────────┘
                         │ REST API + WebSocket
┌────────────────────────┴───────────────────────────────────┐
│                        BACKEND                             │
│                   (Java / Spring Boot)                     │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐    │
│  │ Auth &   │  │ Auction  │  │ Escrow   │  │ Verifica-│    │
│  │ Identity │  │ Engine   │  │ Manager  │  │ tion Svc │    │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘    │
│  ┌──────────┐  ┌──────────────────────────┐                │
│  │ Notifica-│  │ SL World API Client      │                │
│  │ tions    │  │ (Ownership Polling)      │                │
│  └──────────┘  └──────────────────────────┘                │
└────────────────────────┬───────────────────────────────────┘
                         │ HTTP (llHTTPRequest / HTTP-in)
┌────────────────────────┴─────────────────────────────────────┐
│                   IN-WORLD (Second Life)                     │
│  ┌──────────────────┐  ┌──────────────────┐                  │
│  │ SLPA Verification│  │ SLPA Escrow      │                  │
│  │ Terminal         │  │ Terminal         │                  │
│  │ (Player ID)      │  │ (L\$ Payment)    │                  │
│  └──────────────────┘  └──────────────────┘                  │
│  ┌──────────────────┐                                        │
│  │ Parcel Verifier  │                                        │
│  │ Object (rezzable)│                                        │
│  └──────────────────┘                                        │
└──────────────────────────────────────────────────────────────┘
```

---

## 4. User Flows

### 4.1 Player Verification (Identity)

Player verification is **separate from parcel verification**. You don't need to own land to register. You must be verified to list parcels OR place bids.

**Purpose:** Link a website account to a verified SL avatar identity.

**Two verification methods:**

#### Method A: In-World Terminal

1. User creates account on SLPA website (email + password)
2. Website displays a 6-digit verification code (time-limited, 15 minutes)
3. User goes to any SLPA Verification Terminal in-world and touches it
4. Terminal prompts for the 6-digit code via `llTextBox()`
5. Terminal sends to our backend via `llHTTPRequest`:
    - The entered code
    - Avatar UUID (from `llDetectedKey()`)
    - Avatar name (from `llDetectedName()`)
    - SL injects `X-SecondLife-Owner-Key` header (tamper-proof identity of script owner)
    - SL injects `X-SecondLife-Shard` header (ensures production grid, not beta)
6. Backend validates code, links avatar UUID to web account
7. Backend calls `world.secondlife.com/resident/{uuid}` to fetch avatar profile data (name, join date, image) and stores it
8. Account marked as verified

#### Method B: Rezzable Verification Object

1. Same as above (steps 1-2)
2. User obtains free SLPA Verification Object from SL Marketplace (or from a terminal)
3. User rezzes it anywhere they have rez rights
4. Object prompts for code, sends same data as terminal method
5. Object self-destructs after successful verification (`llDie()`)

**Security considerations:**
- `X-SecondLife-Owner-Key` is injected by SL's infrastructure on all outgoing HTTP requests - it cannot be spoofed by scripts
- `X-SecondLife-Shard` must equal `"Production"` (reject beta grid requests)
- Verification codes are single-use and time-limited
- One SL avatar per web account (enforced server-side)

---

### 4.2 Parcel Verification (Listing Creation)

A verified user wants to list a parcel for auction. We need to confirm they own it.

**Two methods:**

#### Method A: Manual UUID Entry

1. User enters their parcel UUID on the website
    - Most experienced SL users can find this in About Land or via a simple script
    - We can provide instructions: "Rez a prim, drop in this script, it will tell you your parcel UUID"
2. Backend calls `https://world.secondlife.com/place/{parcel_uuid}`
3. Parses HTML meta tags:
    - `ownerid` - must match the user's verified avatar UUID
    - `ownertype` - "agent" for individual, "group" for group-owned land
    - `area` - parcel size in sqm
    - `region` - region name
    - `description` - parcel description
    - `snapshot` - parcel image URL
    - `mat` - maturity rating (PG/Moderate/Adult)
4. If `ownerid` matches → parcel verified, listing created with auto-populated metadata
5. If `ownertype` is "group" → additional verification needed (see Section 8: Edge Cases)

#### Method B: Rezzable Parcel Verifier Object

1. User obtains SLPA Parcel Verifier object (free from Marketplace or terminals)
2. User rezzes it ON the parcel they want to list
3. Object reads parcel data via `llGetParcelDetails()`:
    - `PARCEL_DETAILS_ID` → parcel UUID
    - `PARCEL_DETAILS_OWNER` → owner UUID
    - `PARCEL_DETAILS_NAME` → parcel name
    - `PARCEL_DETAILS_AREA` → area in sqm
    - `PARCEL_DETAILS_DESC` → description
4. Object sends all data to backend via `llHTTPRequest`
5. Backend cross-references with World API for additional verification
6. Object can optionally stay rezzed for ongoing ownership monitoring, or self-destruct

**Why both methods?**
- Manual UUID is faster for power users who know their parcel UUID
- Rezzable object is easier for less technical users and provides additional LSL-side data
- Both paths end at the same World API verification

**Ongoing ownership monitoring:**
After listing creation, our backend periodically polls `world.secondlife.com/place/{uuid}` to confirm the seller still owns the parcel. If ownership changes unexpectedly (sold outside SLPA, land reclaimed), the listing is automatically suspended and the seller is notified.

---

### 4.3 Creating an Auction Listing

Once parcel ownership is verified:

1. **Auto-populated fields** (from World API / LSL data):
    - Parcel name
    - Region name
    - Area (sqm)
    - Prim capacity (from LSL `PARCEL_DETAILS_PRIM_CAPACITY` if verifier object used)
    - Maturity rating
    - Snapshot image
    - Description

2. **Seller-provided fields:**
    - Starting bid (L\$)
    - Reserve price (optional, L\$ - minimum price to sell)
    - Buy-it-now price (optional, L\$)
    - Auction duration (24h, 48h, 72h, 7 days, 14 days)
    - Additional photos (uploaded to website)
    - Seller's description / sales pitch
    - Land tier level / monthly cost info
    - Whether the parcel includes objects/builds (content sale)

3. **System fields:**
    - Listing ID (unique)
    - Created timestamp
    - Auction start/end timestamps
    - Status (draft → active → ended → completed/cancelled/expired)
    - Bid history
    - View count

---

### 4.4 Browsing & Searching

**Browse by:**
- Region / location (map integration)
- Parcel size range
- Price range (current bid)
- Maturity rating
- Land type (Mainland, Private Estate, Homestead)
- Auction status (active, ending soon)
- Sort: newest, ending soonest, most bids, lowest price, largest area

**Listing page shows:**
- All parcel metadata + seller description
- Live current bid + bid count
- Countdown timer
- Bid history (anonymized or public - configurable)
- Map embed / SLURL link
- Seller profile (verification badge, past sales rating)

---

### 4.5 Bidding

**Prerequisites:** Verified account (player verification complete).

**Bidding flow:**
1. User enters bid amount on listing page
2. Frontend validates: bid > current highest bid + minimum increment
3. Backend validates:
    - User is verified
    - User is not the seller
    - Bid meets minimum increment
    - Auction is still active
4. Bid recorded in database
5. WebSocket broadcast to all viewers of this listing: new highest bid
6. **Snipe protection:** If a bid is placed in the last 5 minutes, auction extends by 5 minutes
7. Previous high bidder notified (email + optional SL IM via terminal)

**Bid increments** (suggested):

| Current Price         | Minimum Increment |
|-----------------------|-------------------|
| L\$0 - L\$999         | L\$50             |
| L\$1,000 - L\$9,999   | L\$100            |
| L\$10,000 - L\$99,999 | L\$500            |
| L\$100,000+           | L\$1,000          |

**Proxy bidding (optional, future):**
User sets maximum bid. System auto-bids minimum increment above competing bids up to their max. Standard eBay-style proxy bidding.

---

### 4.6 Auction End & Escrow

When an auction ends with a winning bid at or above reserve price:

```
AUCTION ENDS
     │
     ▼
┌────────────────┐
│ Status: ENDED  │
│ Winner notified│
│ Seller notified│
└───────┬────────┘
        │
        ▼
┌────────────────────────────────────────┐
│ STEP 1: WINNER PAYS ESCROW             │
│                                        │
│ Winner goes to SLPA Escrow Terminal    │
│ in-world and pays the winning amount.  │
│                                        │
│ Terminal: money() event receives L\$   │
│ Terminal: llHTTPRequest to backend     │
│   - Payer UUID (llDetectedKey)         │
│   - Amount received                    │
│   - X-SecondLife-Owner-Key header      │
│ Backend: Confirms amount matches       │
│ Backend: Marks escrow as FUNDED        │
│                                        │
│ L\$ held by SLPA SL account            │
│ (terminal owner's account balance)     │
└───────────────┬────────────────────────┘
                │
                ▼
┌────────────────────────────────────────┐
│ STEP 2: SELLER TRANSFERS LAND          │
│                                        │
│ Seller goes to parcel in SL viewer:    │
│ About Land → "Sell Land"               │
│ Set "Sell to:" → winner's avatar name  │
│ Set price: L\$0                        │
│                                        │
│ (Manual step - SL has NO API for this) │
│                                        │
│ Seller confirms on SLPA website that   │
│ they've set the land for sale.         │
└───────────────┬────────────────────────┘
                │
                ▼
┌────────────────────────────────────────┐
│ STEP 3: WINNER CLAIMS LAND             │
│                                        │
│ Winner goes to parcel in SL viewer.    │
│ Clicks "Buy Land" (price L\$0, set     │
│ for sale to them specifically).        │
│                                        │
│ Winner confirms on SLPA website that   │
│ they've claimed the land.              │
└───────────────┬────────────────────────┘
                │
                ▼
┌────────────────────────────────────────┐
│ STEP 4: BACKEND VERIFIES TRANSFER      │
│                                        │
│ Backend polls World API:               │
│ world.secondlife.com/place/{uuid}      │
│ Checks: ownerid == winner's UUID?      │
│                                        │
│ If YES → transfer confirmed            │
│ If NO  → retry (poll every 5 min       │
│          for up to 72 hours)           │
└───────────────┬────────────────────────┘
                │
                ▼
┌────────────────────────────────────────┐
│ STEP 5: PAYOUT                         │
│                                        │
│ Backend instructs escrow terminal:     │
│ HTTP-in POST to terminal's URL         │
│ Terminal: llTransferLindenDollars()    │
│   → Pays seller (minus commission)     │
│ Terminal: transaction_result event     │
│   → Confirms success/failure           │
│ Terminal: llHTTPRequest to backend     │
│   → Reports transaction result         │
│                                        │
│ Backend: Marks auction COMPLETED       │
│ Both parties notified.                 │
└────────────────────────────────────────┘
```

**Timeout & dispute handling:**
- Winner has 48 hours to pay escrow after auction ends
- Seller has 72 hours to transfer land after escrow is funded
- Winner has 48 hours to claim land after seller sets it for sale
- If any timeout expires → automatic cancellation, L\$ refunded to buyer
- Dispute button available for both parties → manual review queue

---

## 5. In-World Objects

### 5.1 SLPA Verification Terminal

**Purpose:** Player identity verification.

**Physical form:** A branded kiosk/terminal object placed at popular SL locations.

**Script behavior:**
```
STATE: IDLE
  - Displays floating text: "Touch to verify your SLPA account"
  - On touch → llTextBox() prompts for 6-digit code
  - On code entry:
    - POST to backend: {code, avatar_uuid, avatar_name}
    - SL adds X-SecondLife-Owner-Key, X-SecondLife-Shard headers
    - Backend responds: success/failure
    - Display result to user via llDialog() or llInstantMessage()
```

**Distribution:** Place at high-traffic locations (welcome areas, shopping malls, info hubs). Free copies available from the terminal itself or SL Marketplace.

### 5.2 SLPA Escrow Terminal

**Purpose:** Receive L\$ payments from auction winners, pay out to sellers.

**Physical form:** A branded payment terminal. Can be co-located with verification terminals.

**Critical design considerations:**
- Owned by the SLPA service account
- Must have `PERMISSION_DEBIT` granted (for payouts)
- `llSetPayPrice()` configured dynamically based on pending transactions
- `money()` event handles incoming payments
- `llTransferLindenDollars()` handles outgoing payouts with `transaction_result` verification
- HTTP-in (`llRequestURL()`) for receiving payout commands from backend
- Rate limit: max 30 payments per 30 seconds per owner per region

**Script behavior:**
```
STATE: READY
  - llRequestURL() → registers HTTP-in URL with backend
  - llSetPayPrice(PAY_HIDE, [...]) → no payments until activated

ON HTTP-IN (from backend, payout command):
  - Verify request authenticity (shared secret in body)
  - llTransferLindenDollars(seller_uuid, amount)
  - transaction_result → report success/failure to backend

ON PAYMENT (money event):
  - Buyer UUID from money() event
  - Amount from money() event
  - POST to backend: {buyer_uuid, amount, listing_id}
  - Backend validates against expected payment
  - If unexpected → refund via llTransferLindenDollars()
```

**Security:**
- HTTP-in URLs change on region restart → terminal must re-register with backend
- Shared secret between terminal and backend for payout authorization
- All outgoing HTTP requests include SL's tamper-proof headers
- Terminal monitors `changed(CHANGED_REGION_START)` to re-register URL

**Multiple terminals:**
- For scalability, deploy multiple escrow terminals across regions
- Backend tracks which terminal holds which payment
- If a terminal goes offline (region restart), backend retries via other terminals or waits for re-registration

### 5.3 SLPA Parcel Verifier (Rezzable)

**Purpose:** Verify parcel ownership for listing creation.

**Physical form:** Small, unobtrusive object. Self-destructs after verification.

**Script behavior:**
```
ON REZ:
  - Read parcel data: llGetParcelDetails(llGetPos(), [...])
  - Collect: PARCEL_DETAILS_ID, PARCEL_DETAILS_OWNER, PARCEL_DETAILS_NAME,
             PARCEL_DETAILS_AREA, PARCEL_DETAILS_DESC, PARCEL_DETAILS_PRIM_CAPACITY
  - POST to backend via llHTTPRequest
  - Backend responds with success → llOwnerSay("Parcel verified!") → llDie()
  - Backend responds with failure → llOwnerSay("Verification failed: {reason}") → llDie()
```

**Distribution:** Free on SL Marketplace + available from verification terminals.

---

## 6. External API Integration

### 6.1 World API - Resident Lookup

**Endpoint:** `https://world.secondlife.com/resident/{avatar_uuid}`

**Returns (HTML meta tags):**
- `agentid` - avatar UUID (confirmation)
- `description` - profile text
- `imageid` - profile picture asset ID
- `mat` - maturity setting
- Page `<title>` contains display name + username

**Usage:** Profile enrichment after verification. Display seller/bidder info.

**Rate limiting:** Unknown/undocumented. Implement client-side rate limiting (1 req/sec max).

**Reliability:** Unofficial API, not guaranteed by Linden Lab. Cache responses aggressively. Have fallback for when it's down (use stored data from last successful fetch).

### 6.2 World API - Place/Parcel Lookup

**Endpoint:** `https://world.secondlife.com/place/{parcel_uuid}`

**Returns (HTML meta tags):**
- `ownerid` - owner avatar/group UUID
- `ownertype` - "agent" or "group"
- `owner` - owner name
- `area` - parcel area in sqm
- `region` - region name
- `location` - coordinates within region
- `description` - parcel description
- `snapshot` - parcel snapshot image URL
- `imageid` - snapshot asset ID
- `parcelid` - parcel UUID (confirmation)
- `mat` - maturity rating (PG_NOT, M_AO, etc.)
- `category` - parcel category
- `boost` - traffic/boost value

**Usage:**
1. Parcel ownership verification (manual UUID entry method)
2. Ongoing ownership monitoring during active listings
3. Transfer confirmation after auction completion
4. Auto-populating listing metadata

**Caveats:**
- Parcel UUID can only be obtained in-world (via `PARCEL_DETAILS_ID`) or provided by user
- Returns 404 for deleted/merged parcels
- Unofficial API - no SLA

### 6.3 Grid Survey API (Supplementary)

**Endpoint:** `http://api.gridsurvey.com/simquery.php?region={name}`

**Returns (plain text key-value):**
- Region status, coordinates, estate type, first/last seen dates, region UUID

**Usage:** Validate that a region exists and is online before listing. Supplementary data.

---

## 7. Database Schema (Key Entities)

### users
```sql
CREATE TABLE users (
    id              BIGSERIAL PRIMARY KEY,
    email           VARCHAR(255) UNIQUE NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    sl_avatar_uuid  UUID UNIQUE,
    sl_avatar_name  VARCHAR(255),
    sl_display_name VARCHAR(255),
    sl_join_date    DATE,
    verified        BOOLEAN DEFAULT FALSE,
    verified_at     TIMESTAMP,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);
```

### parcels
```sql
CREATE TABLE parcels (
    id              BIGSERIAL PRIMARY KEY,
    parcel_uuid     UUID UNIQUE NOT NULL,
    owner_id        BIGINT REFERENCES users(id),
    parcel_name     VARCHAR(255),
    region_name     VARCHAR(255),
    area_sqm        INTEGER,
    prim_capacity   INTEGER,
    maturity        VARCHAR(20),
    description     TEXT,
    snapshot_url     VARCHAR(512),
    location        VARCHAR(100),
    verified        BOOLEAN DEFAULT FALSE,
    verified_at     TIMESTAMP,
    last_checked    TIMESTAMP,
    created_at      TIMESTAMP DEFAULT NOW()
);
```

### auctions
```sql
CREATE TABLE auctions (
    id              BIGSERIAL PRIMARY KEY,
    parcel_id       BIGINT REFERENCES parcels(id),
    seller_id       BIGINT REFERENCES users(id),
    status          VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
        -- DRAFT, ACTIVE, ENDED, ESCROW_PENDING, ESCROW_FUNDED,
        -- TRANSFER_PENDING, COMPLETED, CANCELLED, EXPIRED, DISPUTED
    starting_bid    INTEGER NOT NULL,       -- L\$
    reserve_price   INTEGER,                -- L\$ (optional)
    buy_now_price   INTEGER,                -- L\$ (optional)
    current_bid     INTEGER DEFAULT 0,      -- L\$
    bid_count       INTEGER DEFAULT 0,
    winner_id       BIGINT REFERENCES users(id),
    duration_hours  INTEGER NOT NULL,
    starts_at       TIMESTAMP,
    ends_at         TIMESTAMP,
    seller_desc     TEXT,
    commission_rate DECIMAL(5,4) DEFAULT 0.0500,  -- 5%
    commission_amt  INTEGER,                -- L\$ (calculated at completion)
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);
```

### bids
```sql
CREATE TABLE bids (
    id              BIGSERIAL PRIMARY KEY,
    auction_id      BIGINT REFERENCES auctions(id),
    bidder_id       BIGINT REFERENCES users(id),
    amount          INTEGER NOT NULL,       -- L\$
    placed_at       TIMESTAMP DEFAULT NOW(),
    ip_address      INET
);
CREATE INDEX idx_bids_auction ON bids(auction_id, amount DESC);
```

### escrow_transactions
```sql
CREATE TABLE escrow_transactions (
    id              BIGSERIAL PRIMARY KEY,
    auction_id      BIGINT REFERENCES auctions(id),
    payer_id        BIGINT REFERENCES users(id),
    payee_id        BIGINT REFERENCES users(id),
    type            VARCHAR(20) NOT NULL,   -- PAYMENT, PAYOUT, REFUND, COMMISSION
    amount          INTEGER NOT NULL,       -- L\$
    sl_transaction_id VARCHAR(100),         -- from llTransferLindenDollars
    terminal_id     VARCHAR(100),           -- which escrow terminal handled this
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
        -- PENDING, COMPLETED, FAILED, REFUNDED
    created_at      TIMESTAMP DEFAULT NOW(),
    completed_at    TIMESTAMP
);
```

### verification_codes
```sql
CREATE TABLE verification_codes (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT REFERENCES users(id),
    code            VARCHAR(6) NOT NULL,
    type            VARCHAR(20) NOT NULL,   -- PLAYER, PARCEL
    expires_at      TIMESTAMP NOT NULL,
    used            BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMP DEFAULT NOW()
);
```

---

## 8. Edge Cases & Risk Mitigation

### Group-Owned Land
- World API returns `ownertype: "group"` for group-owned parcels
- Seller must prove they're an officer/owner of that group with land management rights
- Possible verification: seller sets parcel description to a temp string we provide, we check via World API
- Group land transfers work differently - the group deeds land, not an individual
- **MVP decision:** Support individual-owned land only at launch. Group land is a Phase 2 feature.

### Parcel Subdivision/Merge During Auction
- If a seller subdivides or merges the parcel during an active auction, the UUID may change
- Backend detects this when the World API returns 404 for the stored UUID
- Auction auto-suspended, seller notified, requires re-verification

### Region Restart / Downtime
- Escrow terminal HTTP-in URLs become invalid on region restart
- Terminal listens for `changed(CHANGED_REGION_START)` and re-registers with backend
- Backend queues payout commands and retries when terminal comes back online
- Consider deploying terminals in multiple regions for redundancy

### Seller Sells Land Outside SLPA
- Backend polls World API periodically during active auctions
- If `ownerid` changes → auction immediately cancelled, all bidders notified
- If this happens during escrow → automatic refund to buyer

### Payment Failures
- `llTransferLindenDollars` can fail (insufficient funds, rate limit)
- `transaction_result` event provides success/failure + reason
- On payout failure → retry up to 3 times with backoff → manual review queue
- On payment failure (buyer) → inform buyer, allow retry

### Shill Bidding Prevention
- One verified SL avatar per web account
- Seller cannot bid on own auction
- Rate limiting on bid placement
- Suspicious pattern detection (same IP, bids always from same accounts, last-second bids from new accounts)
- Report button for suspected shill bidding

### Escrow Terminal Security
- The SLPA SL account holds all escrowed L\$
- `PERMISSION_DEBIT` is a powerful permission - if terminal script is compromised, account can be drained
- Mitigations:
    - Terminal only executes payouts authorized by backend via signed HTTP-in requests
    - Shared secret rotated periodically
    - Backend rate-limits payout requests
    - Daily balance reconciliation: sum of pending escrow should equal account balance
    - Alert on unexpected balance changes

### SL Terms of Service
- Must comply with SL's TOS regarding L\$ transactions
- L\$ is not real currency - it's a "license" per SL TOS
- Our service is essentially a marketplace facilitator, similar to SL Marketplace
- Review TOS sections on scripted L\$ transactions, third-party services
- **TODO:** Legal review before launch

---

## 9. LSL Script Communication Protocol

### Outgoing (Script → Backend)

All in-world scripts communicate with our backend via `llHTTPRequest`:

```
POST https://api.slpa.com/v1/sl/{endpoint}
Content-Type: application/x-www-form-urlencoded

Headers automatically added by SL:
  X-SecondLife-Owner-Key: {script owner UUID}
  X-SecondLife-Owner-Name: {script owner name}
  X-SecondLife-Object-Key: {object UUID}
  X-SecondLife-Object-Name: {object name}
  X-SecondLife-Region: {region name (x,y)}
  X-SecondLife-Local-Position: {position in region}
  X-SecondLife-Shard: Production  ← MUST verify this
```

**Endpoints:**
- `POST /v1/sl/verify` - Player verification (code + avatar data)
- `POST /v1/sl/parcel/verify` - Parcel verification (parcel data from LSL)
- `POST /v1/sl/escrow/payment` - Payment received notification
- `POST /v1/sl/escrow/payout-result` - Payout transaction result
- `POST /v1/sl/terminal/register` - Terminal URL registration (HTTP-in)

**Security validation (on every request):**
1. Check `X-SecondLife-Shard == "Production"` (reject beta grid)
2. Verify `X-SecondLife-Owner-Key` matches expected SLPA service account
3. Validate request body contents
4. Rate limit per object/region

### Incoming (Backend → Script)

Backend communicates with in-world scripts via HTTP-in:

```
POST {terminal_http_in_url}
Content-Type: text/plain

Body: JSON payload with shared secret + command
{
  "secret": "rotating_shared_secret",
  "command": "payout",
  "seller_uuid": "xxx",
  "amount": 5000,
  "auction_id": 12345
}
```

**Challenges:**
- HTTP-in URLs are temporary (change on region restart, script reset)
- Terminal must re-register URL with backend after any restart
- Backend must handle stale URLs gracefully (retry, wait for re-registration)
- HTTP-in URL format: `https://simhost-{hash}.agni.secondlife.io:12043/cap/{uuid}`

---

## 10. Notification System

Users should be notified of key events through multiple channels:

| Event                   | Email | Website       | SL IM (via terminal) |
|-------------------------|-------|---------------|----------------------|
| Outbid                  | ✓     | ✓ (WebSocket) | Optional             |
| Auction won             | ✓     | ✓             | ✓                    |
| Auction ended (seller)  | ✓     | ✓             | ✓                    |
| Escrow funded           | ✓     | ✓             | ✓                    |
| Land transfer confirmed | ✓     | ✓             | ✓                    |
| Payout sent             | ✓     | ✓             | ✓                    |
| Listing suspended       | ✓     | ✓             | ✓                    |
| Auction cancelled       | ✓     | ✓             | ✓                    |

SL IMs sent via `llInstantMessage(avatar_uuid, message)` from terminal scripts.

---

## 11. Revenue & Economics

**Commission structure (proposed):**
- 5% of final sale price, charged to seller
- Deducted from escrow payout automatically
- Minimum commission: L\$50
- No listing fee (encourages listings)
- No bidding fee

**Example:**
- Parcel sells for L\$10,000
- Commission: L\$500 (5%)
- Seller receives: L\$9,500
- L\$10,000 → US\$39.22 at current rates (L\$255 = US\$1)
- Commission: ~US\$1.96

**Operational costs:**
- SLPA SL account: Premium membership (~US\$11.99/month) for increased L\$ transaction limits
- Small land parcel to host terminals: ~US\$7/month (1024 sqm Mainland)
- Web hosting: ~US\$20-50/month (VPS)
- Domain: ~US\$12/year
- Total: ~US\$40-70/month base cost

---

## 12. MVP Scope (Phase 1)

**In scope:**
- Player verification (terminal method only)
- Parcel verification (both manual UUID + rezzable object)
- Basic auction creation and management
- Real-time bidding with WebSocket updates
- Snipe protection (5-minute extension)
- Escrow payment and payout via terminal
- World API ownership verification and transfer confirmation
- Email notifications
- Basic user dashboard (my auctions, my bids)
- Mobile-responsive frontend

**Out of scope (Phase 2+):**
- Proxy/automatic bidding
- Group-owned land support
- Auction templates / relisting
- Seller/buyer ratings and reviews
- Advanced search (map-based browsing)
- SL Marketplace integration
- Multiple currency support
- API for third-party tools
- Mobile app
- Watchlist / saved searches
- Auction categories / featured listings

---

## 13. Open Questions

1. **Exact commission rate?** 3% vs 5% vs tiered based on sale price?
2. **Minimum auction price?** Should we set a floor to prevent spam listings?
3. **SLPA SL account:** Need to create a dedicated SL account for the service. Premium?
4. **Legal/TOS review:** Need to confirm SL TOS allows third-party escrow services.
5. **Terminal distribution:** How many initial locations? Partnership with existing SL businesses?
6. **Dispute resolution:** Manual process or automated? Who arbitrates?
7. **Anti-fraud:** How aggressive on shill bidding detection? Block or flag?
8. **World API reliability:** What's the fallback if Linden Lab deprecates it?
9. **Domain name:** slpa.com? slauctions.com? Something else?
10. **Reserve price visibility:** Show that a reserve exists (like eBay) or keep hidden?

---

## 14. File Structure (Proposed)

```
slpa/
├── DESIGN.md                 # This document
├── frontend/                 # Next.js app
│   ├── src/
│   │   ├── app/              # Next.js app router
│   │   ├── components/       # React components
│   │   ├── hooks/            # Custom hooks (WebSocket, auth)
│   │   └── lib/              # Utilities, API client
│   └── package.json
├── backend/                  # Java Spring Boot
│   ├── src/main/java/
│   │   └── com/slpa/
│   │       ├── auction/      # Auction engine
│   │       ├── auth/         # Authentication + SL verification
│   │       ├── escrow/       # Escrow management
│   │       ├── parcel/       # Parcel verification + monitoring
│   │       ├── notification/ # Email + SL IM notifications
│   │       ├── sl/           # SL API client (World API)
│   │       └── websocket/    # WebSocket config + handlers
│   └── pom.xml
├── lsl/                      # LSL scripts for in-world objects
│   ├── verification-terminal.lsl
│   ├── escrow-terminal.lsl
│   ├── parcel-verifier.lsl
│   └── utils.lsl            # Shared LSL functions
└── docs/                     # Additional documentation
    ├── api-spec.yaml         # OpenAPI spec
    ├── deployment.md
    └── sl-tos-review.md
```

---

_End of Design Document_