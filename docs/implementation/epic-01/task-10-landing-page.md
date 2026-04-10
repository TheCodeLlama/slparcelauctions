# Task 01-10: Landing Page

## Goal

Build the public landing page that visitors see before logging in. This is the marketing/onboarding page that explains what SLPA is and drives sign-ups.

## Context

The Stitch-generated design is in `docs/stitch-generated-design/landing_page/`. The layout shell and theme system exist from Task 01-06. This page should be accessible without authentication.

## What Needs to Happen

- Build the landing page at `/` (root route for unauthenticated users):
  - **Hero section:** Bold headline about buying/selling SL land at auction, subtitle explaining the platform, two CTAs ("Browse Auctions" → /browse, "List Your Land" → /register or /dashboard)
  - **How It Works section:** 3-4 step visual flow with icons - Verify, List, Auction, Settle
  - **Features section:** Card grid highlighting key features (Real-Time Bidding, Snipe Protection, Verified Listings, Secure Escrow, Proxy Bidding, Reputation System)
  - **CTA section at bottom:** Sign-up prompt
- Ensure it works in both dark and light mode
- Responsive design (desktop, tablet, mobile)
- When a logged-in user visits `/`, either redirect to `/browse` or show the landing page with adjusted CTAs (your choice on what feels better)

## Acceptance Criteria

- Landing page loads at `/` for unauthenticated users
- All sections render correctly in both dark and light mode
- CTAs link to the correct pages
- Responsive on mobile, tablet, and desktop
- Page matches the Stitch design aesthetic
- No authentication required to view

## Notes

- Reference `docs/stitch-generated-design/landing_page/code.html` for layout and styling cues but build as proper React components.
- Stats bar ("X Active Auctions") can use hardcoded placeholder numbers for now.
- Keep it concise - this isn't a SaaS product with 15 sections. Hero + How It Works + Features + CTA is enough.
