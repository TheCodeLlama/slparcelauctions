# SLPA - Second Life Parcel Auctions

## Design Document

_Version 0.2 - April 9, 2026_

---

## 1. Overview

SLPA is a player-to-player land auction platform for Second Life. There is no existing competition - SL's built-in auction system only handles land repossessed by Linden Lab from unpaid fees. No third-party platform currently exists for players to auction their own parcels.

**Target market:** Any Second Life player who wants to sell Mainland land to the highest bidder, and any player looking to buy land. **Phase 1 supports Mainland parcels only.** Private islands/estates are a future phase (see Open Questions).

**Revenue model:** Commission on completed sales (percentage of final auction price, exact rate TBD - likely 3-5%).

**This is NOT an Alchymie Labs project.**

---

## 2. Tech Stack

| Layer | Technology | Rationale |
|-------|-----------|-----------|
| Frontend | Next.js (React) | SSR for SEO, fast page loads, strong ecosystem |
| Backend | Java (Spring Boot) | Heath's primary language, robust for financial logic |
| Database | PostgreSQL | Relational integrity for auction/financial data |
| Real-time | WebSockets (Spring WebSocket / STOMP) | Live bid updates, auction countdowns |
| Cache | Redis | Session management, bid rate limiting, auction timers |
| In-World | LSL (Linden Scripting Language) | Verification terminals, escrow payment objects |
| External APIs | SL World API, SL Map API | Ownership verification, parcel metadata |

---

## 3. Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                        FRONTEND                              │
│                     (Next.js / React)                        │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐    │
│  │ Register │  │  Browse  │  │  Auction │  │ Dashboard│    │
│  │ /Verify  │  │ Listings │  │  Room    │  │ (My Bids │    │
│  │          │  │          │  │ (Live WS)│  │  /Sales) │    │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘    │
└────────────────────────┬────────────────────────────────────┘
                         │ REST API + WebSocket
┌────────────────────────┴────────────────────────────────────┐
│                        BACKEND                               │
│                   (Java / Spring Boot)                        │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐    │
│  │ Auth &   │  │ Auction  │  │ Escrow   │  │ Verifica-│    │
│  │ Identity │  │ Engine   │  │ Manager  │  │ tion Svc │    │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘    │
│  ┌──────────┐  ┌──────────────────────────┐                  │
│  │ Notifica-│  │ SL World API Client      │                  │
│  │ tions    │  │ (Ownership Polling)       │                  │
│  └──────────┘  └──────────────────────────┘                  │
└────────────────────────┬────────────────────────────────────┘
                         │ HTTP (llHTTPRequest / HTTP-in)
┌────────────────────────┴────────────────────────────────────┐
│                   IN-WORLD (Second Life)                      │
│  ┌──────────────────┐  ┌──────────────────┐                  │
│  │ SLPA Verification│  │ SLPA Escrow      │                  │
│  │ Terminal         │  │ Terminal         │                  │
│  │ (Player ID)      │  │ (L$ Payment)     │                  │
│  └──────────────────┘  └──────────────────┘                  │
│  ┌──────────────────┐                                        │
│  │ Parcel Verifier  │                                        │
│  │ Object (rezzable)│                                        │
│  └──────────────────┘                                        │
└─────────────────────────────────────────────────────────────┘
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
5. Terminal gathers avatar data in-world:
    - Avatar UUID (from `llDetectedKey()`)
    - Legacy name (from `llDetectedName()`)
    - Display name (from `llGetDisplayName()`)
    - Username (from `llGetUsername()`)
    - Account creation date (from `llRequestAgentData(uuid, DATA_BORN)` → dataserver event)
    - Payment info status (from `llRequestAgentData(uuid, DATA_PAYINFO)` → dataserver event)
6. Terminal sends ALL data to backend via `llHTTPRequest`:
    - The entered verification code
    - All avatar data from step 5
    - SL auto-injects `X-SecondLife-Owner-Key` header (tamper-proof identity of script owner)
    - SL auto-injects `X-SecondLife-Shard` header (ensures production grid, not beta)
    - SL auto-injects `X-SecondLife-Region` header (region name + coordinates)
7. Backend validates code, links avatar UUID to web account, stores all avatar data
8. Account marked as verified
    - Profile picture is user-uploaded on the website (not pulled from SL)

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

A verified user wants to list a parcel for auction. We need to confirm they own/control it.

**Three methods (seller chooses one):**

#### Method A: Manual UUID Entry (Individual-Owned Land Only)

1. User enters their parcel UUID on the website
    - Most experienced SL users can find this in About Land or via a simple script
    - We can provide instructions: "Rez a prim, drop in this script, it will tell you your parcel UUID"
2. Backend calls `https://world.secondlife.com/place/{parcel_uuid}`
3. Parses HTML meta tags:
    - `ownerid` - must match the user's verified avatar UUID
    - `ownertype` - must be "agent" (this method does NOT work for group-owned land)
    - `area` - parcel size in sqm
    - `region` - region name
    - `description` - parcel description
    - `snapshot` - parcel image URL
    - `mat` - maturity rating (PG/Moderate/Adult)
4. If `ownerid` matches → parcel verified, listing created with auto-populated metadata
5. If `ownertype` is "group" → seller must use Method C (Sale-to-Bot)

#### Method B: Rezzable Parcel Verifier Object (Individual-Owned Land Only)

1. User obtains SLPA Parcel Verifier object (free from Marketplace or terminals)
2. User rezzes it ON the parcel they want to list
3. Object reads parcel data via `llGetParcelDetails()`:
    - `PARCEL_DETAILS_ID` → parcel UUID
    - `PARCEL_DETAILS_OWNER` → owner UUID (must be an avatar, not a group)
    - `PARCEL_DETAILS_NAME` → parcel name
    - `PARCEL_DETAILS_AREA` → area in sqm
    - `PARCEL_DETAILS_DESC` → description
4. Object sends all data to backend via `llHTTPRequest`
5. Backend cross-references with World API for additional verification
6. Object can optionally stay rezzed for ongoing ownership monitoring, or self-destruct
7. If `PARCEL_DETAILS_OWNER` returns a group UUID → seller must use Method C

#### Method C: Sale-to-Bot Verification (Required for Group-Owned Land, Optional for Individual)

**The core insight:** only someone with actual authority over a parcel can set it for sale. The act of setting land for sale to the SLPA bot **is** the proof of authority. No tokens, no codes, no description editing needed.

**Flow:**

1. Seller creates listing on website, selects "Sale-to-Bot Verification"
2. Website instructs seller:
    - "Set your parcel for sale to **SLPAEscrow Resident** at **L$999,999,999**"
    - Provides the primary escrow account's avatar name and a link to their SL profile for easy identification
    - This is always the same account - every seller on the platform uses the same sale target
    - The absurdly high price is **seller protection** - ensures SLPA cannot purchase the land for less than auction value even in a catastrophic bug scenario
3. Seller goes to parcel in SL viewer → About Land → "Sell Land":
    - Sets "Sell to:" → SLPAEscrow Resident (the permanent primary escrow account)
    - Sets price: L$999,999,999
4. Seller clicks "Verify" on website
5. Backend dispatches an SLPA bot (from bot pool) to teleport to the region
6. A **worker bot** (from the monitoring pool) teleports to region, reads `ParcelProperties` via viewer protocol, checking:
    - `AuthBuyerID` matches the **primary escrow account UUID** (SLPAEscrow Resident)
    - `SalePrice` is L$999,999,999 (the expected sentinel value)
    - Parcel owner matches seller's verified avatar UUID (individual) OR is a group UUID (group land)
    - Parcel area, name, location data for auto-populating listing
7. If all checks pass → **verification complete**, listing proceeds

**Why this works for group-owned land:**
- In SL, only group members with the **"Set land for sale info"** (`LandSetSale`) group role ability can set group-deeded land for sale
- If the seller successfully sets the land for sale to the bot, they've cryptographically proven they have that permission
- No need to check group membership, roles, or permissions separately - the action IS the proof
- We don't even need to know which group owns the land or what the seller's role is

**Why the high price matters:**
- Set to L$999,999,999 (maximum) as a sentinel value
- The primary escrow account will NEVER execute a buy - it doesn't even log in, it's just a UUID target
- The worker bots that do the monitoring have no buy logic either
- But even if something goes catastrophically wrong, no SLPA account will have L$999M, so the transaction would fail
- **This is seller protection**: "We literally cannot steal your land because the price is set to a billion L$"

**Ongoing monitoring (during active auction):**
- Land must remain set for sale to the SLPA bot for the duration of the auction
- Worker bot checks every 30 minutes:
    - Is land still for sale? (`SalePrice` > 0)
    - Is `AuthBuyerID` still set to the primary escrow account?
    - Is ownership unchanged?
- If any check fails → auction paused, seller notified with 24-hour window to re-set
- If not restored within 24 hours → auction cancelled, all bidders notified
- Two consecutive failed checks → escalated warning

**Two types of bot accounts:**
- **Primary escrow account** (SLPAEscrow Resident): Permanent, never logs in, never teleports. Its only purpose is being the `AuthBuyerID` target. All sellers set their land for sale to this one account. This account carries zero L$ and has no scripts.
- **Worker bots**: The pool of bots that actually log in, teleport to regions, and read `ParcelProperties`. They verify that `AuthBuyerID` points to the primary escrow account. Workers are disposable - if one gets banned or goes offline, the others pick up its tasks.

**Scaling (worker bots only):**
- 6 teleports/minute per worker (hard SL limit)
- 30-minute check cycle = 180 teleports per worker per cycle
- With parcel clustering (multiple parcels in same region), effective capacity is higher
- Scaling: ~5-12 workers for 2,000 active listings (see Section 5.4 for details)

**Why three methods?**
- Methods A & B are fastest for individual land owners who don't want to deal with sale settings
- Method C is the **only option** for group-owned land
- Method C provides the strongest verification for individual land too (proves real control, not just UUID ownership)
- Sellers can choose based on their comfort level and land type

**Ongoing ownership monitoring (all methods):**
After listing creation, backend periodically polls `world.secondlife.com/place/{uuid}` to confirm the seller still owns the parcel. If ownership changes unexpectedly (sold outside SLPA, land reclaimed), the listing is automatically suspended and the seller is notified. For Method C listings, the bot's 30-minute checks provide additional real-time verification beyond the World API poll.

---

### 4.3 User Profiles

All verified users can customize their SLPA profile:

1. **Profile picture** - uploaded via website (not pulled from SL)
    - Max size: 2MB, formats: JPG, PNG, WebP
    - Cropped to square, stored in multiple sizes (64px, 128px, 256px)
    - Default: SL profile image pulled from World API at verification time
    - User can replace with custom upload at any time

2. **Display name** - defaults to SL display name, can be overridden
3. **Bio/description** - free text, displayed on profile page
4. **Contact preferences** - email notifications on/off, SL IM notifications on/off
5. **Realty group badge** - if member of a realty group, shown on profile

---

### 4.4 Realty Groups

Realty Groups model real estate brokerages. A group has one leader (broker) and multiple agents who can list and manage land on behalf of the group.

#### 4.4.1 Realty Group Structure

```
┌─────────────────────────────────────────┐
│           REALTY GROUP                   │
│                                         │
│  Name: "Mainland Realty Co."            │
│  Logo: [uploaded image]                 │
│  Description: "Premier mainland..."     │
│  Website: "https://..."                 │
│                                         │
│  ┌─────────┐                            │
│  │ LEADER  │  (1 per group)             │
│  │ (Broker)│  Full admin rights         │
│  └─────────┘                            │
│                                         │
│  ┌───────┐ ┌───────┐ ┌───────┐         │
│  │ Agent │ │ Agent │ │ Agent │         │
│  │  #1   │ │  #2   │ │  #3   │         │
│  └───────┘ └───────┘ └───────┘         │
│                                         │
│  Group Stats:                           │
│  - Total sales: 47                      │
│  - Active listings: 12                  │
│  - Avg rating: 4.8/5                    │
└─────────────────────────────────────────┘
```

#### 4.4.2 Roles & Permissions

**Leader (Broker):**
- Creates the realty group
- Uploads group logo, description, contact info
- Invites/removes agents
- Transfers leadership to another agent
- Can list land for the realty group
- Can manage ALL realty group listings (edit, cancel, respond to disputes)
- Can view all realty group escrow transactions and payout history
- Sets realty group-level commission split (how commission is divided between realty group and agent)
- Can dissolve the realty group

**Agent:**
- Accepts invitation to join a realty group
- Can list land for the realty group (shown as "Listed by [Agent] of [Realty Group]")
- Can manage their OWN realty group listings
- Cannot manage other agents' listings
- Cannot invite/remove other agents
- Cannot modify realty group profile
- Can leave the realty group at any time
- Can belong to only ONE realty group at a time

#### 4.4.3 Realty Group Creation Flow

1. Verified user navigates to "Create Realty Group" on dashboard
2. Fills in:
    - Realty group name (unique across platform)
    - Realty group logo (image upload, same specs as profile pic)
    - Description (rich text - what regions they specialize in, history, etc.)
    - Contact info (optional website, SL group link)
3. User becomes the Leader automatically
4. Realty group page created at `/group/{slug}`

#### 4.4.4 Agent Invitation Flow

1. Leader goes to group management page
2. Enters agent's SLPA username or SL avatar name
3. System sends invitation (email + website notification + optional SL IM)
4. Agent logs into SLPA, sees pending invitation
5. Agent accepts → added to group with Agent role
6. Agent declines → invitation removed

**Constraints:**
- Agent must be a verified SLPA user
- Agent cannot already belong to another realty group
- Pending invitations expire after 7 days
- Leader can revoke pending invitations

#### 4.4.5 Listing Under a Realty Group

When creating an auction listing, a realty group agent/leader sees an additional option:

- **List as:** [Individual] / [Group Name]

If listing under a realty group:
- The listing page shows:
    - "Listed by **[Agent Name]** of **[Group Name]**" with both profile pics
    - Group logo/badge on the listing card in search results
    - Link to group profile page
- Parcel ownership verification still requires the **parcel owner** to be:
    - The listing agent's verified SL avatar, OR
    - Any verified member of the realty group, OR
    - An SL group that the realty group has registered (see 4.4.7)
- Escrow payout goes to the parcel owner's SLPA account (not the listing agent)
    - Commission split between platform and realty group is handled on our side

#### 4.4.6 Realty Group Profile Page

Public-facing page at `/group/{slug}` showing:

- Realty group logo and name
- Description
- Leader profile (name + pic)
- All agents (name + pic + individual listing count)
- Active listings by the realty group
- Completed sales count
- Member since date
- Rating (when review system is implemented)

#### 4.4.7 SL Group Correlation (for Group-Owned Land)

Realty groups can register SL groups they manage land for:

1. Leader provides the SL Group UUID
2. Backend fetches `world.secondlife.com/group/{uuid}` to get group info
3. Verification: Leader must prove association with the SL group
    - Set the SL group's "About" text to include a temp verification code, OR
    - Have the SL group's founder verify via an SLPA terminal
4. Once verified, any realty group agent can list parcels owned by that SL group
5. World API check: `ownertype == "group"` and `ownerid` matches registered SL group UUID

#### 4.4.8 Commission Split

Leader configures how the platform commission is noted on group listings:
- The platform commission (e.g., 5%) is always deducted first
- Group can optionally add an additional agent fee on top (configured per-group)
- Example: 5% platform + 2% group fee = 7% total deducted from sale
- Group fee split between group treasury and listing agent (configurable by leader)

---

### 4.5 Creating an Auction Listing

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
    - Starting bid (L$)
    - Reserve price (optional, L$ - minimum price to sell)
    - Buy-it-now price (optional, L$)
    - Auction duration (24h, 48h, 72h, 7 days, 14 days)
    - **Snipe protection:** opt-in toggle (default off)
        - If enabled, configurable extension window: 5, 10, 15, 30, or 60 minutes
        - Any bid placed within the extension window before auction end extends the auction by that amount
    - Additional photos (uploaded to website)
    - Seller's description / sales pitch
    - Land tier level / monthly cost info
    - Whether the parcel includes objects/builds (content sale)
    - **List as:** Individual or Realty Group (if member of one)

3. **System fields:**
    - Listing ID (unique)
    - Created timestamp
    - Auction start/end timestamps (set when listing goes ACTIVE, not at creation)
    - Status lifecycle (see below)
    - Bid history
    - View count
    - Listing agent (user ID)
    - Realty group (group ID, if applicable)
    - Verification tier (SCRIPT, BOT, HUMAN)

### 4.5.1 Listing Status Lifecycle

```
DRAFT ──────────────────────────────────────────────────────────┐
  │  User creates listing, fills in all details.                │
  │  Listing is NOT visible to public.                          │
  │  User can preview exactly how listing will appear.          │
  │                                                             │
  ▼                                                             │
DRAFT_PAID ─────────────────────────────────────────────────────┤
  │  User pays listing fee at in-world terminal.                │
  │  Payment confirmed by backend.                              │
  │  Listing is still NOT visible to public.                    │
  │  Verification process begins (or user manually triggers).   │
  │                                                             │
  ▼                                                             │
VERIFICATION_PENDING ───────────────────────────────────────────┤
  │  Bot/script verification in progress.                       │
  │  Bot attempts to teleport to region & read parcel data.     │
  │  If access denied → seller notified to grant access.        │
  │  48-hour timeout to complete verification.                  │
  │                                                             │
  ├──→ VERIFICATION_FAILED (access denied, ownership mismatch, │
  │     timeout). Listing fee refunded. Returns to DRAFT.       │
  │                                                             │
  ▼                                                             │
ACTIVE ─────────────────────────────────────────────────────────┤
  │  Verification passed. Listing is NOW visible to public.     │
  │  Auction countdown starts NOW (not at creation time).       │
  │  starts_at and ends_at set at this moment.                  │
  │                                                             │
  ▼                                                             │
ENDED → ESCROW_PENDING → ESCROW_FUNDED → TRANSFER_PENDING      │
  → COMPLETED                                                   │
                                                                │
  At any point: → CANCELLED (by seller) or EXPIRED (timeout)   │
  DISPUTED can be entered from ESCROW_FUNDED or TRANSFER_PENDING│
```

**Key rules:**
- Listing fee is PAID before verification starts (DRAFT → DRAFT_PAID)
- Listing is NEVER visible to public until ACTIVE
- Auction clock starts when status transitions to ACTIVE (not at creation)
- User can preview listing at any draft stage
- If verification fails, listing fee is refunded and listing returns to DRAFT for editing
- Seller can cancel from DRAFT or DRAFT_PAID (full refund) or ACTIVE (no refund on listing fee)

### 4.5.2 Verification Tiers

Each listing receives a verification tier badge visible to buyers. Tier is determined by the verification method used:

**🔵 Script-Verified** (free, automatic) - Methods A or B
- Verification object rezzed on parcel OR manual UUID entry
- Confirms: ownership via `llGetParcelDetails()` / World API, parcel name, area, prim capacity, parcel UUID
- Limitations: Cannot verify ongoing sale status, cannot be used for group-owned land
- Badge: "Script Verified" with blue checkmark
- **No bot access required** - works even on fully restricted parcels
- Ongoing monitoring: World API polling only (every 30 minutes)

**🟢 Bot-Verified** (free, automatic) - Method C (Sale-to-Bot)
- SLPA bot (LibreMetaverse) teleports to region and reads full parcel data via viewer protocol
- Confirms: seller has actual control over parcel (proved by setting sale to bot), plus sale status, sale price, AuthBuyerID, category, description, snapshot, traffic, covenant
- Cross-references region data with Grid Survey (region age, estate type, uptime history)
- **Mainland check:** Grid Survey `estate` field must be "Mainland". If estate type is anything else (Private Estate, Homestead, etc.) → listing rejected with message: "SLPA currently supports Mainland parcels only. Private estate support is coming in a future update."
- **Required for group-owned land** (only way to verify authority)
- Optional but recommended for individual land (strongest verification)
- Badge: "Bot Verified" with green checkmark
- **Requires bot access**: parcel must be publicly accessible OR seller adds SLPA bot to access list
- If bot cannot access after 48 hours → verification fails (no fallback for group land; individual land can retry with Method A/B)
- Ongoing monitoring: Bot checks every 30 minutes that land remains set for sale to bot

**🟡 Human-Verified** (paid upsell, L$500-1000) - Add-on to any tier
- SLPA staff member visits parcel in-viewer
- Confirms: everything bot does PLUS parcel tags accuracy, category correctness, description truthfulness, no covenant violations, build quality assessment, neighborhood context
- Staff takes screenshots for listing gallery
- Written verification report attached to listing
- Badge: "Human Verified" gold badge with checkmark (replaces Script/Bot badge)
- **Requires human access**: seller grants access to SLPA reviewer account OR temporarily opens parcel to public during review window
- Reviewer coordinates visit time via website messaging
- Available as add-on to either Script-Verified or Bot-Verified listings

**Access requirements for verification:**

| Parcel Access Type | Script-Verified (A/B) | Bot-Verified (C) | Human-Verified |
|---|---|---|---|
| Public access | ✅ | ✅ | ✅ |
| Access list (bot/human added) | ✅ | ✅ | ✅ |
| Group-only (bot/human in group) | ✅ | ✅ | ✅ |
| Fully restricted (no bot access) | ✅ | ❌ (fails) | ❌ |
| Estate-level ban on bot | ✅ | ❌ (fails) | ❌ (contact estate owner) |

**Ongoing monitoring by tier:**
- **Script-Verified:** World API polling every 30 min (ownership check only). If ownership changes → listing suspended.
- **Bot-Verified:** Bot teleports every 30 min to check `AuthBuyerID` still set to SLPA, sale price still L$999,999,999, ownership unchanged. If sale-to-bot removed → auction paused, 24hr to re-set. If ownership changes → listing suspended immediately.
- **Human-Verified:** Same monitoring as underlying tier (Script or Bot), plus manual re-check available on request.

---

### 4.6 Browsing & Searching

**Browse by:**
- Region / location (map integration)
- **Distance from a point** - user enters a region name or clicks the map, search finds parcels within N regions
- Parcel size range
- Price range (current bid)
- Maturity rating
- Land type (Mainland only in Phase 1 - Private Estate/Homestead listings rejected)
- Parcel tags (multi-select filter, e.g. "Waterfront + Sailable + Protected Land")
- Reserve status: All / Reserve met / Reserve not met / No reserve
- Auction status (active, ending soon)
- Realty group (when Phase 2 launches)
- Snipe protection (yes/no filter)
- Verification tier (Script / Bot / Human)
- Sort: newest, ending soonest, most bids, lowest price, largest area, **nearest**

**Listing cards in search results show:**
- Parcel snapshot thumbnail
- Parcel name + region
- Current bid + bid count
- Time remaining
- Parcel size (sqm)
- Maturity rating badge
- 🛡️ **Snipe protection badge** (if enabled, with window duration: "🛡️ 15min")
- **Verification tier badge** (🔵 Script / 🟢 Bot / 🟡 Human)
- Realty group badge (if applicable, Phase 2)
- Distance from search point (if distance search active)
- Reserve price indicator: "Reserve not met" (orange) / "Reserve met ✓" (green) / no badge if no reserve
- Parcel tags (compact pill badges)

**Listing page shows:**
- All parcel metadata + seller description
- Live current bid + bid count
- Countdown timer
- Bid history (anonymized or public - configurable)
- **Parcel layout map** - generated grid image showing parcel boundaries within the region (see Section 5.5)
- **"Visit in Second Life" button** - links to SLURL (opens SL viewer and teleports user to parcel)
    - Web link format: `https://maps.secondlife.com/secondlife/Region%20Name/x/y/z`
    - Viewer protocol: `secondlife:///Region Name/x/y/z` (for users with viewer installed)
    - Button shows both options: "Open in Viewer" (viewer protocol) + "View on Map" (maps.secondlife.com)
- Map embed showing parcel location on the SL world map
- Seller profile (verification badge, past sales rating)
- **If group listing:** "Listed by [Agent Name] of [Group Name]" with both profile pics + group logo
- Link to listing agent's profile and group profile page

---

### 4.7 Bidding

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
6. **Snipe protection (if enabled by seller):** If a bid is placed within the seller-configured extension window (5-60 min) before auction end, the auction extends by that window. Extensions can stack - each qualifying bid resets the timer. This repeats until no new bids arrive within the window.
7. Previous high bidder notified (email + optional SL IM via terminal)

**Bid increments** (suggested):
| Current Price | Minimum Increment |
|--------------|-------------------|
| L$0 - L$999 | L$50 |
| L$1,000 - L$9,999 | L$100 |
| L$10,000 - L$99,999 | L$500 |
| L$100,000+ | L$1,000 |

**Proxy bidding (automatic):**
User sets a maximum bid amount. System automatically bids the minimum increment above any competing bid, up to the user's max. Standard eBay-style proxy bidding.

- Proxy bid stored separately from visible bids (only the current winning amount is shown)
- If two proxy bidders compete, system resolves instantly to the lower max + one increment (higher max wins)
- If outbid beyond their max, user is notified immediately
- User can increase their max at any time (but never decrease below current winning bid)
- Proxy bids trigger snipe protection the same as manual bids
- UI clearly shows "You have a proxy bid set at L$X" on the listing page

---

### 4.8 Auction End & Escrow

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
│ STEP 1: WINNER PAYS ESCROW            │
│                                        │
│ Winner goes to SLPA Escrow Terminal    │
│ in-world and pays the winning amount.  │
│                                        │
│ Terminal: money() event receives L$    │
│ Terminal: llHTTPRequest to backend     │
│   - Payer UUID (llDetectedKey)         │
│   - Amount received                   │
│   - X-SecondLife-Owner-Key header      │
│ Backend: Confirms amount matches       │
│ Backend: Marks escrow as FUNDED        │
│                                        │
│ L$ held by SLPA SL account            │
│ (terminal owner's account balance)     │
└───────────────┬────────────────────────┘
                │
                ▼
┌────────────────────────────────────────┐
│ STEP 2: SELLER TRANSFERS LAND         │
│                                        │
│ Seller goes to parcel in SL viewer:    │
│ About Land → "Sell Land"              │
│ Set "Sell to:" → winner's avatar name │
│ Set price: L$0                        │
│                                        │
│ (Manual step - SL has NO API for this) │
│                                        │
│ NOTE: For Bot-Verified listings, the   │
│ seller must FIRST change the sale-to-  │
│ bot setting. The parcel was set for    │
│ sale to SLPA bot during auction. Now:  │
│   1. Remove the sale-to-bot setting    │
│   2. Set land for sale to winner at $0 │
│ The bot detects the AuthBuyerID change │
│ as part of its normal monitoring.      │
└───────────────┬────────────────────────┘
                │
                ▼
┌────────────────────────────────────────┐
│ STEP 3: BACKEND MONITORS OWNERSHIP    │
│                                        │
│ Starts IMMEDIATELY after escrow funded │
│                                        │
│ TWO monitoring channels (parallel):    │
│                                        │
│ A) World API polling every 5 min:      │
│    world.secondlife.com/place/{uuid}  │
│    Checks ownerid on each poll.        │
│                                        │
│ B) Bot verification (Bot-Verified      │
│    listings only) every 15 min:        │
│    Teleports to region, reads          │
│    ParcelProperties for ownership AND  │
│    sale status (AuthBuyerID, SalePrice) │
│    Can detect if seller set land for   │
│    sale to winner (AuthBuyerID ==      │
│    winner UUID, SalePrice == 0)        │
│                                        │
│ CASE A: ownerid == winner's UUID       │
│   → Transfer CONFIRMED automatically  │
│   → Proceed to payout (Step 4)        │
│   → No manual confirmation needed     │
│                                        │
│ CASE B: ownerid == seller's UUID       │
│   → Still waiting, continue polling   │
│   (Bot can also check if sale-to-     │
│    winner is set up correctly, and    │
│    notify seller if not)              │
│                                        │
│ CASE C: ownerid == UNKNOWN third party │
│   → ⚠️ FRAUD FLAG                     │
│   → Escrow frozen immediately         │
│   → Seller account flagged for review │
│   → Admin notification triggered      │
│   → May result in account suspension  │
│     or termination after manual review │
│                                        │
│ Polling continues for up to 72 hours.  │
│ After timeout → auto-cancel + refund.  │
└───────────────┬────────────────────────┘
                │
                ▼
┌────────────────────────────────────────┐
│ STEP 4: PAYOUT                        │
│                                        │
│ Backend instructs escrow terminal:     │
│ HTTP-in POST to terminal's URL        │
│ Terminal: llTransferLindenDollars()    │
│   → Pays seller (minus commission)    │
│ Terminal: transaction_result event     │
│   → Confirms success/failure          │
│ Terminal: llHTTPRequest to backend     │
│   → Reports transaction result        │
│                                        │
│ Backend: Marks auction COMPLETED      │
│ Both parties notified.                │
└────────────────────────────────────────┘
```

**Timeout & dispute handling:**
- Winner has 48 hours to pay escrow after auction ends
- Seller has 72 hours to transfer land after escrow is funded (monitored automatically)
- If timeout expires → automatic cancellation, L$ refunded to buyer
- If ownership transfers to unexpected third party → escrow frozen, seller flagged
- Dispute button available for both parties → manual review queue

**Sale status detection:**
LSL has NO functions for checking sale status (`llGetParcelFlags` and `llGetParcelDetails` don't expose it, World API doesn't either). However, the **LibreMetaverse bot CAN** read `ParcelProperties` via the viewer protocol, which includes `SalePrice` and `AuthBuyerID`. This means:
- For **Bot-Verified listings**: Bot can confirm seller properly set land for sale to the winner during escrow. If seller hasn't set it up after 24 hours, bot sends a reminder notification.
- For **Script-Verified listings**: No sale status detection available. Transfer is monitored purely by ownership change via World API.

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
    - Gathers: avatar UUID, legacy name, display name, username (immediate)
    - Requests: DATA_BORN, DATA_PAYINFO (async via dataserver)
    - POST to backend: {code, avatar_uuid, avatar_name, display_name, username, born_date, payinfo}
    - SL auto-injects X-SecondLife-Owner-Key, X-SecondLife-Shard, X-SecondLife-Region headers
    - Backend responds: success/failure
    - Display result to user via llDialog() or llInstantMessage()
```

**Distribution:** Place at high-traffic locations (welcome areas, shopping malls, info hubs). Free copies available from the terminal itself or SL Marketplace.

### 5.2 SLPA Escrow Terminal

**Purpose:** Receive L$ payments from auction winners, pay out to sellers.

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

**Purpose:** Verify parcel ownership for listing creation (Methods A/B).

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

### 5.4 SLPA Verification Bot Pool (LibreMetaverse)

**Purpose:** Sale-to-Bot verification (Method C), ongoing auction monitoring, and escrow transfer verification.

**Architecture: Primary + Worker split**

- **Primary escrow account** (e.g., "SLPAEscrow Resident"): A dedicated SL account that NEVER logs in. It exists solely as the `AuthBuyerID` target that sellers set their land for sale to. All sellers on the platform use this one account name. It holds zero L$, runs no scripts, and has no viewer activity. If this account gets compromised, no damage is possible - it can't buy anything.
- **Worker bots**: Headless bots using LibreMetaverse (C#/.NET). No rendering, no textures, no 3D - just the viewer protocol layer. Each worker is a free SL account. Workers do all the actual teleporting and `ParcelProperties` reading. They're interchangeable and disposable.

**What the bot reads via `ParcelProperties` (viewer protocol):**
- `SalePrice` - current sale price (0 if not for sale)
- `AuthBuyerID` - who the land is set for sale to (UUID, or NULL_KEY for anyone)
- `OwnerID` - current owner (avatar or group UUID)
- `GroupID` - group associated with parcel
- `Name`, `Desc`, `Area`, `Category`, `SnapshotID`
- `RegionDenyAnonymous`, `RegionPushOverride`, etc. (parcel flags)

**Bot operations:**

1. **Sale-to-Bot Verification (Method C):**
    - Backend assigns a bot from pool
    - Bot teleports to the parcel's region
    - Bot sends `ParcelPropertiesRequest` for the parcel coordinates
    - Checks: `AuthBuyerID` == primary escrow account UUID (SLPAEscrow Resident)
    - Checks: `SalePrice` == L$999,999,999
    - Checks: `OwnerID` matches seller's UUID (individual) or is a group UUID (group land)
    - Reports result to backend

2. **Ongoing Auction Monitoring (Bot-Verified listings):**
    - Every 30 minutes during active auction
    - Worker teleports and checks sale-to-bot is still set
    - If `AuthBuyerID` no longer points to primary escrow account → auction paused, seller notified
    - If ownership changed → auction cancelled immediately

3. **Escrow Transfer Monitoring (Bot-Verified listings):**
    - Every 15 minutes during escrow period
    - Checks if seller set land for sale to winner (`AuthBuyerID` == winner UUID)
    - Checks if ownership transferred to winner
    - Can proactively notify seller if setup looks incorrect

**Performance & scaling:**

| Parameter | Value |
|-----------|-------|
| Teleport rate limit | 6 per minute per bot (SL hard cap) |
| Time per teleport + data read | ~7-12 seconds |
| Checks per bot per 30-min cycle | ~180 regions |
| Parcel clustering benefit | Multiple parcels in same region = 1 teleport |
| Workers needed for 500 listings | ~3-4 workers |
| Workers needed for 2,000 listings | ~8-12 workers |
| Workers needed for 5,000 listings | ~15-25 workers |
| Worker account cost | Free (standard SL accounts) |
| Primary escrow account | 1 (permanent, never logs in) |

**Bot pool management:**
- Backend maintains registry of all worker accounts with their UUIDs
- Primary escrow account UUID stored in config (never changes)
- Work queue distributes verification and monitoring tasks across available workers
- If a worker is offline (SL maintenance, account issue) → tasks redistributed to other workers
- Workers authenticate to backend via shared secrets
- Region assignments optimized to minimize teleports (group nearby parcels)

**Access requirements:**
- Worker bot must be able to teleport into the region containing the parcel
- If parcel has access restrictions, seller must add the assigned worker to the access list
- ~40% of SL parcels have some form of access restriction
- Estate-level bans override parcel-level access lists
- If worker is denied access → verification fails, seller notified with instructions

**Safety constraints:**
- Primary escrow account: never logs in, holds zero L$, exists only as a UUID target
- Worker bots: NO buy logic - cannot purchase land under any circumstance
- Workers carry minimal L$ balance (just enough for teleport costs if any)
- Workers do not interact with objects, avatars, or chat
- Workers do not store textures, render meshes, or download assets
- All accounts clearly labeled as service accounts in their SL profiles

### 5.5 Parcel Layout Map Generator

⚠️ **This feature needs further design work before implementation.** The concept is proven (Heath has built this before) but the exact pipeline needs fleshing out.

**What it does:** Generates a grid image of the region (256x256 meters) showing parcel boundaries. Green pixels = part of the listed parcel, red pixels = not part of the parcel. Parcels do not have to be contiguous - the map shows exactly which land is included.

**Display:**
- Shown on the listing detail page alongside the parcel snapshot
- Interactive: hovering over the image shows a tooltip indicating whether that point is part of the parcel or not
- Parcel area clearly visualized relative to the full region

**Generation approaches (to be determined):**

1. **Script verification (LSL):** During script verification, the rezzable object scans the region using `llGetParcelDetails()` at grid points to determine which 4x4m cells belong to the parcel. Results sent to backend via `llHTTPRequest`. Backend renders the grid image server-side.

2. **Bot verification (LibreMetaverse):** On the bot's first visit to the parcel, it reads parcel boundary data from the viewer protocol (`ParcelProperties` includes local ID; `ParcelOverlay` message contains the full region parcel map). Bot sends raw data to backend for image rendering.

3. **Wearable scanner object:** Bot wears a scripted attachment that performs the scan. Could combine LSL scanning with bot mobility. Needs investigation on whether worn scripts can read parcel data for parcels the wearer is standing on vs. adjacent parcels.

4. **Seller-run scanner (fallback/marketplace):** For parcels where the bot can't access, provide a script on SL Marketplace or via vendor that the seller can rez on their parcel. Script performs the scan and sends data to SLPA backend with the listing ID. Self-destructs after scan.

**Fallback behavior:**
- If bot cannot access the parcel/region → layout map is **not generated**
- Listing shows a placeholder with message: "Parcel layout map unavailable - SLPA bot does not have access to this region"
- Suggest seller grant bot access or use the self-service scanner script
- Listings without layout maps are still fully functional, just missing this visual

**Image storage:**
- Generated images stored alongside parcel snapshots
- Re-generated if parcel is subdivided/joined (detected via area change on bot check)
- Cached - only regenerated on verification or manual refresh

**TODO:**
- Determine which `ParcelOverlay` / `ParcelProperties` data from LibreMetaverse provides boundary info
- Decide on server-side image rendering approach (Canvas API, sharp/jimp, etc.)
- Design the interactive tooltip overlay (HTML canvas? SVG?)
- Spec the LSL scanning grid resolution (4x4m cells = 64x64 grid = 4096 checks - may need chunking)
- Evaluate wearable attachment approach for bot scanning
- Design the self-service scanner script for marketplace distribution
- Determine if `ParcelOverlay` bitmap data is sufficient or if per-cell `llGetParcelDetails` is needed

---

## 6. External API Integration

### 6.1 Avatar Data (In-World LSL - No External API Needed)

All avatar data is collected directly by the verification terminal script at verification time:

| Function | Data | Timing |
|----------|------|--------|
| `llDetectedKey(0)` | Avatar UUID | Immediate (touch event) |
| `llDetectedName(0)` | Legacy name ("First Last") | Immediate (touch event) |
| `llGetDisplayName(uuid)` | Display name (custom) | Immediate |
| `llGetUsername(uuid)` | Username ("first.last") | Immediate |
| `llRequestAgentData(uuid, DATA_BORN)` | Account creation date (YYYY-MM-DD) | Async (dataserver event) |
| `llRequestAgentData(uuid, DATA_PAYINFO)` | Payment info status | Async (dataserver event) |

**SL-injected HTTP headers** (added automatically to `llHTTPRequest`, tamper-proof):
- `X-SecondLife-Owner-Key` - script owner UUID
- `X-SecondLife-Object-Key` - object UUID
- `X-SecondLife-Region` - region name + grid coordinates
- `X-SecondLife-Shard` - "Production" (confirms main grid, not beta)

**Profile pictures:** User-uploaded on the website. Not pulled from SL (the World API resident endpoint is unreliable and only returns a texture asset UUID, not a usable image).

**Note:** `world.secondlife.com/resident/{uuid}` exists but is unofficial, undocumented, and unreliable. We intentionally do not depend on it. All user data comes from in-world LSL functions at verification time.

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

### 6.3 Map API (Official, Linden Lab)

Two official endpoints for region coordinate lookups:

**Region name → Grid coordinates:**
```
GET https://cap.secondlife.com/cap/0/d661249b-2b5a-4436-966a-3d3b8d7a574f?var=callback&sim_name={region_name}
```
Returns: `var callback = {'x' : 997, 'y' : 1002 };`

**Grid coordinates → Region name:**
```
GET https://cap.secondlife.com/cap/0/b713fe80-283b-4585-af4d-a3b7d9a32492?var=callback&grid_x={x}&grid_y={y}
```
Returns: `var callback='Ahern';`

**Map tile images:**
```
https://secondlife-maps-cdn.akamaized.net/map-{zoom}-{x}-{y}-objects.jpg
```
Zoom levels 1-8 (1 = most zoomed in, 1 region per tile).

**Usage:**
1. When a parcel is listed, resolve its region name to grid coordinates (x, y)
2. Store grid coordinates in the parcels table
3. Use coordinates for distance-based search and map visualization
4. Display map tiles on listing pages and browse view

**Grid coordinate system:**
- Each region is a 256m × 256m square
- Grid coordinates are integer region positions (e.g., Ahern = 997, 1002)
- Global position in meters = grid coordinate × 256
- Distance between two regions = Euclidean distance on the grid × 256 meters
- Example: distance between (997, 1002) and (1000, 1005) = √((3² + 3²)) × 256 = ~1,085 meters

**Distance search implementation:**
```sql
-- Find parcels within N regions of a given point
SELECT p.*, 
    SQRT(POWER(p.grid_x - :search_x, 2) + POWER(p.grid_y - :search_y, 2)) AS distance_regions
FROM parcels p
WHERE p.grid_x BETWEEN :search_x - :radius AND :search_x + :radius
  AND p.grid_y BETWEEN :search_y - :radius AND :search_y + :radius
ORDER BY distance_regions ASC;
```

With a spatial index on (grid_x, grid_y), this is very fast.

### 6.4 Grid Survey API (Supplementary)

**Endpoint:** `https://api.gridsurvey.com/simquery.php?region={name}`

**Returns (plain text key-value):**
```
status online
x 997
y 1002
access general
estate Mainland
firstseen 2008-03-09
lastseen 2026-04-05
region_uuid 28993779-de2a-fb74-a5f9-438873fec0ba
name Ahern
```

**Also supports coordinate lookup:** `https://api.gridsurvey.com/simquery.php?xy={x},{y}`

**Usage:** Validate that a region exists and is online before listing. Estate type (Mainland vs Private). Supplementary data. Note: Grid Survey is third-party, may lag behind actual grid state.

---

## 7. Database Schema (Key Entities)

### users
```sql
CREATE TABLE users (
    id              BIGSERIAL PRIMARY KEY,
    email           VARCHAR(255) UNIQUE NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    sl_avatar_uuid  UUID UNIQUE,
    sl_avatar_name  VARCHAR(255),           -- legacy name from llDetectedName()
    sl_display_name VARCHAR(255),           -- from llGetDisplayName()
    sl_username     VARCHAR(255),           -- from llGetUsername() (e.g., "first.last")
    sl_born_date    DATE,                   -- from llRequestAgentData(DATA_BORN)
    sl_payinfo      INTEGER,                -- from llRequestAgentData(DATA_PAYINFO)
    display_name    VARCHAR(255),           -- custom display name override on SLPA
    bio             TEXT,                    -- user bio/description
    profile_pic_url VARCHAR(512),           -- user-uploaded profile picture
    verified        BOOLEAN DEFAULT FALSE,
    verified_at     TIMESTAMP,
    -- Reputation fields (denormalized for fast display)
    avg_seller_rating DECIMAL(3,2),          -- avg rating as seller (1.00-5.00)
    avg_buyer_rating  DECIMAL(3,2),          -- avg rating as buyer
    total_seller_reviews INTEGER DEFAULT 0,
    total_buyer_reviews  INTEGER DEFAULT 0,
    completed_sales   INTEGER DEFAULT 0,
    cancelled_with_bids INTEGER DEFAULT 0,   -- cancellation counter (admin-visible only)
    listing_suspension_until TIMESTAMP,      -- NULL if not suspended
    -- Notification preferences
    email              VARCHAR(255),           -- for notifications + account recovery
    email_verified     BOOLEAN DEFAULT FALSE,
    notify_email       JSONB DEFAULT '{"bidding":true,"auction_result":true,"escrow":true,"listing_status":true,"reviews":true,"realty_group":true,"marketing":false}',
    notify_sl_im       JSONB DEFAULT '{"bidding":true,"auction_result":true,"escrow":true,"listing_status":true,"reviews":false,"realty_group":false,"marketing":false}',
    notify_email_muted BOOLEAN DEFAULT FALSE,  -- global email mute
    notify_sl_im_muted BOOLEAN DEFAULT FALSE,  -- global SL IM mute
    sl_im_quiet_start  TIME,                   -- quiet hours start (SLT)
    sl_im_quiet_end    TIME,                   -- quiet hours end (SLT)
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);
```

### realty_groups
```sql
CREATE TABLE realty_groups (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(255) UNIQUE NOT NULL,
    slug            VARCHAR(255) UNIQUE NOT NULL,   -- URL-friendly name
    leader_id       BIGINT NOT NULL REFERENCES users(id),
    logo_url        VARCHAR(512),
    description     TEXT,
    website         VARCHAR(512),
    sl_group_link   VARCHAR(512),           -- optional SL group URL
    agent_fee_rate  DECIMAL(5,4) DEFAULT 0, -- additional group fee (e.g., 0.0200 = 2%)
    agent_fee_split DECIMAL(5,4) DEFAULT 0.5000, -- % of agent fee that goes to agent (rest to group)
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);
```

### realty_group_members
```sql
CREATE TABLE realty_group_members (
    id              BIGSERIAL PRIMARY KEY,
    group_id        BIGINT NOT NULL REFERENCES realty_groups(id),
    user_id         BIGINT NOT NULL REFERENCES users(id),
    role            VARCHAR(20) NOT NULL DEFAULT 'AGENT',  -- LEADER, AGENT
    joined_at       TIMESTAMP DEFAULT NOW(),
    UNIQUE(user_id)   -- user can only belong to one group
);
CREATE INDEX idx_rgm_group ON realty_group_members(group_id);
```

### realty_group_invitations
```sql
CREATE TABLE realty_group_invitations (
    id              BIGSERIAL PRIMARY KEY,
    group_id        BIGINT NOT NULL REFERENCES realty_groups(id),
    invited_user_id BIGINT NOT NULL REFERENCES users(id),
    invited_by_id   BIGINT NOT NULL REFERENCES users(id),
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING, ACCEPTED, DECLINED, REVOKED
    expires_at      TIMESTAMP NOT NULL,
    created_at      TIMESTAMP DEFAULT NOW(),
    responded_at    TIMESTAMP
);
```

### realty_group_sl_groups
```sql
CREATE TABLE realty_group_sl_groups (
    id              BIGSERIAL PRIMARY KEY,
    realty_group_id BIGINT NOT NULL REFERENCES realty_groups(id),
    sl_group_uuid   UUID NOT NULL,
    sl_group_name   VARCHAR(255),
    verified        BOOLEAN DEFAULT FALSE,
    verified_at     TIMESTAMP,
    created_at      TIMESTAMP DEFAULT NOW(),
    UNIQUE(sl_group_uuid)
);
```

### parcels
```sql
CREATE TABLE parcels (
    id              BIGSERIAL PRIMARY KEY,
    parcel_uuid     UUID UNIQUE NOT NULL,
    owner_id        BIGINT REFERENCES users(id),    -- SLPA user who listed it
    sl_owner_uuid   UUID,                           -- SL avatar UUID (individual) or group UUID
    owner_type      VARCHAR(10) DEFAULT 'AGENT',    -- AGENT or GROUP
    sl_group_uuid   UUID,                           -- group UUID if group-owned (NULL for individual)
    parcel_name     VARCHAR(255),
    region_name     VARCHAR(255),
    grid_x          INTEGER,                -- region grid X coordinate (from Map API)
    grid_y          INTEGER,                -- region grid Y coordinate (from Map API)
    area_sqm        INTEGER,
    prim_capacity   INTEGER,
    maturity        VARCHAR(20),
    estate_type     VARCHAR(50),            -- Mainland, Private Estate, etc. (from Grid Survey)
    description     TEXT,
    snapshot_url     VARCHAR(512),
    layout_map_url   VARCHAR(512),          -- generated parcel boundary grid image (see 5.5)
    layout_map_data  JSONB,                 -- raw grid data for interactive tooltip overlay
    layout_map_at    TIMESTAMP,             -- when layout map was last generated
    location        VARCHAR(100),           -- local coordinates within region (e.g. "128,64,25")
    slurl           VARCHAR(512),           -- generated: secondlife:///Region Name/x/y/z
    verified        BOOLEAN DEFAULT FALSE,
    verified_at     TIMESTAMP,
    last_checked    TIMESTAMP,
    created_at      TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_parcels_grid ON parcels(grid_x, grid_y);
```

### parcel_tags (enum)
```sql
CREATE TYPE parcel_tag AS ENUM (
    -- Terrain / Environment
    'WATERFRONT',       -- ocean, lake, or river border
    'SAILABLE',         -- connected to navigable water (Linden waterways)
    'GRASS',            -- grass terrain
    'SNOW',             -- snow terrain
    'SAND',             -- sand/beach terrain
    'MOUNTAIN',         -- elevated/hilly terrain
    'FOREST',           -- wooded area
    'FLAT',             -- level terrain, good for building

    -- Roads / Access
    'STREETFRONT',      -- borders a Linden road
    'ROADSIDE',         -- near (but not directly on) a Linden road
    'RAILWAY',          -- near Linden railroad / SLRR

    -- Location Features
    'CORNER_LOT',       -- parcel on a corner (two road/water sides)
    'HILLTOP',          -- elevated with views
    'ISLAND',           -- surrounded by water
    'PENINSULA',        -- water on 3 sides
    'SHELTERED',        -- enclosed/private feeling, surrounded by terrain

    -- Neighbors / Context
    'RESIDENTIAL',      -- residential neighborhood
    'COMMERCIAL',       -- commercial/shopping area
    'INFOHUB_ADJACENT', -- near a Linden infohub
    'PROTECTED_LAND',   -- adjacent to Linden-owned protected land (parks, waterways)
    'SCENIC',           -- notable views or landscape

    -- Parcel Features
    'DOUBLE_PRIM',      -- on a double-prim region (Magnum/etc.)
    'HIGH_PRIM',        -- above-average prim capacity for size
    'LARGE_PARCEL',     -- 4096+ sqm
    'FULL_REGION'       -- entire region (65536 sqm)
);

CREATE TABLE auction_tags (
    auction_id  BIGINT REFERENCES auctions(id) ON DELETE CASCADE,
    tag         parcel_tag NOT NULL,
    PRIMARY KEY (auction_id, tag)
);
CREATE INDEX idx_auction_tags_tag ON auction_tags(tag);
```

### auctions
```sql
CREATE TABLE auctions (
    id              BIGSERIAL PRIMARY KEY,
    parcel_id       BIGINT REFERENCES parcels(id),
    seller_id       BIGINT REFERENCES users(id),      -- parcel owner
    listing_agent_id BIGINT REFERENCES users(id),      -- agent who created listing (may differ from seller)
    realty_group_id BIGINT REFERENCES realty_groups(id), -- NULL if individual listing
    status          VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
        -- DRAFT, DRAFT_PAID, VERIFICATION_PENDING, VERIFICATION_FAILED,
        -- ACTIVE, ENDED, ESCROW_PENDING, ESCROW_FUNDED,
        -- TRANSFER_PENDING, COMPLETED, CANCELLED, EXPIRED, DISPUTED
    verification_tier VARCHAR(10),           -- SCRIPT, BOT, HUMAN (set after verification)
    verification_method VARCHAR(20),        -- UUID_ENTRY, REZZABLE, SALE_TO_BOT
    assigned_bot_uuid UUID,                 -- which SLPA bot is assigned for Method C monitoring
    sale_sentinel_price INTEGER,            -- expected sale price (L$999,999,999) for Method C
    last_bot_check_at TIMESTAMP,            -- last successful bot monitoring check
    bot_check_failures INTEGER DEFAULT 0,   -- consecutive failed bot checks
    listing_fee_paid BOOLEAN DEFAULT FALSE,
    listing_fee_amt  INTEGER,               -- L$ listing fee paid
    listing_fee_txn  VARCHAR(255),          -- transaction reference from payment terminal
    listing_fee_paid_at TIMESTAMP,
    verified_at     TIMESTAMP,              -- when verification completed
    verification_notes TEXT,                -- bot/human verification details
    starting_bid    INTEGER NOT NULL,       -- L$
    reserve_price   INTEGER,                -- L$ (optional)
    buy_now_price   INTEGER,                -- L$ (optional)
    current_bid     INTEGER DEFAULT 0,      -- L$
    bid_count       INTEGER DEFAULT 0,
    winner_id       BIGINT REFERENCES users(id),
    duration_hours  INTEGER NOT NULL,
    snipe_protect   BOOLEAN DEFAULT FALSE,  -- seller opt-in
    snipe_window_min INTEGER,               -- extension window in minutes (5,10,15,30,60)
    starts_at       TIMESTAMP,
    ends_at         TIMESTAMP,
    original_ends_at TIMESTAMP,             -- original end time before any snipe extensions
    seller_desc     TEXT,
    commission_rate DECIMAL(5,4) DEFAULT 0.0500,  -- 5% platform
    commission_amt  INTEGER,                -- L$ platform commission (calculated at completion)
    agent_fee_rate  DECIMAL(5,4) DEFAULT 0, -- group agent fee (copied from group at listing time)
    agent_fee_amt   INTEGER,                -- L$ agent fee (calculated at completion)
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
    amount          INTEGER NOT NULL,       -- L$
    placed_at       TIMESTAMP DEFAULT NOW(),
    ip_address      INET
);
CREATE INDEX idx_bids_auction ON bids(auction_id, amount DESC);
```

### proxy_bids
```sql
CREATE TABLE proxy_bids (
    id              BIGSERIAL PRIMARY KEY,
    auction_id      BIGINT NOT NULL REFERENCES auctions(id),
    bidder_id       BIGINT NOT NULL REFERENCES users(id),
    max_amount      INTEGER NOT NULL,       -- L$ maximum the bidder is willing to pay
    active          BOOLEAN DEFAULT TRUE,   -- FALSE if outbid beyond max or auction ended
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW(),
    UNIQUE(auction_id, bidder_id)            -- one proxy bid per user per auction
);
```

### bot_accounts
```sql
CREATE TABLE bot_accounts (
    id              BIGSERIAL PRIMARY KEY,
    sl_uuid         UUID UNIQUE NOT NULL,
    sl_username     VARCHAR(255) NOT NULL,
    role            VARCHAR(10) NOT NULL DEFAULT 'WORKER',  -- PRIMARY (escrow target) or WORKER (monitoring)
    status          VARCHAR(20) NOT NULL DEFAULT 'ONLINE',  -- ONLINE, OFFLINE, MAINTENANCE (workers only)
    current_region  VARCHAR(255),           -- region the worker is currently in
    last_heartbeat  TIMESTAMP,
    active_tasks    INTEGER DEFAULT 0,      -- current task count (workers only)
    created_at      TIMESTAMP DEFAULT NOW()
);
-- Only one PRIMARY account allowed
CREATE UNIQUE INDEX idx_bot_primary ON bot_accounts(role) WHERE role = 'PRIMARY';
```

### bot_tasks
```sql
CREATE TABLE bot_tasks (
    id              BIGSERIAL PRIMARY KEY,
    bot_id          BIGINT REFERENCES bot_accounts(id),
    auction_id      BIGINT REFERENCES auctions(id),
    task_type       VARCHAR(30) NOT NULL,   -- VERIFY, MONITOR_AUCTION, MONITOR_ESCROW
    region_name     VARCHAR(255) NOT NULL,
    parcel_uuid     UUID NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
        -- PENDING, IN_PROGRESS, COMPLETED, FAILED, CANCELLED
    result_data     JSONB,                  -- ParcelProperties data from bot
    scheduled_at    TIMESTAMP,
    started_at      TIMESTAMP,
    completed_at    TIMESTAMP,
    error_message   TEXT,
    created_at      TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_bot_tasks_bot ON bot_tasks(bot_id, status);
CREATE INDEX idx_bot_tasks_auction ON bot_tasks(auction_id);
CREATE INDEX idx_bot_tasks_scheduled ON bot_tasks(scheduled_at) WHERE status = 'PENDING';
```

### reviews
```sql
CREATE TABLE reviews (
    id              BIGSERIAL PRIMARY KEY,
    auction_id      BIGINT REFERENCES auctions(id) NOT NULL,
    reviewer_id     BIGINT REFERENCES users(id) NOT NULL,     -- who left the review
    reviewee_id     BIGINT REFERENCES users(id) NOT NULL,     -- who is being reviewed
    reviewer_role   VARCHAR(10) NOT NULL,                     -- BUYER or SELLER
    rating          SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5),
    review_text     VARCHAR(500),
    response_text   VARCHAR(500),                             -- reviewee's response
    response_at     TIMESTAMP,
    visible         BOOLEAN DEFAULT FALSE,                    -- becomes true when both submit or timeout
    flagged         BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMP DEFAULT NOW(),
    UNIQUE(auction_id, reviewer_id)
);
CREATE INDEX idx_reviews_reviewee ON reviews(reviewee_id, visible);
CREATE INDEX idx_reviews_auction ON reviews(auction_id);
```

### cancellation_log
```sql
CREATE TABLE cancellation_log (
    id              BIGSERIAL PRIMARY KEY,
    auction_id      BIGINT REFERENCES auctions(id) NOT NULL,
    seller_id       BIGINT REFERENCES users(id) NOT NULL,
    had_bids        BOOLEAN NOT NULL,
    bid_count       INTEGER DEFAULT 0,
    reason          TEXT,
    penalty_applied VARCHAR(30),    -- WARNING, FEE, SUSPENSION, BAN
    created_at      TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_cancellation_seller ON cancellation_log(seller_id);
```

### listing_reports
```sql
CREATE TYPE report_reason AS ENUM (
    'INACCURATE_DESCRIPTION',
    'WRONG_TAGS',
    'SHILL_BIDDING',
    'FRAUDULENT_SELLER',
    'DUPLICATE_LISTING',
    'NOT_ACTUALLY_FOR_SALE',
    'TOS_VIOLATION',
    'OTHER'
);

CREATE TYPE report_status AS ENUM (
    'OPEN',
    'REVIEWED',
    'DISMISSED',
    'ACTION_TAKEN'
);

CREATE TABLE listing_reports (
    id              BIGSERIAL PRIMARY KEY,
    auction_id      BIGINT REFERENCES auctions(id) NOT NULL,
    reporter_id     BIGINT REFERENCES users(id) NOT NULL,
    subject         VARCHAR(100) NOT NULL,
    reason          report_reason NOT NULL,
    details         TEXT NOT NULL,           -- max 2000 chars enforced in app
    status          report_status DEFAULT 'OPEN',
    admin_notes     TEXT,                    -- internal admin notes
    reviewed_by     BIGINT REFERENCES users(id),
    reviewed_at     TIMESTAMP,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW(),
    UNIQUE(auction_id, reporter_id)         -- one report per user per listing
);
CREATE INDEX idx_reports_auction ON listing_reports(auction_id);
CREATE INDEX idx_reports_status ON listing_reports(status);
```

### bans
```sql
CREATE TABLE bans (
    id              BIGSERIAL PRIMARY KEY,
    ban_type        VARCHAR(10) NOT NULL,    -- IP, AVATAR, BOTH
    ip_address      INET,                    -- banned IP (NULL if avatar-only ban)
    sl_avatar_uuid  UUID,                    -- banned avatar (NULL if IP-only ban)
    reason          TEXT NOT NULL,
    banned_by       BIGINT REFERENCES users(id),  -- admin who issued ban
    expires_at      TIMESTAMP,               -- NULL = permanent
    created_at      TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_bans_ip ON bans(ip_address) WHERE ip_address IS NOT NULL;
CREATE INDEX idx_bans_avatar ON bans(sl_avatar_uuid) WHERE sl_avatar_uuid IS NOT NULL;
```

### fraud_flags
```sql
CREATE TABLE fraud_flags (
    id              BIGSERIAL PRIMARY KEY,
    flag_type       VARCHAR(30) NOT NULL,    -- SAME_IP_MULTI_ACCOUNT, NEW_ACCOUNT_LAST_SECOND, etc.
    auction_id      BIGINT REFERENCES auctions(id),
    user_id         BIGINT REFERENCES users(id),
    details         JSONB,                   -- IP address, timing, related accounts, etc.
    status          VARCHAR(20) DEFAULT 'OPEN',  -- OPEN, REVIEWED, DISMISSED, ACTION_TAKEN
    reviewed_by     BIGINT REFERENCES users(id),
    reviewed_at     TIMESTAMP,
    created_at      TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_fraud_flags_status ON fraud_flags(status);
```

### escrow_transactions
```sql
CREATE TABLE escrow_transactions (
    id              BIGSERIAL PRIMARY KEY,
    auction_id      BIGINT REFERENCES auctions(id),
    payer_id        BIGINT REFERENCES users(id),
    payee_id        BIGINT REFERENCES users(id),
    type            VARCHAR(20) NOT NULL,   -- PAYMENT, PAYOUT, REFUND, COMMISSION
    amount          INTEGER NOT NULL,       -- L$
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
- `PARCEL_DETAILS_OWNER` returns the group UUID, not an individual avatar
- **Verification: Sale-to-Bot (Method C) is REQUIRED** for group-owned land
    - Only someone with the `LandSetSale` group role ability can set group land for sale
    - Setting the land for sale to the SLPA bot proves authority without needing to check group membership or roles
    - This is the cleanest solution - the action IS the proof
- Group land sale flow during escrow differs slightly:
    - Seller (group officer) sets land for sale to winner at L$0
    - Winner buys directly, land transfers from group to winner's individual ownership
    - Group no longer owns the parcel after transfer
- LSL has NO functions to check group role permissions (only active group via `llSameGroup`)
- The viewer protocol CAN check group roles (`GroupMembersReply` includes `Powers` bitmask with `LandSetSale` flag) but requires bot to be a member of the group - overly complex compared to Sale-to-Bot
- **MVP decision:** Group-owned land IS supported at launch via Method C (Sale-to-Bot). No Phase 2 needed.

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
- **No bid rate limiting** - let people bid freely
- Suspicious pattern detection (flag only, no automation):
    - Same IP across multiple bidding accounts → flagged for admin review
    - Last-second bids from new accounts (< 3 completed auctions) → flagged
- Report button on listings (see Listing Reports section below)
- **Admin banning:** Can ban by IP address and/or SL avatar UUID. No automated bans - all manual.
- Banned IPs cannot register, log in, or place bids
- Banned SL avatars cannot re-verify with a new web account

### Escrow Terminal Security
- The SLPA SL account holds all escrowed L$
- `PERMISSION_DEBIT` is a powerful permission - if terminal script is compromised, account can be drained
- Mitigations:
    - Terminal only executes payouts authorized by backend via signed HTTP-in requests
    - Shared secret rotated periodically
    - Backend rate-limits payout requests
    - Daily balance reconciliation: sum of pending escrow should equal account balance
    - Alert on unexpected balance changes

### Anti-Circumvention

**Risk:** Seller lists land on SLPA for price discovery, gets bids, then cancels the auction and sells directly to the highest bidder off-platform to avoid commission.

**Mitigations:**

1. **Non-refundable listing fee** - Already documented. Listing fee is paid before verification. Not refunded on cancellation once auction goes ACTIVE. Makes cancel-and-sell-privately cost something.

2. **Cancellation penalties** - If a seller cancels an auction that has active bids:
    - First offense: warning + listing fee forfeited (already non-refundable)
    - Second offense: L$500 penalty deducted from any future payouts
    - Third offense: 30-day listing suspension
    - Persistent pattern: permanent ban
    - Cancellation reason required (free text) - logged for admin review
    - "Cancelled with bids" counter visible only to admins, not public

3. **Reputation system** - The primary anti-circumvention tool:
    - Sellers build reputation through completed auctions
    - Buyers prefer sellers with proven track records
    - A strong seller rating is worth more than saving one commission
    - Completion rate displayed on seller profile (completed / total listed)
    - New sellers have a "New Seller" badge until 3 completed auctions
    - See ratings/reviews system below

4. **Ownership change detection** - Bot-Verified listings only:
    - If ownership changes to anyone other than the auction winner during or shortly after cancellation → fraud flag
    - Seller's cancellation history cross-referenced with parcel ownership changes

**What we DON'T do:**
- Hide bidder identities from sellers - this would undermine the reputation system
- Hide seller identities from bidders - bidders need to see ratings, other listings, and seller history to make informed bids
- Restrict SL communication between users - unenforceable and hostile

**Philosophy:** Make the platform valuable enough that the commission is worth paying. Escrow protection, verified listings, buyer pool, dispute resolution, reputation. If someone wants a zero-protection handshake deal in SL, that's their risk.

### Ratings & Reviews

Both buyers and sellers can rate each other after a completed auction:

**Rating flow:**
1. Auction completes (escrow released, both parties satisfied)
2. Both parties receive a "Rate this transaction" prompt (website + SL IM notification)
3. Each party rates the other: 1-5 stars + optional text review (max 500 chars)
4. Ratings are blind until both submit (or 14-day window expires)
5. After both submit (or timeout), ratings become public simultaneously
6. If only one party rates, that rating goes public after 14 days

**What's rated:**
- **Seller rating:** Land as described? Timely transfer? Communication? Overall experience.
- **Buyer rating:** Timely payment? Smooth transaction? Communication? Overall experience.

**Display:**
- Seller profile: average rating, total reviews, completion rate, "Member since" date
- Buyer profile: average rating, total reviews, auctions won
- Individual listing cards: seller's rating summary
- Realty group page: aggregate rating across all agents

**Anti-abuse:**
- Cannot rate unless the auction completed through escrow (prevents rating bombing)
- One rating per auction per party (no edits after submission)
- Flagged reviews sent to admin queue
- Seller can respond to reviews (one response per review, public)

### Listing Reports

Users can report any ACTIVE listing they believe is suspicious, fraudulent, or inaccurate.

**Report form:**
- **Subject** (required): Short summary, free text (max 100 chars)
- **Reason category** (required, enum): `INACCURATE_DESCRIPTION`, `WRONG_TAGS`, `SHILL_BIDDING`, `FRAUDULENT_SELLER`, `DUPLICATE_LISTING`, `NOT_ACTUALLY_FOR_SALE`, `TOS_VIOLATION`, `OTHER`
- **Details** (required): Free text explanation (max 2000 chars)
- Reporter identity stored but not shown to seller

**Thresholds & escalation:**
- Each report is logged and visible in admin dashboard immediately
- **3 unique reports** on a single listing → admin email alert triggered
- **No automatic pausing** - reports never affect listing status. Only an admin can take action after manual review.
- Reports from the same user on the same listing are deduplicated (can update, not stack)
- Admins can: dismiss report, warn seller, suspend listing, cancel listing, ban seller

**Anti-abuse (reporting):**
- One report per user per listing (prevents report bombing)
- Must be a verified user to report
- Cannot report your own listing
- Frivolous reporting tracked - users who repeatedly file dismissed reports may lose reporting privileges

**Admin dashboard:**
- Queue of reported listings sorted by report count
- Each report shows: reporter, timestamp, subject, reason category, details
- Admin actions logged with reason
- Seller notification on action taken (not on individual reports)

### SL Terms of Service
- Must comply with SL's TOS regarding L$ transactions
- L$ is not real currency - it's a "license" per SL TOS
- Our service is essentially a marketplace facilitator, similar to SL Marketplace
- Review TOS sections on scripted L$ transactions, third-party services
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

## 10. REST API Endpoints (Key Routes)

### Authentication
- `POST /api/v1/auth/register` - Create account (email + password)
- `POST /api/v1/auth/login` - Login, returns JWT
- `POST /api/v1/auth/refresh` - Refresh JWT token

### User Profiles
- `GET /api/v1/users/{id}` - Public profile
- `PUT /api/v1/users/me` - Update own profile (display name, bio)
- `POST /api/v1/users/me/avatar` - Upload profile picture (multipart)
- `DELETE /api/v1/users/me/avatar` - Remove profile picture
- `GET /api/v1/users/{id}/reviews` - Public reviews for a user (paginated)
- `GET /api/v1/users/me/notifications` - Notification feed (paginated, WebSocket for real-time)
- `GET /api/v1/users/me/notification-preferences` - Current notification settings
- `PUT /api/v1/users/me/notification-preferences` - Update notification settings
- `POST /api/v1/users/me/email/verify` - Send email verification
- `POST /api/v1/users/me/email/confirm` - Confirm email with token

**Reviews:**
- `POST /api/v1/auctions/{id}/review` - Submit review for counterparty (after escrow complete)
- `POST /api/v1/reviews/{id}/respond` - Reviewee responds to a review (one response only)
- `POST /api/v1/reviews/{id}/flag` - Flag a review for admin review

### SL Verification (called by in-world scripts)
- `POST /api/v1/sl/verify` - Player verification (code + avatar data)
- `POST /api/v1/sl/parcel/verify` - Parcel verification from LSL object
- `POST /api/v1/sl/escrow/payment` - Payment received notification
- `POST /api/v1/sl/escrow/payout-result` - Payout transaction result
- `POST /api/v1/sl/terminal/register` - Terminal HTTP-in URL registration

### Realty Groups
- `POST /api/v1/realty-groups` - Create realty group (becomes leader)
- `GET /api/v1/realty-groups/{slug}` - Public realty group profile
- `PUT /api/v1/realty-groups/{id}` - Update realty group (leader only: name, description, logo, fees)
- `POST /api/v1/realty-groups/{id}/logo` - Upload realty group logo (multipart, leader only)
- `DELETE /api/v1/realty-groups/{id}` - Dissolve realty group (leader only, no active listings)
- `GET /api/v1/realty-groups/{id}/members` - List agents
- `POST /api/v1/realty-groups/{id}/invitations` - Invite agent (leader only)
- `DELETE /api/v1/realty-groups/{id}/invitations/{inviteId}` - Revoke invitation (leader only)
- `POST /api/v1/realty-groups/{id}/invitations/{inviteId}/accept` - Accept invitation
- `POST /api/v1/realty-groups/{id}/invitations/{inviteId}/decline` - Decline invitation
- `DELETE /api/v1/realty-groups/{id}/members/{userId}` - Remove agent (leader only)
- `POST /api/v1/realty-groups/{id}/transfer-leadership` - Transfer leader role
- `POST /api/v1/realty-groups/{id}/leave` - Agent leaves realty group
- `GET /api/v1/realty-groups/{id}/listings` - All realty group listings
- `GET /api/v1/realty-groups/{id}/transactions` - Realty group transaction history (leader only)

### Parcels
- `POST /api/v1/parcels/verify` - Verify parcel ownership (manual UUID entry)
- `GET /api/v1/parcels/{id}` - Parcel details

### Auctions
- `POST /api/v1/auctions` - Create listing (DRAFT status)
- `GET /api/v1/auctions` - Browse/search active listings (with filters, only shows ACTIVE+)
- `GET /api/v1/auctions/{id}` - Listing detail (public: ACTIVE+ only; owner: any status)
- `GET /api/v1/auctions/{id}/preview` - Preview listing as it will appear (owner/agent only, any status)
- `PUT /api/v1/auctions/{id}` - Edit listing (DRAFT or DRAFT_PAID only)
- `POST /api/v1/auctions/{id}/pay-listing-fee` - Record listing fee payment (terminal callback)
- `POST /api/v1/auctions/{id}/verify` - Start verification process (DRAFT_PAID → VERIFICATION_PENDING)
- `POST /api/v1/auctions/{id}/verify/callback` - Bot/script verification result callback
- `POST /api/v1/auctions/{id}/cancel` - Cancel listing (refund if DRAFT_PAID or earlier)
- `GET /api/v1/tags` - List all available parcel tags (returns enum values with display labels and categories)
- `POST /api/v1/auctions/{id}/report` - Report a listing (subject, reason, details)
- `GET /api/v1/admin/reports` - Admin: list reports (filterable by status, sorted by count)
- `PUT /api/v1/admin/reports/{id}` - Admin: review/dismiss/action a report
- `POST /api/v1/auctions/{id}/bid` - Place bid (manual, ACTIVE only)
- `POST /api/v1/auctions/{id}/proxy-bid` - Set/update proxy bid (max amount)
- `DELETE /api/v1/auctions/{id}/proxy-bid` - Cancel proxy bid
- `GET /api/v1/auctions/{id}/bids` - Bid history (proxy max amounts are hidden)

### Dashboard
- `GET /api/v1/me/auctions` - My listings (as seller or listing agent)
- `GET /api/v1/me/bids` - My bid history
- `GET /api/v1/me/invitations` - My pending realty group invitations
- `GET /api/v1/me/realty-group` - My realty group info

---

## 11. Notification System

**All notifications are user-configurable.** Users set their preferences during registration and can change them anytime in account settings.

**Defaults:** Email ON, SL IM ON, Website always ON (cannot be disabled - it's the in-app feed).

### Notification Preferences

Users configure per-category preferences for two optional channels:

| Category | Email (default) | SL IM (default) | Website |
|----------|:-:|:-:|:-:|
| Bidding (outbid, proxy exhausted) | ON | ON | Always |
| Auction won/lost | ON | ON | Always |
| Auction ended (seller) | ON | ON | Always |
| Escrow events (funded, confirmed, payout) | ON | ON | Always |
| Listing status (suspended, cancelled, verified) | ON | ON | Always |
| Reviews (received, response window closing) | ON | OFF | Always |
| Realty group (invitations, member changes) | ON | OFF | Always |
| Marketing (featured listings, tips) | OFF | OFF | Always |

**Registration flow:**
1. After player verification, user is prompted to set up notifications
2. Email address required (for account recovery too)
3. Notification preferences shown with defaults pre-checked
4. User can accept defaults or customize per-category
5. Can skip and configure later in settings

**User settings page:**
- Toggle email on/off per category
- Toggle SL IM on/off per category
- Global "mute all emails" and "mute all SL IMs" toggles (overrides per-category)
- Email address management (change, verify)
- Quiet hours for SL IMs (e.g., no IMs between 11 PM - 8 AM SLT)

**Implementation:**
- Website notifications always delivered (WebSocket push + persistent notification feed)
- Email via standard email service (SendGrid, SES, etc.)
- SL IMs sent via `llInstantMessage(avatar_uuid, message)` from terminal scripts
- Backend checks user preferences before dispatching each notification
- Unsubscribe link in every email (one-click, per-category)

---

## 12. Revenue & Economics

**Commission structure (proposed):**
- 5% of final sale price, charged to seller
- Deducted from escrow payout automatically
- Minimum commission: L$50
- No listing fee (encourages listings)
- No bidding fee

**Example:**
- Parcel sells for L$10,000
- Commission: L$500 (5%)
- Seller receives: L$9,500
- L$10,000 → US$39.22 at current rates (L$255 = US$1)
- Commission: ~US$1.96

**Operational costs:**
- SLPA SL account: Premium membership (~US$11.99/month) for increased L$ transaction limits
- Small land parcel to host terminals: ~US$7/month (1024 sqm Mainland)
- Web hosting: ~US$20-50/month (VPS)
- Domain: ~US$12/year
- Total: ~US$40-70/month base cost

---

## 13. MVP Scope (Phase 1)

**In scope:**
- Player verification (terminal method only)
- Parcel verification (all three methods: UUID entry, rezzable object, Sale-to-Bot)
- **Group-owned land support** (via Sale-to-Bot verification - Method C required)
- User profiles with custom profile picture upload
- Basic auction creation and management
- Real-time bidding with WebSocket updates
- Snipe protection (seller opt-in, configurable 5-60 minute window)
- Proxy bidding (automatic max-bid system)
- Escrow payment and payout via terminal
- World API ownership verification and transfer confirmation
- LibreMetaverse bot pool for verification and monitoring
- Seller/buyer ratings and reviews (blind until both submit, 14-day window)
- Anti-circumvention measures (cancellation penalties, completion rate tracking)
- Email notifications
- Basic user dashboard (my auctions, my bids)
- Mobile-responsive frontend

**Out of scope (Phase 2+):**
- **Private island/estate support** (different ownership model, estate owner permissions, transfer mechanics - see Open Questions)
- Realty Groups (creation, invitations, agent roles, group listings, group dashboard)
- Auction templates / relisting
- Advanced search (map-based browsing)
- SL Marketplace integration
- Multiple currency support
- API for third-party tools
- Mobile app
- Watchlist / saved searches
- Auction categories / featured listings

---

## 14. Open Questions

### Resolved

1. ~~**Commission rate?**~~ → **5% flat**, minimum L$50. Reevaluate later.
2. ~~**Minimum auction price?**~~ → **No floor.** L$50 minimum commission handles spam economics.
3. ~~**SLPA SL account?**~~ → **Yes, dedicated Premium SL account.** (SLPAEscrow Resident)
4. ~~**Legal/TOS review?**~~ → **Deferred.** Not worrying about this right now.
5. ~~**Terminal distribution?**~~ → **1 initial location.** Will allow partnerships (see Partners section below).
6. ~~**Dispute resolution?**~~ → **Manual.** SLPA staff arbitrates all disputes.
7. ~~**Anti-fraud?**~~ → **Flag only, no automation.** Same IP across multiple bidding accounts → flagged. Last-second bids from new accounts → flagged. No bid rate limiting. Admin can ban by IP and SL avatar. No automated bans.
8. ~~**World API reliability?**~~ → **No fallback plan yet.** Not a concern for now.
9. ~~**Domain?**~~ → **slparcelauctions.com** (primary, required by LL TOS to not use "SL" abbreviation misleadingly). **slparcels.com** as redirect.
10. ~~**Reserve price visibility?**~~ → **Show that a reserve exists** (not the amount). Display "Reserve not met" / "Reserve met" on listing. Filterable in browse/search.
11. ~~**Private regions?**~~ → **Mainland only.** Private region sales involve real USD ($100 transfer fee, $229+/month tier) - may never be in scope.

### Still Open

1. **Realty group limits?** Max agents per realty group? Max realty groups on platform? (Phase 2)
2. **Realty group verification requirements?** Extra verification beyond individual? (Phase 2)
3. **Profile picture storage:** S3/R2/local? CDN for serving?
4. **Realty group dissolution:** What happens to active listings when a realty group is dissolved? (Phase 2)

### Partners

SLPA will support partnerships with existing SL businesses for terminal hosting and cross-promotion.

**Partner page on website:**
- List of current partners with SL business name, SLURL to their location, description
- "Become a Partner" request form: business name, SL avatar, location, description of business, why they want to partner
- Requests go to admin queue for review

**What partnership means (to be refined):**
- Partner hosts an SLPA terminal at their location
- Partner gets a badge/listing on the partners page
- Potential commission share or referral credit for listings created at their terminal
- Co-marketing (partner listed on SLPA, SLPA sign at partner location)

---

## 15. File Structure (Proposed)

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
│   │       ├── bot/          # Bot pool management, task scheduling
│   │       ├── escrow/       # Escrow management
│   │       ├── realtygroup/   # Realty groups, invitations, agent management
│   │       ├── parcel/       # Parcel verification + monitoring
│   │       ├── notification/ # Email + SL IM notifications
│   │       ├── profile/      # User profiles, image uploads
│   │       ├── sl/           # SL API client (World API, Map API)
│   │       └── websocket/    # WebSocket config + handlers
│   └── pom.xml
├── bot/                      # LibreMetaverse bot service (C#/.NET)
│   ├── BotPool/              # Bot pool manager, work queue
│   ├── Tasks/                # Verification, monitoring task implementations
│   ├── Protocol/             # ParcelProperties parsing, teleport logic
│   └── Bot.sln
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