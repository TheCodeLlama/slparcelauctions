# Task 01-06: Next.js Layout Shell, Theme System & Component Foundation

> **Before starting:**
> 1. Read [CONVENTIONS.md](../CONVENTIONS.md) — in particular the **Design system ("The Digital Curator")** and **Modular, component-based architecture** sections. Those rules are binding for every frontend task, starting with this one.
> 2. Read the design spec: **[`docs/superpowers/specs/2026-04-10-task-01-06-frontend-foundation-design.md`](../../superpowers/specs/2026-04-10-task-01-06-frontend-foundation-design.md)**. The spec is the source of truth for *why* every decision came out the way it did, the full token block, the complete component contracts, the test infrastructure, the gotchas, and the explicit non-goals. This task brief is the *what to build* checklist; the spec is the *why*.
>
> **If this brief and the spec disagree, the spec wins.** Surface the discrepancy in the PR so we can fix the brief.

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

This project uses **Tailwind 4**, which replaces `tailwind.config.js` theming with the CSS-native `@theme` directive. Define all tokens in `frontend/src/app/globals.css` (after the `app/` → `src/app/` restructure described below). Do not use a JS-based Tailwind config for colors.

**The full token block — both light defaults and dark overrides — is specified in [spec §4.1](../../superpowers/specs/2026-04-10-task-01-06-frontend-foundation-design.md).** Copy it from the spec rather than re-extracting from the Stitch HTML; the extraction was done once during brainstorming and the spec is authoritative. The block includes ~45 M3 color tokens, the full M3 type scale (15 roles via Tailwind 4 multi-value `--text-*` tokens), font/radius/shadow tokens.

**Rules:**

- **Tokens are the single source of truth.** Every color, font, radius, and shadow in the design lives in `@theme`. No hex values anywhere in component code. No inline styles. Enforced by `npm run verify` (see spec §10).
- **Semantic naming only.** Use `--color-surface`, `--color-on-surface`, `--color-primary`, `--color-primary-container`, etc. — names that describe role, not appearance. Never `--color-charcoal-900` or `--color-amber-500`.
- **Components never write `dark:` variants.** Zero occurrences of `dark:` anywhere in `components/` or `app/`. Components use semantic classes (`bg-surface text-on-surface`) and the `.dark` class override swaps the underlying CSS variable values. This is non-negotiable — it's the only way a 50+ component library stays maintainable across two modes.
- **The `@variant dark (.dark &);` directive is deliberately omitted.** Without it, any `dark:` write produces no CSS — a silent no-op that fails loudly in the wrong direction (mode looks broken), teaching the rule by failing to override it. Spec §2.11 explains the rationale; spec §11.12 is the gotcha. Do not add the directive back.
- Import Manrope from Google Fonts via `next/font/google` for zero-CLS — six weights (`300, 400, 500, 600, 700, 800`) cover the M3 type scale. Spec §5.2.

### Theme toggle (next-themes) and provider stack

Install **`next-themes`** and **`@tanstack/react-query`** as part of this task. Both providers compose into a single `<Providers>` client component at `src/app/providers.tsx` — ThemeProvider outside QueryClientProvider, with `QueryClient` constructed inside a `useState` initializer (the App Router footgun, see spec §2.6 / §11.7). Full code in **spec §5.1 + §5.2**.

Key wiring:

- `attribute="class"` — toggles the `.dark` class on `<html>`, flipping CSS variable overrides.
- `defaultTheme="dark"` — dark mode is the default on first visit.
- `enableSystem` — respects `prefers-color-scheme` when the user hasn't explicitly chosen.
- `suppressHydrationWarning` on `<html>` is required because `next-themes` mutates the class before React hydrates.
- TanStack Query defaults: `staleTime: 60_000`, `refetchOnWindowFocus: false`, `retry: 1`. **No devtools** in this task — see spec §2.5 non-goal.
- Mount order: `RootLayout → <Providers> → <AppShell> → children`. AppShell lives inside Providers so Header's `useTheme()` and `useAuth()` hooks have ancestors.

The `ThemeToggle` itself is a `"use client"` component that composes `<IconButton aria-label="Toggle theme">` + `Sun`/`Moon` from the lucide barrel. Mounted-gate via `useEffect` returns `null` before mount to avoid hydration mismatch on the icon. Full contract in spec §7.7.

### Component library foundation (`components/ui/`)

Build eight atomic primitives plus an icon barrel. Each supports both modes, uses token-based colors, and follows the variant/size/tone prop conventions in CONVENTIONS.md. **Full prop contracts and variant tables are in spec §7.**

- **`Button`** — variants (`primary` gradient, `secondary` ghost, `tertiary` text-only), sizes (`sm/md/lg`), `loading`, `leftIcon`, `rightIcon`, `fullWidth`. Spec §7.1.
- **`IconButton`** — circular icon-only button. **`aria-label` is required in the TS type, not optional** — a11y belongs in the type system. Variants match `Button`. Stroke weight `1.5` baked in via `[&_svg]:stroke-[1.5]`. Spec §7.2.
- **`Input`** — default + focus states per DESIGN.md §5, `leftIcon`, `rightIcon`, `error`, `label`, `helperText`. `forwardRef` for React Hook Form. Spec §7.3.
- **`Card`** — compound: `<Card>`, `<Card.Header>`, `<Card.Body>`, `<Card.Footer>`. `surface-container-lowest`, `shadow-soft`, no borders. Sub-components also exported individually. Spec §7.4.
- **`StatusBadge`** — one component with `status` (auction-domain: active, ending-soon, ended, cancelled) and `tone` (generic: default, success, warning, danger) props. Short-circuits to `null` if all three of `status`/`tone`/`children` are absent. Spec §7.5.
- **`Avatar`** — user avatar with fallback initials, sizes `xs/sm/md/lg/xl`. Uses `next/image` with explicit `width`/`height` (not `fill`). Spec §7.6.
- **`ThemeToggle`** — sun/moon `IconButton` wired to `useTheme()`. Mounted-gate to avoid hydration mismatch. Spec §7.7.
- **`Dropdown`** — keyboard-accessible menu built on **Headless UI** (`@headlessui/react`). Array-based `items` API for this task; future task adds `<Dropdown.Item>` compound API for dividers/groups. Spec §7.8.
- **`icons.ts`** — lucide-react barrel re-export (renamed `Menu` → `MenuIcon` to avoid future collision). No `<Icon name="...">` wrapper. Spec §7.9.
- **`index.ts`** — public barrel for the UI library. Spec §7.10.

**Cross-cutting rules baked into every primitive (full list in spec §2.17, §3, §7):**

- Class composition uses the `cn()` helper from `src/lib/cn.ts` (`clsx` + `tailwind-merge`) so consumer-passed `className` always wins via dedup.
- Loading state convention: swap the relevant icon slot for `<Loader2 className="animate-spin" />`, disable the control, preserve layout (no width shift).
- Every primitive gets a sibling `*.test.tsx`. The `npm run verify:coverage` script enforces this.
- Test rule: every primitive test imports `renderWithProviders` from `@/test/render`, never raw `render` from `@testing-library/react`.

Every primitive must work in both dark and light mode. Every primitive gets Vitest + RTL tests covering its contract — see spec §9.4 for the test count breakdown (eight primitives = 35 cases out of the 64 total).

### Layout shell (`components/layout/`)

Six layout components. **Full code and tests in spec §8.**

- **`AppShell`** (RSC) — composes `Header` + `<main>` + `Footer` in a flex column. Imported once by the root layout. Spec §8.1.
- **`Header`** (client) — logo wordmark "SLPA", desktop nav, `ThemeToggle`, notification bell `IconButton`, auth-state-aware right cluster, mobile hamburger. **No border** — uses `bg-surface/80 backdrop-blur-md` + scroll-aware `shadow-soft` (inline `useScrolled` state, threshold `scrollY > 8`). The auth zone reads `useAuth()` and branches on the three-state union — Task 01-08 swaps only the hook body, never Header. Spec §8.2.
- **`NavLink`** (client) — single component with `variant: "header" | "mobile"` consumed by both Header and MobileMenu. Uses `usePathname()` for active-route detection (`text-primary` + `aria-current="page"` when active). Replaces what would otherwise be two near-duplicate components. Spec §8.3.
- **`MobileMenu`** (client) — hamburger-triggered slide-in drawer built on **Headless UI `Dialog`** (focus trap, escape, click-outside, scroll lock, return-focus, ARIA — all free). Backdrop is `bg-inverse-surface/40` (M3-correct overlay token, not `bg-on-surface/40`). Spec §8.4.
- **`Footer`** (RSC) — About / Terms / Contact / Partners links + copyright. `surface-container-low` background shift carries the visual separation; no border. Spec §8.5.
- **`PageHeader`** (RSC) — reusable: `title`, `subtitle`, `breadcrumbs`, `actions` slot. The `actions` is a `ReactNode` slot, so a server `PageHeader` can render a client `<Button>` inside without becoming client itself. Spec §8.6.

### `lib/` foundation

- **`lib/api.ts`** — server-component-safe typed fetch wrapper with base URL from `NEXT_PUBLIC_API_URL`, `credentials: "include"`, `params` helper, RFC 7807 `ProblemDetail` normalization via `ApiError` class, `isApiError` type guard. **No JWT injection** in this task — Task 01-08 adds it as a strict superset. **No retries** — TanStack Query owns retry behavior. **No interceptors / middleware framework** — KISS. Full code in spec §6.1.
- **`lib/auth.ts`** — stub `useAuth()` returning `AuthSession` discriminated union (`loading | authenticated | unauthenticated`). Task 01-08 replaces only the hook body without touching Header or any other consumer. The three-state shape matters: it lets Header avoid flashing "Sign in" → user avatar on initial load. Full code in spec §6.2.
- **`lib/cn.ts`** — `clsx` + `tailwind-merge` composition helper used by every primitive. Full code in spec §6.3.

### Routing

**Eleven** placeholder routes, each rendering `<PageHeader title="..." />` inside the shell so routing + layout are verifiable end-to-end. Each is a thin RSC, ~5–15 lines, no client code. Note: Next 16 makes dynamic-route `params` a `Promise` that must be `await`ed — see spec §11.8.

- `/` (landing placeholder)
- `/browse`
- `/auction/[id]` (dynamic, awaits `params`)
- `/dashboard`
- `/login`
- `/register`
- `/forgot-password`
- `/about`, `/terms`, `/contact`, `/partners` — covers the footer links so they don't 404. Original brief listed seven; the four extras were added during brainstorming so every nav target works.

## Acceptance Criteria

Spec §10.2 has the full "what done looks like" checklist. Summary:

- `npm run test` — all 64 cases green (test count breakdown in spec §9.4).
- `npm run lint` — no errors.
- `npm run build` — completes without warnings.
- **`npm run verify`** — passes all five grep rules (no `dark:` variants, no hex colors in components, no inline styles, every primitive has a sibling test). See spec §10.1 + §10.2.
- Manual smoke test (spec §10.3) — paste the checklist into the PR description and confirm every step in both modes.
- Frontend loads at `http://localhost:3000` with "The Digital Curator" theme. Dark mode is the default on first visit; toggling persists; no theme flash on initial paint.
- **Both modes render correctly** — every shell element, primitive, and placeholder page looks intentional in both, no per-mode code branches in any component.
- Header desktop nav and mobile hamburger both route to all eleven placeholder pages.
- `globals.css` contains the full M3 token set (spec §4.1), the M3 type scale, dark-mode overrides in `.dark { ... }`. Zero hardcoded hex values in components, zero `dark:` variants anywhere in `src/components` or `src/app`.
- `next-themes` and `@tanstack/react-query` wired via `<Providers>` (spec §5.1) inside `RootLayout` (spec §5.2). `suppressHydrationWarning` on `<html>`.
- `lib/api.ts`, `lib/auth.ts`, `lib/cn.ts` all importable from `@/lib/*`. No inline styles, no raw hex colors in JSX, no copy-pasted markup from the Stitch HTML.
- Root README sweep per the `feedback_update_readme_each_task` rule — frontend section gains a one-paragraph update mentioning `npm run test`, `npm run verify`, and the component library.

## Notes

- **Read `docs/stitch_generated-design/DESIGN.md` before writing any code.** If your implementation diverges from that document, the document wins — do not guess.
- Reference both `light_mode/<page>/code.html` and `dark_mode/<page>/code.html` for every placeholder page. Diff them to see which properties are token-swapped vs structural. (The token block in spec §4.1 was extracted from these once during brainstorming — copy from the spec, don't re-extract.)
- The header is a great test of the component library: if building it requires creating several new primitives, that's expected and good. Build them in `components/ui/` first, then compose the header.
- Storybook is not required this task but the primitives should be built as if they'll go into one — isolated, prop-driven, self-contained.
- Light mode "looks intentional" means it uses the warm ivory surface palette from `DESIGN.md §2`, not a jarring pure white.
- **Explicit non-goals are in spec §12.** Before "while we're here" temptations, check the table — most adjacent concerns are already owned by a future task.

## Gotchas (read before implementing)

The spec catalogs 12 footguns in §11. The high-impact ones:

1. **`@/*` alias dual-resolver** — must be in both `tsconfig.json` `paths` AND `vitest.config.ts` `resolve.alias`. Vitest does not read tsconfig.
2. **`next/font/google` blows up jsdom** — must be mocked in `vitest.setup.ts`. Spec §9.2 has the one-liner.
3. **`next/navigation` blows up jsdom** — same fix, mocked globally; per-test override via `vi.mocked(usePathname).mockReturnValue(...)` inside `beforeEach`.
4. **`QueryClient` must be in `useState` initializer**, not module scope. App Router footgun.
5. **`forcedTheme` is off-label as a test default** — `renderWithProviders` exposes opt-in `forceTheme?: boolean` (default `false`) so the `ThemeToggle` integration test can observe the actual class flip.
6. **Next 16 dynamic `params` is a `Promise`** — `await` before destructuring.
7. **Header has no border** — uses `bg-surface/80 backdrop-blur-md` + scroll-aware shadow. Do not add `border-b` "for definition" — that violates the no-line rule and is the precedent we're explicitly avoiding.
8. **`@variant dark` is deliberately omitted** — without it, any `dark:` write produces no CSS, teaching the rule by failing to override. Don't add it back.
