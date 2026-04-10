# Task 01-06: Next.js Layout Shell & Theme System

> **Before starting:** read [CONVENTIONS.md](../CONVENTIONS.md) for project-wide rules (no new migrations, Lombok required, vertical slices, feature-based packages).

## Goal

Set up the Next.js frontend with the Gilded Slate design system, dark/light mode toggle, responsive layout shell (header, navigation, footer), and basic page routing.

## Context

Next.js 16 with Tailwind 4 is already initialized. The design system is documented in `docs/stitch-generated-design/gilded_slate/DESIGN.md`. Stitch-generated HTML/CSS for all pages are in `docs/stitch-generated-design/` - use these as visual reference for layout structure, colors, typography, and component styling.

The design uses:
- Dark mode first with deep charcoal backgrounds (#121416, #1a1c1e, #282a2c)
- Warm amber/gold accent (#F59E0B range)
- Manrope for headlines, Inter for body text
- No solid borders - use background shifts and negative space
- Glassmorphism on floating elements (nav, sticky panels)

## What Needs to Happen

- Configure Tailwind with the Gilded Slate color tokens, typography, and spacing
- Implement dark/light mode with a toggle (sun/moon icon) - persist preference in localStorage, default to dark
- Build the app layout shell:
  - **Header:** Logo ("SLPA" or "SL Parcel Auctions"), nav links (Browse, Dashboard, Create Listing), dark mode toggle, placeholder for notification bell and user avatar dropdown
  - **Footer:** Simple - About, Terms, Contact, Partners links + copyright
  - **Mobile:** Hamburger menu for navigation
- Create placeholder page routes: `/` (landing), `/browse`, `/auction/[id]`, `/dashboard`, `/login`, `/register`, `/forgot-password`
- Each placeholder page should just show its name in a heading so routing is verifiable
- Set up the API client utility (fetch wrapper that will eventually attach JWT tokens and handle errors)

## Acceptance Criteria

- Frontend loads at localhost:3000 with the Gilded Slate dark theme applied
- Dark/light mode toggle works and persists across page refreshes
- Header navigation links route to the correct placeholder pages
- All placeholder routes load without errors
- Mobile hamburger menu works on small viewports
- Footer is visible on all pages
- Light mode looks intentional (soft light grey background, not jarring white)
- Tailwind config includes the design system color tokens

## Notes

- Reference the Stitch-generated HTML files for visual accuracy but rebuild as proper React components - don't copy-paste raw HTML.
- The header should conditionally show auth-related items (login/register vs user avatar) but for now just show both as placeholders since auth isn't wired up yet.
- Google Fonts: import Manrope and Inter.
