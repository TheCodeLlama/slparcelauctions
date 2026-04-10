# Task 01-06: Next.js Layout Shell, Theme System & Component Foundation

> **Before starting:** read [CONVENTIONS.md](../CONVENTIONS.md) — in particular the **Design system ("The Digital Curator")** and **Modular, component-based architecture** sections. Those rules are binding for every frontend task, starting with this one.

## Goal

Stand up the foundation that every subsequent frontend task will build on: the Tailwind theme, the dark/light mode system, the shared layout shell, the initial set of reusable UI primitives, and the placeholder routing.

This task is explicitly about **establishing the component library and theme architecture**, not shipping finished pages. Pages land in later tasks; they need a well-designed toolbox to draw from first.

## Context

Next.js 16 with Tailwind 4 is already scaffolded. The design system lives in `docs/stitch_generated-design/`:

- **Strategy document:** `docs/stitch_generated-design/DESIGN.md` — read this end-to-end before touching code. It defines "The Digital Curator" aesthetic, the no-line rule, surface hierarchy, typography, elevation, glassmorphism, and the core component rules (buttons, cards, inputs, chips, curator tray).
- **Reference HTML:**
  - `docs/stitch_generated-design/light_mode/` — light mode reference for every page
  - `docs/stitch_generated-design/dark_mode/` — dark mode reference for every page
  - Both directories contain: `landing_page/`, `sign_in/`, `sign_up/`, `forgot_password/`, `browse_auctions/`, `auction_detail/`, `user_dashboard/` with a `code.html` and `screen.png` each.

**Both modes are first-class.** The layout shell, the theme tokens, and every component you build must look correct in both. Read both `light_mode/` and `dark_mode/` references for each page they render. Do not treat light mode as a variant; treat it as a peer.

Use the reference HTML for layout, spacing, typography, and component composition cues. **Do not copy-paste raw HTML** — rebuild everything as proper React components.

## What Needs to Happen

### Theme & Tailwind setup

- Configure Tailwind with the full token set from `DESIGN.md`: colors (primary, surface tiers, on_surface, outline_variant, tertiary_container, error_container, etc.), typography (Manrope for display/headline/title/body/label scales), spacing, border radius (`sm`, `DEFAULT`, `Round 4`, etc.), and shadow tokens.
- All colors go in `tailwind.config.ts` as named tokens. **No hardcoded hex values in components.**
- Dark/light mode via Tailwind's class strategy (`class` not `media`) so we can drive it from a toggle.
- Theme toggle persists in `localStorage`, respects `prefers-color-scheme` on first visit, never flashes the wrong theme on initial paint (inline `<script>` in `<head>` to set the class before React hydrates).
- Import Manrope from Google Fonts.

### Component library foundation (`components/ui/`)

Build the initial atomic primitives. Each one supports both modes, uses token-based colors, and follows the variant/size/tone prop conventions in CONVENTIONS.md:

- **`Button`** — variants (`primary` with gradient, `secondary` ghost, `tertiary` text-only), sizes (`sm`, `md`, `lg`), `loading`, `leftIcon`, `rightIcon`, `fullWidth` props. One component, not three.
- **`Input`** — default / focus states per `DESIGN.md §5`. Supports `leftIcon`, `rightIcon`, `error`, `label`, `helperText`. Works with React Hook Form (forward ref, standard event signature).
- **`Card`** — compound component: `<Card>`, `<Card.Header>`, `<Card.Body>`, `<Card.Footer>`. Uses surface-container-lowest tonal layering, `sm` shadow, no borders.
- **`Chip`** / **`StatusBadge`** — one component with `status` / `tone` props. Covers auction statuses (active, ending-soon, ended, cancelled) and generic tones (default, success, warning, danger).
- **`Avatar`** — user avatar with fallback initials, sizes (`xs`, `sm`, `md`, `lg`, `xl`).
- **`IconButton`** — circular icon-only button, variants match `Button`.
- **`ThemeToggle`** — sun/moon icon that flips the theme class on `<html>`, persists to localStorage.
- **`Dropdown`** / **`Menu`** — keyboard-accessible menu primitive for the user avatar menu and future use.

Every primitive must work in both dark and light mode. Every primitive gets at least one Vitest + RTL test covering its main variants.

### Layout shell (`components/layout/`)

- **`Header`** — logo ("SLPA"), main nav (Browse, Dashboard, Create Listing), `ThemeToggle`, notification bell placeholder, user avatar dropdown placeholder. Uses the glassmorphism rule from `DESIGN.md §2` for the floating nav. Conditionally shows login/register vs authenticated avatar (placeholder logic — auth lands in Task 01-07).
- **`Footer`** — About, Terms, Contact, Partners links + copyright. Uses `surface-container-low` tonal background shift, no border lines.
- **`MobileMenu`** — hamburger-triggered off-canvas menu for small viewports.
- **`AppShell`** — wraps `Header`, page content, and `Footer`. Imported once by the root layout.
- **`PageHeader`** — reusable page header component: `title`, `subtitle`, `breadcrumbs`, `actions` slot. Every real page later will use this.

### API client foundation (`lib/`)

- `lib/api.ts` — fetch wrapper with base URL from `NEXT_PUBLIC_API_URL`, JSON encoding, error normalization (converts RFC 7807 ProblemDetail responses into typed errors), placeholder JWT header injection (wired in Task 01-08).
- `lib/theme.ts` — theme constants, toggle helpers.

### Routing

Placeholder routes, each rendering `<PageHeader title="..." />` inside the shell so routing + layout are verifiable:

- `/` (landing)
- `/browse`
- `/auction/[id]`
- `/dashboard`
- `/login`
- `/register`
- `/forgot-password`

## Acceptance Criteria

- Frontend loads at `http://localhost:3000` with "The Digital Curator" theme applied.
- Dark mode is the default on first visit; toggling persists across refreshes; no theme flash on initial paint.
- **Both modes render correctly** — a reviewer can toggle between light and dark and every shell element, primitive, and placeholder page looks intentional and matches the tonal layering described in `DESIGN.md`.
- Header navigation routes to the placeholder pages; mobile hamburger works below the Tailwind `md` breakpoint.
- All primitives in `components/ui/` exist, follow the variant/size/tone convention, have Vitest + RTL tests, and are importable from a barrel (`components/ui/index.ts`).
- Every primitive and layout component works in both modes without per-mode code branches (tokens do the work).
- `tailwind.config.ts` contains the full token set from `DESIGN.md` — no hardcoded hex values in any component.
- `lib/api.ts` is importable and exports a typed fetch helper.
- No inline styles, no raw hex colors in JSX, no copy-pasted markup from the Stitch HTML.

## Notes

- **Read `docs/stitch_generated-design/DESIGN.md` before writing any code.** If your implementation diverges from that document, the document wins — do not guess.
- Reference both `light_mode/<page>/code.html` and `dark_mode/<page>/code.html` for every placeholder page. Diff them to see which properties are token-swapped vs structural.
- The header is a great test of the component library: if building it requires creating several new primitives, that's expected and good. Build them in `components/ui/` first, then compose the header.
- Storybook is not required this task but the primitives should be built as if they'll go into one — isolated, prop-driven, self-contained.
- Light mode "looks intentional" means it uses the warm ivory surface palette from `DESIGN.md §2`, not a jarring pure white.
