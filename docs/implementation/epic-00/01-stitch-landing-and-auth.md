# Stitch Follow-Up Prompt: Landing Page & Authentication Pages

Use the same design system, color palette, typography, and component styles from the previous design. These pages should feel like they belong to the same site.

## Pages to Design

### 1. Landing Page (Homepage for logged-out users)

This is what people see when they first visit the site. It should communicate what SLPA is, build trust, and get them to sign up or browse.

**Hero Section:**
- Bold headline: something like "Buy & Sell Second Life Land at Auction"
- Subtitle: brief explanation (1-2 sentences) - "The first player-to-player land auction platform for Second Life. List your Mainland parcel, set your terms, and let the market decide the price."
- Two CTAs: "Browse Auctions" (primary amber button) + "List Your Land" (secondary/outline button)
- Background: subtle abstract pattern or a blurred/stylized SL landscape. Nothing too busy - the text needs to be readable in both dark and light mode

**How It Works Section:**
- 3-4 step visual flow with icons:
  1. **Verify** - "Link your Second Life avatar to prove you're a real resident"
  2. **List** - "Create a listing for your Mainland parcel with photos and details"
  3. **Auction** - "Bidders compete in real-time with proxy bidding and snipe protection"
  4. **Settle** - "Secure escrow handles the L$ payment and land transfer"
- Each step gets a small icon and 1-2 sentences. Clean horizontal layout on desktop, vertical stack on mobile

**Features Highlights Section:**
- Card grid (2x2 or 3-column) highlighting key features:
  - **Real-Time Bidding** - "Watch bids update live with WebSocket-powered auction rooms"
  - **Snipe Protection** - "Optional anti-snipe extensions keep auctions fair"
  - **Verified Listings** - "Multi-tier verification ensures sellers actually own the land they're listing"
  - **Secure Escrow** - "L$ held safely until land transfer is confirmed"
  - **Proxy Bidding** - "Set your max and let the system bid for you"
  - **Reputation System** - "Ratings and reviews build trust between buyers and sellers"

**Live Stats Bar (optional but nice):**
- A simple row showing: "X Active Auctions • X Completed Sales • X Verified Users"
- Numbers can be placeholder/static for now

**CTA Section at bottom:**
- "Ready to get started?" with sign-up button
- Or "Browse current auctions" link

### 2. Sign Up Page

Clean, centered card layout. Not too many fields - we want low friction.

**Sign Up Form:**
- Email address
- Password (with strength indicator)
- Confirm password
- "Create Account" button (amber primary)
- "Already have an account? Sign in" link below

**Design notes:**
- Centered card on a minimal background
- Logo at top of the card
- Keep it simple - SL avatar linking happens AFTER account creation, not during signup
- Optional: subtle background pattern or gradient that matches the landing page hero

### 3. Sign In Page

Same layout style as sign up - centered card, minimal.

**Sign In Form:**
- Email address
- Password
- "Remember me" checkbox
- "Sign In" button (amber primary)
- "Forgot password?" link
- "Don't have an account? Sign up" link below

**Design notes:**
- Visually consistent with the sign-up page (same card style, same background)
- These two pages should clearly be siblings

### 4. Forgot Password Page (bonus, if time)

- Same centered card layout
- Email input + "Send Reset Link" button
- Success state: "Check your email for a reset link"
- "Back to sign in" link

## General Notes

- All auth pages should work in both dark and light mode
- Form validation styling: red border + error message below field for invalid inputs
- Loading state for buttons: spinner or subtle animation while submitting
- The landing page is the ONLY page that's marketing-focused. Once logged in, users go straight to the browse page. Keep the landing page punchy and not too long - this isn't a SaaS product with 15 feature sections
- Mobile responsive on everything
