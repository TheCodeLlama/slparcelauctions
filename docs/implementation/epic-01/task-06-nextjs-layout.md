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

This project uses **Tailwind 4**, which replaces `tailwind.config.js` theming with the CSS-native `@theme` directive. Define all tokens in `frontend/src/app/globals.css` (or equivalent). Do not use a JS-based Tailwind config for colors.

**Required structure:**

```css
@import "tailwindcss";

@variant dark (.dark &);

@theme {
  /* Light mode defaults — read values from docs/stitch_generated-design/light_mode/ */
  --color-surface: ...;
  --color-surface-container-lowest: ...;
  --color-surface-container-low: ...;
  --color-surface-container-high: ...;
  --color-on-surface: ...;
  --color-on-surface-variant: ...;
  --color-primary: ...;
  --color-primary-container: ...;
  --color-on-primary: ...;
  --color-on-primary-container: ...;
  --color-outline-variant: ...;
  --color-tertiary-container: ...;
  --color-error-container: ...;
  /* ... every token from DESIGN.md ... */

  --font-display: "Manrope", sans-serif;
  --font-body: "Manrope", sans-serif;

  --radius-sm: ...;
  --radius-default: ...;

  --shadow-soft: ...;
  --shadow-elevated: ...;
}

.dark {
  /* Dark mode overrides — same token names, different values.
     Read values from docs/stitch_generated-design/dark_mode/.
     Components never write `dark:` variants; they read semantic tokens
     and the tokens do the work. */
  --color-surface: ...;
  --color-surface-container-lowest: ...;
  /* ... override every color token that differs in dark mode ... */
}
```

**Rules:**

- **Tokens are the single source of truth.** Every color, font, radius, and shadow in the design lives in `@theme`. No hex values anywhere in component code. No inline styles.
- **Semantic naming only.** Use `--color-surface`, `--color-on-surface`, `--color-primary`, `--color-primary-container`, etc. — names that describe role, not appearance. Never `--color-charcoal-900` or `--color-amber-500`.
- **Components never write `dark:` variants.** Zero occurrences of `dark:` anywhere in `components/` or `app/`. Components use semantic classes (`bg-surface text-on-surface border-outline-variant`) and the `.dark` class override swaps the underlying CSS variable values. This is non-negotiable — it's the only way a 50+ component library stays maintainable across two modes.
- **Both palettes must be read from the Stitch reference HTML.** Open `docs/stitch_generated-design/light_mode/landing_page/code.html` and `docs/stitch_generated-design/dark_mode/landing_page/code.html` side by side, extract the colors, and put them in `@theme` and `.dark`. Cross-check against `DESIGN.md`.
- Import Manrope from Google Fonts (via `next/font/google` for zero-CLS).

### Theme toggle (next-themes)

Install and use **`next-themes`** for theme management. Do not roll your own. It handles localStorage persistence, `prefers-color-scheme` detection, and no-flash-on-load correctly.

```bash
npm install next-themes
```

```tsx
// frontend/src/app/layout.tsx
import { ThemeProvider } from "next-themes";

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" suppressHydrationWarning>
      <body>
        <ThemeProvider attribute="class" defaultTheme="dark" enableSystem>
          {children}
        </ThemeProvider>
      </body>
    </html>
  );
}
```

- `attribute="class"` — toggles the `.dark` class on `<html>`, which flips the CSS variable overrides defined in `globals.css`.
- `defaultTheme="dark"` — dark mode is the default on first visit.
- `enableSystem` — respects `prefers-color-scheme` when the user hasn't explicitly chosen.
- `suppressHydrationWarning` on `<html>` is required because `next-themes` mutates the class before React hydrates.

The `ThemeToggle` component is then trivial:

```tsx
"use client";
import { useTheme } from "next-themes";
import { useEffect, useState } from "react";

export function ThemeToggle() {
  const { resolvedTheme, setTheme } = useTheme();
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);
  if (!mounted) return null; // avoid hydration mismatch on the icon
  return (
    <IconButton
      onClick={() => setTheme(resolvedTheme === "dark" ? "light" : "dark")}
      aria-label="Toggle theme"
    >
      {resolvedTheme === "dark" ? <SunIcon /> : <MoonIcon />}
    </IconButton>
  );
}
```

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
- `globals.css` contains the full token set from `DESIGN.md` in the `@theme` block, with dark-mode overrides in `.dark { ... }` — no hardcoded hex values in any component, and zero `dark:` variants anywhere in `components/` or `app/`.
- `next-themes` is installed and wired in `RootLayout` with `attribute="class" defaultTheme="dark" enableSystem` and `suppressHydrationWarning` on `<html>`.
- `lib/api.ts` is importable and exports a typed fetch helper.
- No inline styles, no raw hex colors in JSX, no copy-pasted markup from the Stitch HTML.

## Notes

- **Read `docs/stitch_generated-design/DESIGN.md` before writing any code.** If your implementation diverges from that document, the document wins — do not guess.
- Reference both `light_mode/<page>/code.html` and `dark_mode/<page>/code.html` for every placeholder page. Diff them to see which properties are token-swapped vs structural.
- The header is a great test of the component library: if building it requires creating several new primitives, that's expected and good. Build them in `components/ui/` first, then compose the header.
- Storybook is not required this task but the primitives should be built as if they'll go into one — isolated, prop-driven, self-contained.
- Light mode "looks intentional" means it uses the warm ivory surface palette from `DESIGN.md §2`, not a jarring pure white.
