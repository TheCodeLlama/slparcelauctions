# Stitch Prompt: SLPA Design System & Core Pages

## Project Overview

Design a modern auction website called **SL Parcel Auctions (SLPA)** - a player-to-player land auction platform for the virtual world Second Life. Think of it like eBay but specifically for virtual land parcels. The site should feel trustworthy, professional, and fun to use without being overly complicated.

## Tech Stack

- Next.js with React (use App Router)
- Tailwind CSS for styling
- Dark mode and light mode with a toggle in the header (default to dark)

## Design Direction

- **Clean and modern** - generous whitespace, clear typography hierarchy
- **Dark mode first** - deep slate/charcoal backgrounds, not pure black. Light mode should feel equally polished, not an afterthought
- **Accent color** - use a warm amber/gold (#F59E0B or similar) as the primary accent. This fits the "auction house" feel without being generic blue
- **Cards-based layout** - auction listings displayed as cards in a responsive grid
- **Subtle depth** - light borders, soft shadows, slight glassmorphism on key elements. Nothing heavy
- **Typography** - clean sans-serif (Inter or similar). Bold headings, readable body text
- **Responsive** - mobile-first, works well on all screen sizes

## Pages to Design

### 1. Browse/Search Page (the main page users will spend time on)

This is the primary listing browse page. It should have:

**Filter Sidebar (collapsible on mobile):**
- Region name search (text input)
- Price range (min/max inputs or slider)
- Parcel size range (sqm, min/max)
- Maturity rating (checkboxes: General, Moderate, Adult)
- Parcel tags (multi-select pills from predefined list, e.g. "Waterfront", "Protected Land", "Roadside", "Flat Terrain")
- Reserve status filter (All / Reserve met / Reserve not met / No reserve)
- Snipe protection (All / Enabled / Disabled)
- Verification tier (checkboxes: Script-Verified, Bot-Verified, Human-Verified)
- Sort dropdown: Ending Soonest, Newest, Most Bids, Lowest Price, Largest Area

**Listing Cards in a responsive grid (3 columns desktop, 2 tablet, 1 mobile):**
Each card shows:
- Parcel snapshot image (thumbnail at top of card)
- Parcel name (bold) + region name (muted)
- Current bid amount in L$ (large, prominent) + bid count
- Time remaining (countdown format, e.g. "2d 14h" or "23m 41s" if ending soon - use orange/red text for < 1 hour)
- Parcel size in sqm
- Small badge row: maturity rating, verification tier icon, snipe protection shield icon (if enabled), reserve status
- 1-2 parcel tag pills (overflow hidden with "+3 more" if many)

**Top bar above the grid:**
- Result count ("247 active auctions")
- View toggle: grid view / list view
- Sort dropdown (duplicated here for convenience)

### 2. Auction Detail / Bidding Room Page

The individual auction page where users view details and place bids:

**Left/Main column (wider):**
- Large parcel snapshot image (or image gallery if multiple)
- Parcel name as page heading
- Region name (linked)
- Seller profile card (avatar, display name, rating stars, completion rate, "New Seller" badge if applicable)
- Full description (seller-written)
- Parcel metadata grid: Size (sqm), Prim capacity, Maturity rating, Parcel tags as pills
- "Visit in Second Life" button group: "Open in Viewer" (primary) + "View on Map" (secondary/outline)
- Bid history table (bidder name, amount, time - most recent first)

**Right sidebar (sticky on scroll):**
- Current bid (very large text, prominent)
- Bid count
- Countdown timer (large, updates live - turns orange < 1hr, red < 10min)
- Snipe protection badge and info if enabled ("🛡️ 15 min extension window")
- Reserve status indicator
- Verification tier badge with label
- Bid input field + "Place Bid" button (primary amber CTA)
- Minimum next bid shown below input ("Minimum bid: L$5,500")
- Proxy bid section: expandable/collapsible panel with "Set Maximum Bid" input, current proxy status, cancel proxy button
- Buy-it-now button if seller set a BIN price (secondary style, below bid section)

### 3. User Dashboard

Tabbed interface with:

**"My Bids" tab:**
- Table/card list of auctions the user has bid on
- Status labels: "Winning" (green), "Outbid" (red), "Won" (blue), "Lost" (gray)
- Current bid, your max bid, time remaining
- Quick link to auction page

**"My Listings" tab:**
- Table/card list of auctions the user has created
- Status labels matching lifecycle: Draft, Pending Verification, Active, Ended, Escrow, Completed
- Bid count, current price, time remaining for active ones
- "Create New Listing" button (prominent)

### 4. Header / Navigation

- Logo ("SLPA" or "SL Parcel Auctions") on the left
- Nav links: Browse, Dashboard, Create Listing
- Right side: Dark/Light mode toggle (sun/moon icon), notification bell with unread count badge, user avatar dropdown (Profile, Settings, Sign Out)
- On mobile: hamburger menu

### 5. Footer

- Simple, not heavy. Links: About, Terms, Contact, Partners
- "Powered by Second Life" or similar attribution
- Copyright line

## Component Library

Please also create these reusable components:
- **AuctionCard** - the listing card used in browse grid
- **BidPanel** - the sidebar bidding widget
- **CountdownTimer** - live updating countdown that changes color as time runs low
- **BadgeRow** - composable row of small badges (maturity, verification, snipe, reserve)
- **FilterSidebar** - the collapsible filter panel
- **DarkModeToggle** - theme switcher with smooth transition

## Things to Avoid

- No gradients everywhere - keep it flat/minimal with subtle depth
- No neon colors - warm amber accent, cool slate backgrounds
- Don't make it look like a crypto/NFT marketplace
- Don't overcrowd the listing cards - they need to breathe
- No skeleton screens yet - just the actual designed pages
