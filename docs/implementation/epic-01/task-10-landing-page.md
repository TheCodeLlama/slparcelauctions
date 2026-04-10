# Task 01-10: Landing Page

> **Before starting:** read [CONVENTIONS.md](../CONVENTIONS.md) for project-wide rules (no new migrations, Lombok required, vertical slices, feature-based packages).

## Goal

Build the public landing page that visitors see before logging in. This is the marketing/onboarding page that explains what SLPA is and drives sign-ups.

## Context

The Stitch-generated designs are in:

- `docs/stitch_generated-design/light_mode/landing_page/` (`code.html` + `screen.png`)
- `docs/stitch_generated-design/dark_mode/landing_page/` (`code.html` + `screen.png`)

Read both. Also re-read `docs/stitch_generated-design/DESIGN.md` — the landing page is the showcase for "The Digital Curator" aesthetic (intentional asymmetry, tonal depth, glassmorphism). The layout shell, theme, and component library exist from Task 01-06. This page is accessible without authentication.

**Every section on this page should be a reusable component.** `Hero`, `HowItWorksStep`, `FeatureCard`, `CtaSection`, etc. — not inline JSX. Even if a component is used once today, it will be reused by `/browse`, `/dashboard`, or marketing pages later. Compose the page from components; do not write a 400-line `page.tsx`.

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
- Page matches both the light and dark mode Stitch references
- Every visually distinct section is its own component, composed in `app/page.tsx`
- No authentication required to view

## Notes

- Reference both `docs/stitch_generated-design/light_mode/landing_page/code.html` and `docs/stitch_generated-design/dark_mode/landing_page/code.html` for layout and styling cues, but build as proper React components composed from `components/ui/` primitives plus new landing-specific components.
- Stats bar ("X Active Auctions") can use hardcoded placeholder numbers for now.
- Keep it concise - this isn't a SaaS product with 15 sections. Hero + How It Works + Features + CTA is enough.
- If a component you build here (e.g. `FeatureCard`, `HowItWorksStep`, `Hero`) could be reused on the homepage featured view (Task 07-04), put it in `components/marketing/` so Task 07-04 can import it.
