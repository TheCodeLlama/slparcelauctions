_April 10, 2026_

# Task 01-06: Frontend Foundation — Design Spec

> **What this is.** The rationale + full prescription for Task 01-06: the layout shell, theme system, component primitive library, `lib/` contracts, routing, and testing infrastructure that every subsequent frontend task builds on. The companion task brief is [`docs/implementation/epic-01/task-06-nextjs-layout.md`](../../implementation/epic-01/task-06-nextjs-layout.md) — that doc tells the contractor *what* to build, this doc tells them *why* the decisions came out the way they did.
>
> **Audience.** Two: the implementing contractor (reads sections 3–9 prescriptively) and future reviewers (read section 2 for rationale). The contractor should be able to ship this task by following the prescriptive sections; the rationale is for "I think we should do it differently" conversations.

---

## 1. Overview

Task 01-06 stands up the foundation that every subsequent frontend task will depend on. It is **explicitly not a page-shipping task**: real pages (landing, browse, auction detail, dashboard, auth flows) land in later tasks. This task ships the toolbox those pages will draw from.

Concretely, this task delivers:

1. A `src/`-based directory restructure for the existing Next.js scaffold.
2. The full Material 3 token vocabulary in `globals.css` — both light mode (`@theme`) and dark mode (`.dark` overrides) — extracted from the Stitch reference HTML, no invention.
3. The full M3 type scale as Tailwind 4 multi-value `--text-*` tokens.
4. `next-themes` wired with class-based dark mode, default dark, system detection, no flash on first paint.
5. TanStack Query wired via a single `<Providers>` component with the agreed defaults (`staleTime: 60_000`, `refetchOnWindowFocus: false`, `retry: 1`).
6. `lib/api.ts` — a server-component-safe typed fetch wrapper with RFC 7807 ProblemDetail normalization.
7. `lib/auth.ts` — a stub `useAuth()` hook with the three-state `AuthSession` shape that Task 01-08 will fill in.
8. `lib/cn.ts` — the `clsx` + `tailwind-merge` utility every primitive uses for class composition.
9. Eight UI primitives in `components/ui/`: `Button`, `IconButton`, `Input`, `Card` (compound), `StatusBadge`, `Avatar`, `ThemeToggle`, `Dropdown` (Headless UI–backed).
10. A lucide-react icon barrel and a public `index.ts` barrel for the UI library.
11. Five layout components in `components/layout/`: `AppShell`, `Header` (client), `MobileMenu` (client, Headless UI Dialog), `Footer` (RSC), `PageHeader` (RSC), plus a single `NavLink` (client) consumed by both Header and MobileMenu.
12. Eleven placeholder routes wired through the shell so layout is end-to-end verifiable.
13. Vitest + RTL test infrastructure with `renderWithProviders` helper, `next/font` and `next/navigation` global mocks, and ~64 test cases across primitives, layout, and lib.
14. `npm run verify` script wrapping the grep-based rule enforcement (no `dark:` variants, no hex colors in components, no inline styles, every primitive has a test).

The task is **deliberately large** — it spans visual system, component library, infrastructure, and tests in one PR — because every layer is interdependent and shipping any subset would force a rework when the next layer lands. The design system, the primitives, and the test infrastructure mature together or none of them mature at all.

---

## 2. Decisions & rationale

This section captures the design conversation distilled. Each subsection is one major decision point with the alternatives considered and the chosen path. Implementation details are in sections 3–10; this section is *why*.

### 2.1 `src/` directory restructure

**Decision:** Move `frontend/app/` to `frontend/src/app/`. Add `src/components/{ui,layout}/`, `src/lib/`, `src/test/`. Use `git mv` so history follows the files.

**Why:** `CONVENTIONS.md` already prescribes `frontend/src/{app,components,lib}/` and the original task brief writes paths like `frontend/src/app/globals.css`. The existing scaffold has `frontend/app/` because `create-next-app` defaulted that way. Three options were considered:

- **A. Move `app/` into `src/app/`** — chosen. One-time mechanical move, matches CONVENTIONS as written, every future task starts in the right place. `src/` keeps source code visually separate from tooling config (`next.config.ts`, `package.json`, `eslint.config.mjs`, `Dockerfile.dev`).
- **B. Keep `app/` at the frontend root** — rejected. Would diverge from CONVENTIONS, and the moment we cite CONVENTIONS in PR review we'd be apologizing for the deviation.
- **C. Update CONVENTIONS to drop `src/`** — rejected. Honest but loses the namespacing benefit.

The cost is small (four files moved, `tsconfig.json` paths update) and the benefit compounds for every future task.

### 2.2 `@/*` path alias

**Decision:** Configure `@/*` → `./src/*` in **both** `tsconfig.json` `paths` **and** `vitest.config.ts` `resolve.alias`.

**Why:** `@/*` is the Next.js App Router convention; every scaffold and reviewer expects it. A `@slpa/*` namespace was rejected as inventing disambiguation we don't need.

The dual-resolver requirement is the footgun: **Vitest does not read tsconfig paths**. Tests fail to resolve `@/components/...` imports unless the alias is also declared in `vitest.config.ts`. Captured in section 11 (Gotchas) so the contractor doesn't lose 20 minutes to it.

### 2.3 Providers as a single file in `src/app/`

**Decision:** Put both `ThemeProvider` and `QueryClientProvider` inside a single `<Providers>` client component at `src/app/providers.tsx`. **No** `components/providers/` folder.

**Why:** Providers are a layout concern, not a component library. The `components/` tree is conceptually "things pages import and compose"; providers are the opposite — wrapped around everything, imported exactly once, in exactly one place (`layout.tsx`). Three options were considered:

- **A. Single `src/app/providers.tsx`** — chosen. Pattern the Next.js docs use, one file owns ordering, layout.tsx becomes `<Providers>{children}</Providers>`. Adding a third provider later is a one-file change.
- **B. `components/providers/{ThemeProvider,QueryProvider}.tsx`** — rejected. Splits one concept across files, invites someone to import a provider into a page component (subtle footgun), and `ThemeProvider.tsx` would be a wrapper around a wrapper.
- **C. `src/lib/providers/{ThemeProvider,QueryProvider}.tsx` for testability** — held in reserve. If we ever need to mount just one provider in isolation for tests, we collapse `providers.tsx` into a composer of two files in `src/lib/providers/`. **Never `components/providers/`.**

### 2.4 lucide-react via a barrel

**Decision:** Use `lucide-react` for icons. Import only via the barrel at `src/components/ui/icons.ts`. Stroke weight `1.5` baked into `IconButton` via `[&_svg]:stroke-[1.5]`. No `<Icon name="sun" />` wrapper, no string-lookup, no abstraction layer.

**Why:** Three options were compared:

- **A. lucide-react** — chosen. ~1,500 icons, tree-shakable, plain SVG components, MIT, the de-facto choice for new Tailwind projects in 2026. Thin-stroke aesthetic fits "Quiet Luxury" / Curator. Lowest bundle cost per icon.
- **B. heroicons/react** — rejected. Smaller catalog (~300), pairs with Tailwind by default but wouldn't cover the auction-domain icons we need in later tasks (gavel, parcel, etc., which we'll add as custom SVGs to the lucide barrel).
- **C. Material Symbols** — rejected. The Stitch HTML uses font-glyph rendering which doesn't translate to React. ~3× bundle cost. Visual style is heavier than the Curator aesthetic wants.

The barrel re-export gives a single point of swap. When custom SVGs (gavel, parcel, SL logo, Linden symbol) join the icon set, they're added to `icons.ts` and nothing downstream changes. A `<Icon name="..." />` wrapper would gain nothing and lose tree-shaking.

### 2.5 TanStack Query — wire now, no devtools

**Decision:** Install `@tanstack/react-query` in this task. Wire `<QueryClientProvider>` inside `<Providers>` with the defaults `staleTime: 60_000`, `refetchOnWindowFocus: false`, `retry: 1`. **No devtools** in this task. **No `useQuery` hooks** in this task — `lib/api.ts` ships as a plain typed fetch wrapper that `useQuery` will eventually call.

**Why:** CONVENTIONS already names TanStack Query as the API state choice, so the question was *when* not *if*. Three positions:

- **A. Wire provider now, no hooks** — chosen. ~15 lines, belongs topically in "layout shell," keeps Task 01-08's PR focused on auth instead of "auth + plumbing."
- **B. Defer entirely to Task 01-08** — rejected. Smaller blast radius this task but Task 01-08 grows by 20 lines and has to touch the root layout.
- **C. Wire provider + devtools + a smoke `useQuery`** — rejected. Devtools clutter the dev experience until we have real queries; a smoke query is a smoke test masquerading as a feature.

The defaults reflect an auction site's reality: real-time updates come via WebSocket (Phase 6), not query refetching. `refetchOnWindowFocus: false` prevents tab-focus from re-hammering endpoints. Individual hooks override per-query when stricter freshness is needed.

**Devtools deferral note:** When devtools land later, gate them on `process.env.NODE_ENV === 'development'` and lazy-import so they never ship to prod bundles.

### 2.6 `QueryClient` lifecycle inside `<Providers>`

**Decision:** Construct `QueryClient` inside `useState` initializer, not at module scope.

**Why:** The Next.js App Router footgun. A module-level `const queryClient = new QueryClient(...)` is shared across requests on the server. `useState(() => new QueryClient(...))` constructs a fresh client per component instance, which on the client is once per app session and on the server is once per request. This is the official TanStack guidance for App Router.

### 2.7 Mount order: ThemeProvider outside QueryProvider

**Decision:** `<ThemeProvider>` wraps `<QueryClientProvider>`, not the other way around.

**Why:** Theme state is not query-backed and should not invalidate on any query activity. Wrapping query inside theme isolates the two state systems cleanly. If we ever introduce a third provider whose state depends on theme (e.g., a future toaster that picks accent colors from theme tokens), it goes inside `ThemeProvider` but outside `QueryProvider`.

### 2.8 Vitest with jsdom + explicit imports + collocated tests

**Decision:** `vitest` + `jsdom` + `@testing-library/{react,jest-dom,user-event}`. Collocated test files (`Button.test.tsx` next to `Button.tsx`), explicit imports (no `globals: true`), `next/font` and `next/navigation` mocked globally in `vitest.setup.ts`, custom `renderWithProviders` helper at `src/test/render.tsx`.

**Why:**

- **jsdom over happy-dom** — happy-dom's speed advantage (~2–3×) matters at hundreds of tests, not at the ~64 cases this task ships. jsdom is the RTL ecosystem default; every Stack Overflow answer assumes it; happy-dom has rough edges with newer DOM APIs (ResizeObserver, focus management, Selection API). Revisit if the test run ever crosses ~10 seconds.
- **Explicit imports** — tiny typing convenience cost vs. hours of "where is `expect` coming from?" Every major ecosystem that tried globals (Jest, Mocha) eventually regretted not making imports explicit.
- **Collocated tests** — easier to grep, easier to delete-with-the-component, matches the React-ecosystem default. No `__tests__/` folder.
- **Global mocks** — `next/font/google` blows up jsdom because it expects the Next build pipeline; `next/navigation` hooks (`usePathname`, `useRouter`) require a request context. Mocking both globally means tests don't blow up on import; per-test overrides via `vi.mocked()`.
- **`renderWithProviders` factory** — fresh `QueryClient` per test (sharing leaks query state into "flaky tests weeks later"), `enableSystem={false}` on `ThemeProvider` (jsdom's `matchMedia` is a stub), `retry: false` on queries and mutations (we don't want 3× retries turning a 10ms test into a 3-second timeout).

### 2.9 `forceTheme` opt-in, not forced by default

**Decision:** `renderWithProviders` accepts `theme?: "light" | "dark"` (initial theme) and `forceTheme?: boolean` (default `false`). Default path lets `next-themes` actually transition; `forceTheme: true` locks the theme via `forcedTheme={theme}` for tests that need snapshot stability.

**Why:** A first design used `forcedTheme={theme}` unconditionally to pin snapshots, but that breaks the one test that legitimately needs to observe a theme transition (the `ThemeToggle` test must verify that clicking the button flips `documentElement`'s class). `forcedTheme` is designed for edge cases like "this page is always light mode" — using it as the default in tests is off-label usage. `enableSystem={false}` already prevents the matchMedia/prefers-color-scheme nondeterminism that motivated `forcedTheme` in the first place.

### 2.10 The full M3 token block, up front

**Decision:** Put the **entire** M3 color token vocabulary (~45 tokens) in `@theme` from day one, not just the ones today's primitives reference.

**Why:** Unused tokens compile to unused CSS custom properties (~10 bytes each minified, zero runtime cost). Tailwind 4 only generates utility classes for tokens that are *referenced*, not defined, so the bundle cost is exactly zero. The "unused" 80 lines of CSS are 80 lines of *contract* with the design system that prevent the "I need `secondary-container`, let me just add it real quick" drift where a contractor invents a hex value rather than defining the token properly. Extracting tokens once while both reference HTMLs are open is much cheaper than doing it in ten separate PRs over two months.

### 2.11 Strip `@variant dark (.dark &);`

**Decision:** Do **not** include `@variant dark (.dark &);` in `globals.css`. The original task brief shows it; we deliberately omit it.

**Why:** The rule is "zero `dark:` variants in `src/components` and `src/app`." If the directive exists, `dark:bg-red-500` compiles to a working class and a single PR slipping through review puts a crack in the wall. If the directive does **not** exist, `dark:bg-red-500` is parseable Tailwind syntax but produces no CSS — the class silently no-ops. A developer notices dark mode looks wrong and discovers the rule by failing to override it. **The failure teaches the rule.**

The "third-party widget with hardcoded colors" escape hatch isn't real: lucide SVGs honor `currentColor`, and `next-themes` has no styling of its own. If we ever genuinely hit a case where component-level dark variants are necessary, we add the directive back with an inline comment explaining the exception, get explicit review, and move on. Leaving it in "just in case" is the kind of small compromise that silently grows.

### 2.12 Full M3 type scale via multi-value `--text-*` tokens

**Decision:** Define the entire M3 type scale (15 roles: `display-{lg,md,sm}`, `headline-{lg,md,sm}`, `title-{lg,md,sm}`, `body-{lg,md,sm}`, `label-{lg,md,sm}`) in `@theme` using Tailwind 4 multi-value text tokens. Use M3 standard pixel values; use `px` for letter-spacing (M3's values are absolute pixels, not em). Flag `display-lg` (57px) for revisit during Task 01-10 if it feels too heavy for Curator hero typography.

**Why:** We're adopting M3, not inventing. The whole color palette is M3 — using anything other than M3's type scale creates a dialect mismatch. `DESIGN.md`'s type naming (`display-md`, `headline-sm`, etc.) is the M3 naming. The first primitive that needs `label-md` is `Button`; the first layout component that needs `headline-md` is `PageHeader`. Defining the scale piecemeal across three or four components is more total work than defining the whole scale once.

### 2.13 lib/api.ts — minimum viable, no speculative extension points

**Decision:** Single `request<T>` core with four method shortcuts (`get`, `post`, `put`, `delete`). Includes `credentials: "include"`, `params` helper, `isApiError` type guard. No interceptors, no retry, no timeout, no JWT field. RFC 7807 `ProblemDetail` normalization via `ApiError` class.

**Why:** TanStack Query owns retry behavior on the consumer side. Auth lands in Task 01-08 as a strict superset of the current shape. Timeouts come back when a real endpoint asks for them. `credentials: "include"` is set now because adding it later when we add cookie-based refresh tokens (httpOnly cookies) is harder to backfill than to set as the default and forget.

The wrapper is **server-component safe** by construction — no React hooks, no `window` references, no module-level state that depends on the request. Server components can call it directly, server actions can call it, client components call it via TanStack Query hooks. The same module works in all four contexts (client, server, server actions, server components).

### 2.14 `lib/auth.ts` stub with three-state shape

**Decision:** Stub `useAuth()` in `src/lib/auth.ts` returning a discriminated union: `{ status: "loading" | "authenticated" | "unauthenticated"; user: AuthUser | null }`. Header consumes the hook. Task 01-08 replaces only the hook body without touching Header or any other consumer.

**Why:** A boolean-in-the-file approach (`const isAuthenticated = false` directly in `Header.tsx`) creates a dual edit for Task 01-08 — it has to touch both `Header.tsx` and the new auth provider. The hook approach localizes the swap to one file. Every other component that later imports `useAuth()` gets the real implementation for free.

The three-state shape matters: on first page load, before the JWT is validated, the correct state is `loading`, not `unauthenticated`. Without that distinction, Header would flash "Sign in" → user avatar on every refresh. Locking the shape now means Task 01-08's implementation has to conform to it instead of inventing a new shape.

### 2.15 Headless UI for Dropdown and Dialog

**Decision:** Install `@headlessui/react`. Use it for `Dropdown` (in `components/ui/`) and for `MobileMenu`'s `Dialog` (in `components/layout/`). Spec-level rule: **behavior primitives reach for Headless UI first, custom second.** Future tasks should use it for `Dialog` (modal confirmations), `Listbox` (custom selects), `Combobox` (autocomplete), `Disclosure` (accordion).

**Why:** Building accessible menus from scratch means reimplementing focus trap, escape, click-outside, keyboard nav, return-focus-to-trigger, ARIA roles, and type-ahead search. Mobile menus additionally need iOS scroll lock — a ~30-line `position: fixed` dance every team gets subtly wrong. Headless UI is maintained by Tailwind Labs, MIT, used by tens of thousands of projects, and has been solving this since 2020. One install, multiple primitives unlocked. ~10 KB minified+gzipped.

The `Dropdown` API stays array-based for this task (`items: DropdownItem[]`) with an inline comment in the file about the future `<Dropdown.Item>` compound API for dividers, group headers, and custom item content. The first task that needs dividers swaps in the compound API.

### 2.16 `aria-label` required in TypeScript type for `IconButton`

**Decision:** `IconButtonProps` declares `"aria-label": string` (required, not optional). The only way to ship an `IconButton` without an accessible name is to cast with `as any`, which is review-visible.

**Why:** Compile error > lint warning > review catch. Lint warnings get disabled inline or suppressed project-wide. Code review catches maybe 80% of missed labels; the 20% that slip through are in the codebase forever until someone runs an a11y audit. **A11y constraints belong in the type system wherever the compiler can enforce them.** Same principle applies to `Avatar.alt` (already required) and any future image-wrapping primitive.

### 2.17 `cn` helper as a baseline utility

**Decision:** Install `clsx` + `tailwind-merge`, build `src/lib/cn.ts` once, every primitive uses `cn(baseClasses, variantClasses, className)` for class composition.

**Why:** Without `tailwind-merge`, passing `className="p-8"` to a component whose base classes include `p-4` produces `"p-4 p-8"`, which Tailwind resolves to "whichever comes last in the generated CSS" — usually but not always the consumer's intent. `tailwind-merge` dedupes intelligently so the consumer's class wins. This is standard shadcn-derived practice; mention as a baseline, not a per-primitive decision.

### 2.18 Header has no border — uses scroll-aware shadow instead

**Decision:** Header is `bg-surface/80 backdrop-blur-md sticky top-0 z-50`. **No border.** Optional `shadow-soft` activated when `window.scrollY > 8` via inline `useState` + `useEffect`. Don't pre-extract `useScrolled()`.

**Why:** Invoking the `DESIGN.md §2` ghost-border exception on the most prominent floating element of the entire app on the foundation task sets the wrong precedent. Future contractors would see `border-outline-variant/15` on the header and internalize "ghost borders are fine" — and they'd start appearing on cards, modals, sidebars. The no-line rule survives by being unexceptional where possible.

Backdrop blur + translucent surface already creates a perceptible edge because content scrolling underneath is subtly distorted; that *is* the separator in a glassmorphism aesthetic. The scroll-aware shadow is the M3 §4 "Shadow as Structure" principle in action. If the second consumer of "scroll-aware lift" appears (sticky sidebars, sticky bid panels), the inline pattern gets extracted to `src/lib/hooks/useScrolled.ts` then.

### 2.19 NavLink consolidation with `usePathname` active state

**Decision:** Single `src/components/layout/NavLink.tsx` (client) takes `variant: "header" | "mobile"` and optional `onClick`. Uses `usePathname()` for active-route detection. Active state is `text-primary` + `aria-current="page"`; inactive is `text-on-surface-variant hover:text-on-surface`. Footer keeps its own local `FooterLink` helper because legal/site-map links are conceptually different (no active state, different typography).

**Why:** Active-route state ships now — adding it later never happens. The split between desktop and mobile NavLink in early drafts was duplication that CONVENTIONS' "props over duplication" rule explicitly forbids. Variant prop is the right answer.

### 2.20 Eleven placeholder routes

**Decision:** Eleven RSC placeholder pages: `/`, `/browse`, `/auction/[id]`, `/dashboard`, `/login`, `/register`, `/forgot-password`, `/about`, `/terms`, `/contact`, `/partners`. Each is ~5–15 lines, all RSC, all rendering `<PageHeader title="..." />`. No tests for placeholder pages individually.

**Why:** Original task brief lists seven; the four extras (`/about`, `/terms`, `/contact`, `/partners`) cover the footer links. "Click footer link → 404" is a bad first impression even in dev. Four extra files, zero logic, full coverage. Per-page tests would just be testing `PageHeader` 11 times.

### 2.21 Grep-based verification rules

**Decision:** Five mechanical rules enforced via `npm run verify`:

1. `verify:no-dark-variants` — `! grep -rEn 'dark:' src/components src/app`
2. `verify:no-hex-colors` — `! grep -rEn 'className.*#[0-9a-fA-F]{3,8}|style=.*#[0-9a-fA-F]{3,8}' src/components src/app`
3. `verify:no-inline-styles` — `! grep -rn 'style={' src/components src/app`
4. `verify:coverage` — `scripts/verify-coverage.sh` checks every `src/components/ui/*.tsx` (excluding `index` and `icons`) has a sibling `*.test.tsx`
5. `verify` — wraps all four; one command, one exit code

**Why:** Grep rules are the only mechanical enforcement of stylistic rules without writing custom ESLint plugins. Code review is bad at catching literal-match rules — a single `dark:` variant slipped into a 200-line PR at line 147 will pass review most days. The grep can't glaze over. Rules are also documentation: seeing the command in the spec is a crisper statement of "no `dark:` in components" than a paragraph of prose.

The hex regex is constrained to CSS-context (`className`/`style` proximity) rather than `#[0-9a-fA-F]{3,8}` to avoid false positives on URL fragments and JSDoc examples. False positives erode credibility, and the rule's value is its credibility.

---

## 3. File inventory & directory layout

### 3.1 Final directory tree

```
frontend/
  src/                              ← NEW (everything that ships moves here)
    app/
      providers.tsx                 ← NEW: <Providers> composing ThemeProvider + QueryClientProvider
      layout.tsx                    ← rewritten: Manrope font, Providers, AppShell
      page.tsx                      ← rewritten: landing placeholder
      globals.css                   ← rewritten: full M3 token block + type scale + .dark overrides
      browse/
        page.tsx                    ← NEW placeholder
      auction/
        [id]/
          page.tsx                  ← NEW dynamic placeholder
      dashboard/
        page.tsx                    ← NEW placeholder
      login/
        page.tsx                    ← NEW placeholder
      register/
        page.tsx                    ← NEW placeholder
      forgot-password/
        page.tsx                    ← NEW placeholder
      about/
        page.tsx                    ← NEW placeholder
      terms/
        page.tsx                    ← NEW placeholder
      contact/
        page.tsx                    ← NEW placeholder
      partners/
        page.tsx                    ← NEW placeholder
    components/
      ui/
        Button.tsx                  + Button.test.tsx
        IconButton.tsx              + IconButton.test.tsx
        Input.tsx                   + Input.test.tsx
        Card.tsx                    + Card.test.tsx
        StatusBadge.tsx             + StatusBadge.test.tsx
        Avatar.tsx                  + Avatar.test.tsx
        ThemeToggle.tsx             + ThemeToggle.test.tsx
        Dropdown.tsx                + Dropdown.test.tsx
        icons.ts                    ← lucide-react barrel
        index.ts                    ← public barrel
      layout/
        AppShell.tsx                + AppShell.test.tsx
        Header.tsx                  + Header.test.tsx
        MobileMenu.tsx              + MobileMenu.test.tsx
        Footer.tsx                  + Footer.test.tsx
        PageHeader.tsx              + PageHeader.test.tsx
        NavLink.tsx                 + NavLink.test.tsx
    lib/
      api.ts                        + api.test.ts
      auth.ts                       + auth.test.ts
      cn.ts                         + cn.test.ts
    test/
      render.tsx                    ← renderWithProviders helper (no test file)
  scripts/
    verify-coverage.sh              ← NEW: bash script for grep rule #4
  vitest.config.ts                  ← NEW
  vitest.setup.ts                   ← NEW
  package.json                      ← scripts + new deps
  tsconfig.json                     ← path alias @/* → src/*
  next.config.ts                    ← unchanged
  Dockerfile.dev                    ← unchanged (WORKDIR /app, source bind-mount stays valid)
```

### 3.2 Components/ folder rules

- **`components/auction/` and `components/user/`** are deliberately **not** created in this task. The first task that needs domain composites creates them, with a one-line `README.md` naming the convention ("auction-domain composites: ListingCard, BidPanel, CountdownTimer, ...").
- **No `components/providers/` folder.** Providers live at `src/app/providers.tsx`. If we ever need them split for testability, they go to `src/lib/providers/`, never `components/providers/`.

### 3.3 Server vs client boundaries

| Component                | Type   | Reason                                                                |
|--------------------------|--------|------------------------------------------------------------------------|
| `src/app/layout.tsx`     | RSC    | Server-side metadata, font, Providers mount.                           |
| `src/app/providers.tsx`  | client | `useState` for QueryClient, hooks (`useTheme` indirectly) consumed by descendants. |
| `AppShell`               | RSC    | Pure structural composition. No state, no hooks, no event handlers.   |
| `Header`                 | client | `useState` (mobile menu, scrolled), `useTheme` indirectly via `ThemeToggle`. |
| `Footer`                 | RSC    | Pure presentation; year via `new Date()` at request time.             |
| `MobileMenu`             | client | Headless UI `Dialog` state.                                           |
| `PageHeader`             | RSC    | Pure presentation; `actions` slot is a `ReactNode` so server can render client widgets inside it. |
| `NavLink`                | client | `usePathname()` for active-route state.                               |
| All page routes          | RSC    | Each is a thin compose of `<PageHeader />`. No client code.           |

### 3.4 Package additions

**New runtime dependencies:**

```
"dependencies": {
  "next": "16.2.3",                       // existing
  "react": "19.2.4",                      // existing
  "react-dom": "19.2.4",                  // existing
  "next-themes": "^0.4",                  // NEW
  "@tanstack/react-query": "^5",          // NEW
  "@headlessui/react": "^2",              // NEW
  "lucide-react": "^0.4xx",               // NEW (current stable at install time)
  "clsx": "^2",                           // NEW
  "tailwind-merge": "^3"                  // NEW
}
```

**New devDependencies:**

```
"devDependencies": {
  "@tailwindcss/postcss": "^4",           // existing
  "@types/node": "^20",                   // existing
  "@types/react": "^19",                  // existing
  "@types/react-dom": "^19",              // existing
  "eslint": "^9",                         // existing
  "eslint-config-next": "16.2.3",         // existing
  "tailwindcss": "^4",                    // existing
  "typescript": "^5",                     // existing
  "vitest": "^3",                         // NEW
  "@vitejs/plugin-react": "^5",           // NEW
  "@vitest/ui": "^3",                     // NEW
  "jsdom": "^25",                         // NEW
  "@testing-library/react": "^16",        // NEW
  "@testing-library/jest-dom": "^6",      // NEW
  "@testing-library/user-event": "^14"    // NEW
}
```

Six runtime, seven dev. Major versions shown; the implementation plan locks exact versions at install time.

### 3.5 New `package.json` scripts

```json
{
  "scripts": {
    "dev": "next dev",
    "build": "next build",
    "start": "next start",
    "lint": "eslint",
    "test": "vitest run",
    "test:watch": "vitest",
    "test:ui": "vitest --ui",
    "verify:no-dark-variants": "! grep -rEn 'dark:' src/components src/app",
    "verify:no-hex-colors": "! grep -rEn 'className.*#[0-9a-fA-F]{3,8}|style=.*#[0-9a-fA-F]{3,8}' src/components src/app",
    "verify:no-inline-styles": "! grep -rn 'style={' src/components src/app",
    "verify:coverage": "bash scripts/verify-coverage.sh",
    "verify": "npm run verify:no-dark-variants && npm run verify:no-hex-colors && npm run verify:no-inline-styles && npm run verify:coverage"
  }
}
```

### 3.6 `tsconfig.json` path alias

Add to `compilerOptions`:

```json
{
  "compilerOptions": {
    "baseUrl": ".",
    "paths": { "@/*": ["./src/*"] }
  }
}
```

---

## 4. Theme system

### 4.1 Color tokens — full `globals.css` block

The values below are extracted from `docs/stitch_generated-design/{light,dark}_mode/landing_page/code.html` and cross-checked against `DESIGN.md §2`. Every value is from the design reference; nothing is invented. The `.dark` block contains **only** the tokens that differ from light — tokens absent from `.dark` are intentionally shared between modes (mostly the `*-fixed` family and the tertiary container tokens).

```css
@import "tailwindcss";

@theme {
  /* ===== Surface family (light defaults) ===== */
  --color-background: #f7f9fb;
  --color-on-background: #191c1e;
  --color-surface: #f7f9fb;
  --color-on-surface: #191c1e;
  --color-on-surface-variant: #4e4638;
  --color-surface-tint: #7b5808;
  --color-surface-bright: #f7f9fb;
  --color-surface-dim: #d8dadc;
  --color-surface-variant: #e0e3e5;
  --color-surface-container-lowest: #ffffff;
  --color-surface-container-low: #f2f4f6;
  --color-surface-container: #eceef0;
  --color-surface-container-high: #e6e8ea;
  --color-surface-container-highest: #e0e3e5;

  /* ===== Primary (warm amber) ===== */
  --color-primary: #7b5808;
  --color-on-primary: #ffffff;
  --color-primary-container: #c99e4c;
  --color-on-primary-container: #4e3600;
  --color-primary-fixed: #ffdea7;
  --color-on-primary-fixed: #271900;
  --color-primary-fixed-dim: #eec06a;
  --color-on-primary-fixed-variant: #5e4200;

  /* ===== Secondary (cool slate) ===== */
  --color-secondary: #545f73;
  --color-on-secondary: #ffffff;
  --color-secondary-container: #d5e0f8;
  --color-on-secondary-container: #586377;
  --color-secondary-fixed: #d8e3fb;
  --color-on-secondary-fixed: #111c2d;
  --color-secondary-fixed-dim: #bcc7de;
  --color-on-secondary-fixed-variant: #3c475a;

  /* ===== Tertiary (cooler blue, "Active" status accents) ===== */
  --color-tertiary: #3f5f93;
  --color-on-tertiary: #ffffff;
  --color-tertiary-container: #86a5de;
  --color-on-tertiary-container: #163a6c;
  --color-tertiary-fixed: #d6e3ff;
  --color-on-tertiary-fixed: #001b3e;
  --color-tertiary-fixed-dim: #aac7ff;
  --color-on-tertiary-fixed-variant: #254779;

  /* ===== Error / "Ending Soon" status ===== */
  --color-error: #ba1a1a;
  --color-on-error: #ffffff;
  --color-error-container: #ffdad6;
  --color-on-error-container: #93000a;

  /* ===== Outline (used at low opacity, the no-line rule applies) ===== */
  --color-outline: #807666;
  --color-outline-variant: #d2c5b2;

  /* ===== Inverse (snackbars, tooltips, modal backdrops) ===== */
  --color-inverse-surface: #2d3133;
  --color-inverse-on-surface: #eff1f3;
  --color-inverse-primary: #eec06a;

  /* ===== Typography ===== */
  --font-sans: var(--font-manrope), system-ui, sans-serif;
  --font-display: var(--font-manrope), system-ui, sans-serif;
  --font-body: var(--font-manrope), system-ui, sans-serif;

  /* ===== M3 Type Scale (size / line-height / weight / tracking) ===== */
  /* px values are M3 standards; letter-spacing uses absolute px (M3 spec). */
  --text-display-lg: 3.5625rem;            /* 57px — flagged for revisit in Task 01-10 */
  --text-display-lg--line-height: 4rem;
  --text-display-lg--font-weight: 400;
  --text-display-lg--letter-spacing: -0.25px;
  --text-display-md: 2.8125rem;            /* 45px */
  --text-display-md--line-height: 3.25rem;
  --text-display-md--font-weight: 400;
  --text-display-md--letter-spacing: 0px;
  --text-display-sm: 2.25rem;              /* 36px */
  --text-display-sm--line-height: 2.75rem;
  --text-display-sm--font-weight: 400;
  --text-display-sm--letter-spacing: 0px;

  --text-headline-lg: 2rem;                /* 32px */
  --text-headline-lg--line-height: 2.5rem;
  --text-headline-lg--font-weight: 400;
  --text-headline-lg--letter-spacing: 0px;
  --text-headline-md: 1.75rem;             /* 28px */
  --text-headline-md--line-height: 2.25rem;
  --text-headline-md--font-weight: 400;
  --text-headline-md--letter-spacing: 0px;
  --text-headline-sm: 1.5rem;              /* 24px */
  --text-headline-sm--line-height: 2rem;
  --text-headline-sm--font-weight: 400;
  --text-headline-sm--letter-spacing: 0px;

  --text-title-lg: 1.375rem;               /* 22px */
  --text-title-lg--line-height: 1.75rem;
  --text-title-lg--font-weight: 400;
  --text-title-lg--letter-spacing: 0px;
  --text-title-md: 1rem;                   /* 16px */
  --text-title-md--line-height: 1.5rem;
  --text-title-md--font-weight: 500;
  --text-title-md--letter-spacing: 0.15px;
  --text-title-sm: 0.875rem;               /* 14px */
  --text-title-sm--line-height: 1.25rem;
  --text-title-sm--font-weight: 500;
  --text-title-sm--letter-spacing: 0.1px;

  --text-body-lg: 1rem;                    /* 16px */
  --text-body-lg--line-height: 1.5rem;
  --text-body-lg--font-weight: 400;
  --text-body-lg--letter-spacing: 0.5px;
  --text-body-md: 0.875rem;                /* 14px */
  --text-body-md--line-height: 1.25rem;
  --text-body-md--font-weight: 400;
  --text-body-md--letter-spacing: 0.25px;
  --text-body-sm: 0.75rem;                 /* 12px */
  --text-body-sm--line-height: 1rem;
  --text-body-sm--font-weight: 400;
  --text-body-sm--letter-spacing: 0.4px;

  --text-label-lg: 0.875rem;               /* 14px */
  --text-label-lg--line-height: 1.25rem;
  --text-label-lg--font-weight: 500;
  --text-label-lg--letter-spacing: 0.1px;
  --text-label-md: 0.75rem;                /* 12px */
  --text-label-md--line-height: 1rem;
  --text-label-md--font-weight: 500;
  --text-label-md--letter-spacing: 0.5px;
  --text-label-sm: 0.6875rem;              /* 11px */
  --text-label-sm--line-height: 1rem;
  --text-label-sm--font-weight: 500;
  --text-label-sm--letter-spacing: 0.5px;

  /* ===== Radius ===== */
  --radius-sm: 0.5rem;        /* 8px — chips, checkboxes, small icon buttons */
  --radius-default: 1rem;     /* 16px — buttons, inputs, cards (DESIGN.md "Round 4") */
  --radius-lg: 1.5rem;        /* 24px — card corners on hero images */
  --radius-full: 9999px;      /* pills, avatars */

  /* ===== Shadows (soft, low-intensity per DESIGN.md §4) ===== */
  --shadow-soft: 0 8px 24px rgba(25, 28, 30, 0.04);
  --shadow-elevated: 0 20px 40px rgba(25, 28, 30, 0.06);
}

.dark {
  /* Only the tokens that DIFFER from light. Tokens absent here are intentionally
     shared between modes (mostly the *-fixed family and tertiary container tokens). */

  --color-background: #121416;
  --color-on-background: #e2e2e5;
  --color-surface: #121416;
  --color-on-surface: #e2e2e5;
  --color-on-surface-variant: #d2c5b2;
  --color-surface-tint: #eec06a;
  --color-surface-bright: #37393b;
  --color-surface-dim: #121416;
  --color-surface-variant: #333537;
  --color-surface-container-lowest: #0c0e10;
  --color-surface-container-low: #1a1c1e;
  --color-surface-container: #1e2022;
  --color-surface-container-high: #282a2c;
  --color-surface-container-highest: #333537;

  --color-primary: #eec06a;
  --color-on-primary: #412d00;

  --color-secondary: #dcc39a;
  --color-on-secondary: #3d2e11;
  --color-secondary-container: #584627;
  --color-on-secondary-container: #ceb58d;
  --color-secondary-fixed: #fadfb4;
  --color-on-secondary-fixed: #261901;
  --color-secondary-fixed-dim: #dcc39a;
  --color-on-secondary-fixed-variant: #554425;

  --color-tertiary: #aac7ff;
  --color-on-tertiary: #053062;

  --color-error: #ffb4ab;
  --color-on-error: #690005;
  --color-error-container: #93000a;
  --color-on-error-container: #ffdad6;

  --color-outline: #9b8f7e;
  --color-outline-variant: #4e4638;

  --color-inverse-surface: #e2e2e5;
  --color-inverse-on-surface: #2f3133;
  --color-inverse-primary: #7b5808;
}
```

### 4.2 Tailwind utilities the token block generates

| CSS variable                          | Tailwind class             |
|---------------------------------------|----------------------------|
| `--color-surface`                     | `bg-surface`, `text-surface`, `border-surface`, `ring-surface` |
| `--color-surface-container-lowest`    | `bg-surface-container-lowest` (multi-segment names work in TW4) |
| `--font-sans`                         | `font-sans` (the workhorse)|
| `--font-display`, `--font-body`       | `font-display`, `font-body` (editorial aliases)              |
| `--text-headline-md`                  | `text-headline-md` (size + line-height + weight + tracking in one utility) |
| `--radius-default`                    | `rounded` (the default)    |
| `--radius-sm`                         | `rounded-sm`               |
| `--radius-lg`                         | `rounded-lg`               |
| `--radius-full`                       | `rounded-full`             |
| `--shadow-soft`                       | `shadow-soft`              |
| `--shadow-elevated`                   | `shadow-elevated`          |

---

## 5. Provider stack & root layout

### 5.1 `src/app/providers.tsx`

```tsx
"use client";

import { ThemeProvider } from "next-themes";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState, type ReactNode } from "react";

export function Providers({ children }: { children: ReactNode }) {
  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            staleTime: 60_000,             // 1 min — sane default; per-query override allowed
            refetchOnWindowFocus: false,   // real-time updates come via WebSocket later
            retry: 1,                      // one automatic retry, then surface to caller
          },
        },
      })
  );

  return (
    <ThemeProvider attribute="class" defaultTheme="dark" enableSystem>
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    </ThemeProvider>
  );
}
```

### 5.2 `src/app/layout.tsx`

```tsx
import type { Metadata } from "next";
import { Manrope } from "next/font/google";
import "./globals.css";
import { Providers } from "./providers";
import { AppShell } from "@/components/layout/AppShell";

const manrope = Manrope({
  subsets: ["latin"],
  display: "swap",
  variable: "--font-manrope",
  weight: ["300", "400", "500", "600", "700", "800"],
});

export const metadata: Metadata = {
  title: {
    default: "SLPA — Second Life Parcel Auctions",
    template: "%s · SLPA",
  },
  description: "Player-to-player land auctions for Second Life.",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en" className={manrope.variable} suppressHydrationWarning>
      <body className="min-h-screen font-sans bg-surface text-on-surface antialiased">
        <Providers>
          <AppShell>{children}</AppShell>
        </Providers>
      </body>
    </html>
  );
}
```

**Six font weights** (300/400/500/600/700/800) cover the entire M3 type scale: 300 for `body-md` light variants if used, 400 body, 500 `label-md`/`title-sm` accent, 600 `headline-sm`, 700 `headline-md`, 800 `display-md/lg`. `display: "swap"` for fast first paint.

**`metadata.title.template`** lets child pages set just `title: "Browse"` and the browser tab reads `Browse · SLPA`. One source of truth for branding.

**`suppressHydrationWarning`** on `<html>` is required because `next-themes` mutates the class before React hydrates. This is the official next-themes recommendation, not a hack.

---

## 6. `lib/` contracts

### 6.1 `src/lib/api.ts`

```ts
/**
 * RFC 7807 Problem Details. Matches the shape that
 * backend/src/main/java/.../common/GlobalExceptionHandler.java emits.
 *
 * `errors` is a SLPA extension for validation failures —
 * { fieldName: "must not be blank", ... } — populated by the
 * MethodArgumentNotValidException handler.
 */
export type ProblemDetail = {
  type?: string;
  title?: string;
  status: number;
  detail?: string;
  instance?: string;
  errors?: Record<string, string>;
  [key: string]: unknown;
};

/**
 * Thrown by every non-2xx response. Callers `try { } catch (e)` and
 * either rethrow, narrow with `isApiError(e)`, or read the normalized
 * `e.problem` to render field-level error messages.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly problem: ProblemDetail;

  constructor(problem: ProblemDetail) {
    super(problem.detail ?? problem.title ?? `HTTP ${problem.status}`);
    this.name = "ApiError";
    this.status = problem.status;
    this.problem = problem;
  }
}

/**
 * Type guard. Prefer this over `instanceof ApiError` across module
 * boundaries — bundler edge cases in RSC + client splits can produce
 * duplicate class identities, breaking instanceof checks.
 */
export function isApiError(e: unknown): e is ApiError {
  return e instanceof ApiError;
}

const BASE_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

type RequestOptions = Omit<RequestInit, "body"> & {
  body?: unknown;
  params?: Record<string, string | number | boolean | undefined>;
};

function buildPath(
  path: string,
  params?: RequestOptions["params"]
): string {
  if (!params) return path;
  const sp = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined) continue;
    sp.append(key, String(value));
  }
  const qs = sp.toString();
  return qs ? `${path}?${qs}` : path;
}

async function request<T>(
  path: string,
  { body, headers, params, ...rest }: RequestOptions = {}
): Promise<T> {
  const response = await fetch(`${BASE_URL}${buildPath(path, params)}`, {
    credentials: "include",
    ...rest,
    headers: {
      Accept: "application/json",
      ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
      ...headers,
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });

  if (!response.ok) {
    let problem: ProblemDetail;
    try {
      problem = (await response.json()) as ProblemDetail;
    } catch {
      // Backend returned a non-JSON error body (proxy 502, network blip).
      // Synthesize a minimal ProblemDetail so callers see a consistent shape.
      problem = { status: response.status, title: response.statusText };
    }
    throw new ApiError(problem);
  }

  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}

export const api = {
  get: <T>(path: string, options?: RequestOptions) =>
    request<T>(path, { ...options, method: "GET" }),
  post: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    request<T>(path, { ...options, method: "POST", body }),
  put: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    request<T>(path, { ...options, method: "PUT", body }),
  delete: <T>(path: string, options?: RequestOptions) =>
    request<T>(path, { ...options, method: "DELETE" }),
};
```

**Tests** (`src/lib/api.test.ts`, 5 cases):
1. Happy path — `api.get<T>` returns parsed JSON.
2. 4xx with ProblemDetail body — `ApiError.problem.errors` round-trips.
3. 5xx with non-JSON body — synthesized ProblemDetail has `status` and `title: response.statusText`.
4. 204 — returns `undefined`.
5. `params` helper — happy path encodes correctly, `undefined` values stripped.

Mock `fetch` via `vi.stubGlobal("fetch", vi.fn(...))`.

### 6.2 `src/lib/auth.ts`

```ts
/**
 * Stub auth hook. Returns an unauthenticated session.
 *
 * Replaced in Task 01-08 with a real JWT-backed implementation that
 * reads from localStorage and exposes login/logout mutations. Callers
 * MUST treat the return shape as the contract — do not add fields
 * here without updating the replacement in 01-08.
 */

export type AuthUser = {
  id: number;
  email: string;
  displayName: string;
  slAvatarUuid: string | null;
  verified: boolean;
};

export type AuthSession =
  | { status: "loading"; user: null }
  | { status: "authenticated"; user: AuthUser }
  | { status: "unauthenticated"; user: null };

export function useAuth(): AuthSession {
  return { status: "unauthenticated", user: null };
}
```

**Tests** (`src/lib/auth.test.ts`, 1 case):
1. Stub returns `{ status: "unauthenticated", user: null }`.

The test exists primarily as a regression canary — if Task 01-08's swap accidentally inverts the default, this test catches it.

### 6.3 `src/lib/cn.ts`

```ts
import { type ClassValue, clsx } from "clsx";
import { twMerge } from "tailwind-merge";

/**
 * Compose Tailwind class strings. Filters falsy values via clsx, then
 * dedupes conflicting Tailwind utilities via tailwind-merge so that
 * consumer-passed `className` always wins over component base classes.
 */
export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs));
}
```

**Tests** (`src/lib/cn.test.ts`, 2 cases):
1. **Merge dedup** — `cn("p-4", "p-8")` → `"p-8"` (consumer wins).
2. **Conditional truthy/falsy** — `cn("p-4", false && "bg-red-500", null, undefined, "text-on-surface")` → `"p-4 text-on-surface"`.

---

## 7. `components/ui/` primitives

Each subsection lists the prop signature, the key DESIGN.md rule the primitive enforces, the variant table where applicable, and the test plan. Implementation details (exact Tailwind classes for every state) belong in the implementation plan, not the spec — the spec locks the **interface**.

### 7.1 Button

```ts
type ButtonProps = {
  variant?: "primary" | "secondary" | "tertiary";  // default "primary"
  size?: "sm" | "md" | "lg";                       // default "md"
  loading?: boolean;
  leftIcon?: ReactNode;
  rightIcon?: ReactNode;
  fullWidth?: boolean;
  children: ReactNode;
} & Omit<ButtonHTMLAttributes<HTMLButtonElement>, "children">;
```

| Variant     | Visual treatment (DESIGN.md §5)                                                              |
|-------------|----------------------------------------------------------------------------------------------|
| `primary`   | Gradient `bg-gradient-to-br from-primary to-primary-container text-on-primary rounded-default`. Both endpoints are tokens; the gradient flips correctly in dark mode. |
| `secondary` | Ghost: `bg-surface-container-lowest text-on-surface shadow-soft`. No border (no-line rule); the soft shadow + tonal lift does the work. |
| `tertiary`  | Text-only: `text-primary` with `hover:underline underline-offset-4`. No background.          |

| Size | Type token       | Height | Horizontal padding |
|------|------------------|--------|--------------------|
| `sm` | `text-label-md`  | `h-9`  | `px-4`             |
| `md` | `text-label-lg`  | `h-11` | `px-5`             |
| `lg` | `text-title-md`  | `h-13` | `px-7`             |

- `loading` disables the button, swaps `leftIcon` for `<Loader2 className="animate-spin" />` from icons.ts, keeps the label visible (no layout shift).
- `forwardRef` so React Hook Form's `register` works without warnings.

**Tests** (6): each variant renders the right base classes; `loading` disables the button and renders the spinner; `leftIcon` and `rightIcon` render in the right slots; click handler fires; `fullWidth` adds `w-full`; consumer `className` overrides base via `cn`.

### 7.2 IconButton

```ts
type IconButtonProps = {
  variant?: "primary" | "secondary" | "tertiary";  // default "secondary"
  size?: "sm" | "md" | "lg";                       // default "md"
  "aria-label": string;                            // REQUIRED, not optional
  children: ReactNode;                             // the icon
} & Omit<ButtonHTMLAttributes<HTMLButtonElement>, "children" | "aria-label">;
```

- `aria-label` is **required in the TS type**, not lint-enforced. Casting with `as any` is the only escape and is review-visible.
- `rounded-full`, square footprint per size: `h-9 w-9` / `h-11 w-11` / `h-13 w-13`.
- `[&_svg]:stroke-[1.5]` baked into base classes — single source of stroke weight.

**Tests** (4): aria-label is on the rendered button; click handler fires; each variant renders correct base classes; rendered child SVG inherits the 1.5 stroke (assert via class presence on the wrapper).

### 7.3 Input

```ts
type InputProps = {
  label?: string;
  helperText?: string;
  error?: string;
  leftIcon?: ReactNode;
  rightIcon?: ReactNode;
  fullWidth?: boolean;
} & InputHTMLAttributes<HTMLInputElement>;
```

- **Default state** (DESIGN.md §5): `bg-surface-container-low text-on-surface placeholder:text-on-surface-variant rounded-default`.
- **Focus state**: transitions to `bg-surface-container-lowest ring-1 ring-primary` — the "glow" against the softer background. No outline, no border.
- `error` swaps `ring-primary` for `ring-error` and replaces `helperText` content with `error` text (`text-error text-body-sm`).
- `label` auto-wires `htmlFor` to a generated id if `id` isn't passed.
- `forwardRef` for RHF.

**Tests** (5): label associates with input via htmlFor/id; error state replaces helper text and applies error ring; leftIcon renders with correct padding offset; controlled usage works (`value` + `onChange`); uncontrolled usage works (`defaultValue`).

### 7.4 Card (compound)

```ts
function Card(props: HTMLAttributes<HTMLDivElement>): JSX.Element;
function CardHeader(props: HTMLAttributes<HTMLDivElement>): JSX.Element;
function CardBody(props: HTMLAttributes<HTMLDivElement>): JSX.Element;
function CardFooter(props: HTMLAttributes<HTMLDivElement>): JSX.Element;

Card.Header = CardHeader;
Card.Body = CardBody;
Card.Footer = CardFooter;

export { Card, CardHeader, CardBody, CardFooter };
```

- `Card`: `bg-surface-container-lowest rounded-default shadow-soft overflow-hidden`. **Zero borders** (DESIGN.md §2 no-line rule).
- `Card.Header`: `p-6 pb-4`.
- `Card.Body`: `px-6 py-4`.
- `Card.Footer`: `p-6 pt-4` typically right-aligned actions (consumer flexes).
- Sub-components also exported individually so tree-shaking works and compound usage isn't forced.

**Tests** (3): card renders children with default classes; header/body/footer slots render in order; consumer `className` merges with base via `cn`.

### 7.5 StatusBadge

```ts
type AuctionStatus = "active" | "ending-soon" | "ended" | "cancelled";
type Tone = "default" | "success" | "warning" | "danger";

type StatusBadgeProps = {
  status?: AuctionStatus;  // when set, provides default label + tone
  tone?: Tone;             // generic, used when status is not set
  children?: ReactNode;    // overrides default label from `status`
};
```

Short-circuit: `if (!status && !tone && !children) return null;` — never renders an empty pill.

| `status`      | Tokens                                                  | Default label   |
|---------------|----------------------------------------------------------|-----------------|
| `active`      | `bg-tertiary-container text-on-tertiary-container`       | "Active"        |
| `ending-soon` | `bg-error-container text-on-error-container`             | "Ending Soon"   |
| `ended`       | `bg-surface-container-high text-on-surface-variant`      | "Ended"         |
| `cancelled`   | `bg-surface-container-high text-on-surface-variant line-through` | "Cancelled" |

| `tone`     | Tokens                                                  |
|------------|----------------------------------------------------------|
| `default`  | `bg-surface-container-high text-on-surface-variant`      |
| `success`  | `bg-tertiary-container text-on-tertiary-container`       |
| `warning`  | `bg-secondary-container text-on-secondary-container`     |
| `danger`   | `bg-error-container text-on-error-container`             |

Shape: `rounded-full px-3 py-1 text-label-md font-medium inline-flex items-center gap-1.5`.

**Tests** (5): each status renders correct label + tone classes; each tone value renders correct classes; children override status label; default empty render returns null; `<StatusBadge status="active">12 bids</StatusBadge>` renders active palette with custom content.

### 7.6 Avatar

```ts
type AvatarProps = {
  src?: string;
  alt: string;          // required
  name?: string;        // for fallback initials when src is missing
  size?: "xs" | "sm" | "md" | "lg" | "xl";  // default "md"
};
```

| Size | Pixels |
|------|--------|
| `xs` | 24     |
| `sm` | 32     |
| `md` | 40     |
| `lg` | 56     |
| `xl` | 80     |

- **With `src`**: renders `next/image` with `width`/`height` set to the size (no `fill`, no wrapper gymnastics) and `className="rounded-full object-cover"`.
- **Without `src`**: renders initials fallback. `bg-tertiary-container text-on-tertiary-container font-semibold rounded-full`. Initials = first letter of first two whitespace-separated parts of `name`, uppercased (`"Heath Barcus"` → `"HB"`, `"Heath"` → `"H"`, missing → `"?"`).

**Tests** (4): src renders next/image; missing src renders initials from name; missing name renders `"?"`; each size applies correct dimensions.

### 7.7 ThemeToggle

```tsx
"use client";
export function ThemeToggle(): JSX.Element | null;
```

- Uses `useTheme()` from `next-themes`.
- **Mounted gate**: `useEffect` sets a `mounted` state; before mount, returns `null` to avoid hydration mismatch on the icon.
- Composes `<IconButton aria-label="Toggle theme" onClick={...}>{resolvedTheme === "dark" ? <Sun /> : <Moon />}</IconButton>`.
- No props.

**Tests** (3): clicks call `setTheme("light")` when current is dark; clicks call `setTheme("dark")` when current is light; renders sun icon in dark mode and moon icon in light mode (this test passes a real, non-forced `theme` to `renderWithProviders`, then asserts `documentElement.classList` flips on click — the integration test that motivated the `forceTheme` opt-in).

### 7.8 Dropdown

Built on Headless UI's `<Menu>` primitive (focus trap, escape, click-outside, keyboard nav, ARIA — all free).

```ts
type DropdownItem = {
  label: string;
  onSelect: () => void;
  icon?: ReactNode;
  disabled?: boolean;
  danger?: boolean;  // renders in error tone
};

type DropdownProps = {
  trigger: ReactNode;             // typically an IconButton or Avatar
  items: DropdownItem[];
  align?: "start" | "end";        // default "end" (right-align under trigger)
  className?: string;
};

// Inline comment in the file:
// For simple menus. When we need dividers, group headers, or custom item content,
// switch to a `children`-based API with <Dropdown.Item> subcomponents.
// Headless UI primitives support both patterns.
```

- The `trigger` is a slot; consumer passes their own `<IconButton>` or `<Avatar>`.
- **Floating panel**: `bg-surface-container-lowest rounded-default shadow-elevated p-2`. Items: `text-body-md text-on-surface px-3 py-2 rounded-sm hover:bg-surface-container`.

**Tests** (5): clicking trigger opens the menu; escape closes it; item click fires `onSelect` and closes; disabled items don't fire `onSelect`; danger items render with `text-error`.

### 7.9 `icons.ts` barrel

```ts
// src/components/ui/icons.ts
export {
  Sun,
  Moon,
  Bell,
  Search,
  Menu as MenuIcon,    // renamed to avoid colliding with future `Menu` primitive
  X,
  Check,
  ChevronDown,
  ChevronRight,
  ChevronLeft,
  ChevronUp,
  Eye,
  EyeOff,
  User,
  LogOut,
  Settings,
  Loader2,             // Button loading spinner
} from "lucide-react";
```

Add icons as components consume them. **No `Icon` wrapper component, no `<Icon name="sun" />` string lookup.**

### 7.10 `index.ts` public barrel

```ts
// src/components/ui/index.ts
export { Button } from "./Button";
export { IconButton } from "./IconButton";
export { Input } from "./Input";
export { Card, CardHeader, CardBody, CardFooter } from "./Card";
export { StatusBadge } from "./StatusBadge";
export { Avatar } from "./Avatar";
export { ThemeToggle } from "./ThemeToggle";
export { Dropdown } from "./Dropdown";
export * from "./icons";
```

`ProblemDetail` and `ApiError` are **not** re-exported here — they live in `@/lib/api` and consumers import them from there. The UI barrel is for UI surface only.

---

## 8. `components/layout/` shell + routing

### 8.1 AppShell (RSC)

```tsx
// src/components/layout/AppShell.tsx
import { Header } from "./Header";
import { Footer } from "./Footer";

export function AppShell({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex min-h-screen flex-col">
      <Header />
      <main className="flex-1">{children}</main>
      <Footer />
    </div>
  );
}
```

**Tests** (1): renders Header, main with children, Footer in order.

### 8.2 Header (client)

Glassmorphism + scroll-aware shadow + auth-state-aware right cluster + mobile hamburger.

Key structural decisions:

- **Sticky** (`sticky top-0 z-50`) — header floats above content as user scrolls.
- **Glassmorphism**: `bg-surface/80 backdrop-blur-md`. **No border.** Backdrop blur creates the visual edge.
- **Scroll-aware shadow**: inline `useState` + `useEffect` listening to `window.scrollY > 8`, adds `shadow-soft` when scrolled. Don't pre-extract `useScrolled()`; extract when a second consumer appears.
- **Logo wordmark**: `text-primary font-display font-black uppercase tracking-wider text-xl` — amber accent reads correctly in both modes via token flip.
- **Desktop nav** (`hidden md:flex`): `<NavLink variant="header">` × 3 — Browse, Dashboard, Create Listing.
- **Right cluster order**: `ThemeToggle` → notification bell `<IconButton>` (placeholder, no menu wired yet) → auth zone → mobile hamburger (`md:hidden`).
- **Auth zone**: branches on `useAuth()`'s status:
  - `loading` → returns `null` (skeleton in 01-08; for now an invisible placeholder is fine).
  - `authenticated` → `<Dropdown trigger={<Avatar name={user.displayName} alt="Account menu" size="sm" />} items={...} />`.
  - `unauthenticated` → `<div className="hidden md:flex items-center gap-2"><Link href="/login"><Button variant="tertiary" size="sm">Sign in</Button></Link><Link href="/register"><Button variant="primary" size="sm">Register</Button></Link></div>`.

```tsx
"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { Bell, MenuIcon } from "@/components/ui/icons";
import {
  Avatar,
  Button,
  Dropdown,
  IconButton,
  ThemeToggle,
} from "@/components/ui";
import { useAuth } from "@/lib/auth";
import { cn } from "@/lib/cn";
import { MobileMenu } from "./MobileMenu";
import { NavLink } from "./NavLink";

export function Header() {
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const [scrolled, setScrolled] = useState(false);
  const { status, user } = useAuth();

  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 8);
    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => window.removeEventListener("scroll", onScroll);
  }, []);

  return (
    <>
      <header
        className={cn(
          "sticky top-0 z-50 bg-surface/80 backdrop-blur-md transition-shadow",
          scrolled && "shadow-soft"
        )}
      >
        <div className="mx-auto flex h-16 max-w-7xl items-center justify-between px-6">
          <Link
            href="/"
            className="font-display text-xl font-black uppercase tracking-wider text-primary"
          >
            SLPA
          </Link>

          <nav className="hidden md:flex items-center gap-8">
            <NavLink variant="header" href="/browse">Browse</NavLink>
            <NavLink variant="header" href="/dashboard">Dashboard</NavLink>
            <NavLink variant="header" href="/auction/new">Create Listing</NavLink>
          </nav>

          <div className="flex items-center gap-2">
            <ThemeToggle />
            <IconButton aria-label="Notifications" variant="tertiary">
              <Bell />
            </IconButton>

            {status === "loading" ? null : status === "authenticated" ? (
              <Dropdown
                trigger={
                  <Avatar name={user.displayName} alt="Account menu" size="sm" />
                }
                items={[
                  { label: "Profile", onSelect: () => {} },
                  { label: "Settings", onSelect: () => {} },
                  { label: "Sign out", onSelect: () => {}, danger: true },
                ]}
              />
            ) : (
              <div className="hidden md:flex items-center gap-2">
                <Link href="/login">
                  <Button variant="tertiary" size="sm">Sign in</Button>
                </Link>
                <Link href="/register">
                  <Button variant="primary" size="sm">Register</Button>
                </Link>
              </div>
            )}

            <IconButton
              aria-label="Open menu"
              variant="tertiary"
              className="md:hidden"
              onClick={() => setMobileMenuOpen(true)}
            >
              <MenuIcon />
            </IconButton>
          </div>
        </div>
      </header>

      <MobileMenu open={mobileMenuOpen} onClose={() => setMobileMenuOpen(false)} />
    </>
  );
}
```

**Tests** (5): desktop NavLinks render and route correctly (assert `href`); auth-unauthenticated renders Sign in + Register; clicking hamburger opens MobileMenu (assert Dialog in DOM); ThemeToggle present; auth-authenticated renders avatar+dropdown (mock `useAuth` to return authenticated session).

### 8.3 NavLink (client)

```tsx
// src/components/layout/NavLink.tsx
"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";
import { cn } from "@/lib/cn";

type NavLinkProps = {
  href: string;
  variant: "header" | "mobile";
  onClick?: () => void;
  children: ReactNode;
};

export function NavLink({ href, variant, onClick, children }: NavLinkProps) {
  const pathname = usePathname();
  const isActive =
    pathname === href || (href !== "/" && pathname.startsWith(href));

  const base =
    variant === "header"
      ? "text-body-md transition-colors"
      : "text-headline-sm transition-colors";

  const state = isActive
    ? "text-primary"
    : "text-on-surface-variant hover:text-on-surface";

  return (
    <Link
      href={href}
      onClick={onClick}
      aria-current={isActive ? "page" : undefined}
      className={cn(base, state)}
    >
      {children}
    </Link>
  );
}
```

**Tests** (4): renders link with correct href; active state applies `text-primary` and `aria-current="page"` when pathname matches (mock `usePathname`); inactive state when pathname doesn't match; `onClick` fires when provided (mobile variant uses this to close the drawer).

### 8.4 MobileMenu (client)

Slide-in drawer from the right edge, built on Headless UI `Dialog`.

```tsx
"use client";

import { Dialog, DialogPanel } from "@headlessui/react";
import Link from "next/link";
import { X } from "@/components/ui/icons";
import { Button, IconButton } from "@/components/ui";
import { NavLink } from "./NavLink";

type MobileMenuProps = {
  open: boolean;
  onClose: () => void;
};

export function MobileMenu({ open, onClose }: MobileMenuProps) {
  return (
    <Dialog open={open} onClose={onClose} className="md:hidden relative z-50">
      <div
        className="fixed inset-0 bg-inverse-surface/40 backdrop-blur-sm"
        aria-hidden="true"
      />
      <div className="fixed inset-y-0 right-0 flex w-full max-w-sm">
        <DialogPanel className="flex w-full flex-col bg-surface-container-low p-6">
          <div className="flex justify-end">
            <IconButton aria-label="Close menu" variant="tertiary" onClick={onClose}>
              <X />
            </IconButton>
          </div>

          <nav className="mt-8 flex flex-col gap-6">
            <NavLink variant="mobile" href="/browse" onClick={onClose}>Browse</NavLink>
            <NavLink variant="mobile" href="/dashboard" onClick={onClose}>Dashboard</NavLink>
            <NavLink variant="mobile" href="/auction/new" onClick={onClose}>Create Listing</NavLink>
          </nav>

          <div className="mt-auto flex flex-col gap-3">
            <Link href="/login"><Button variant="tertiary" fullWidth>Sign in</Button></Link>
            <Link href="/register"><Button variant="primary" fullWidth>Register</Button></Link>
          </div>
        </DialogPanel>
      </div>
    </Dialog>
  );
}
```

Backdrop is `bg-inverse-surface/40` (M3-correct token for overlays), not `bg-on-surface/40` (text token; semantic abuse).

**Tests** (4): opens when `open=true`; escape calls `onClose` (Headless UI handles this); link click calls `onClose`; close button calls `onClose`.

### 8.5 Footer (RSC)

```tsx
// src/components/layout/Footer.tsx
import Link from "next/link";

export function Footer() {
  return (
    <footer className="bg-surface-container-low">
      <div className="mx-auto max-w-7xl px-6 py-12">
        <div className="flex flex-col gap-8 md:flex-row md:items-center md:justify-between">
          <div className="flex flex-wrap gap-x-6 gap-y-2">
            <FooterLink href="/about">About</FooterLink>
            <FooterLink href="/terms">Terms</FooterLink>
            <FooterLink href="/contact">Contact</FooterLink>
            <FooterLink href="/partners">Partners</FooterLink>
          </div>
          <p className="text-body-sm text-on-surface-variant">
            © {new Date().getFullYear()} SLPA. Not affiliated with Linden Lab.
          </p>
        </div>
      </div>
    </footer>
  );
}

function FooterLink({ href, children }: { href: string; children: React.ReactNode }) {
  return (
    <Link
      href={href}
      className="text-body-sm text-on-surface-variant hover:text-on-surface transition-colors"
    >
      {children}
    </Link>
  );
}
```

The `bg-surface-container-low` background shift against the page's `bg-surface` *is* the visual separator — DESIGN.md §2 no-line rule in action. **No `border-t`.** The footer year via `new Date().getFullYear()` runs on every server render, which is fine — micro-optimization not warranted.

**Tests** (2): all four links render with correct href; current year appears in copyright text.

### 8.6 PageHeader (RSC)

```tsx
// src/components/layout/PageHeader.tsx
import Link from "next/link";
import { ChevronRight } from "@/components/ui/icons";

type Breadcrumb = { label: string; href?: string };

type PageHeaderProps = {
  title: string;
  subtitle?: string;
  actions?: React.ReactNode;
  breadcrumbs?: Breadcrumb[];
};

export function PageHeader({ title, subtitle, actions, breadcrumbs }: PageHeaderProps) {
  return (
    <div className="mx-auto max-w-7xl px-6 pt-12 pb-8">
      {breadcrumbs && breadcrumbs.length > 0 && (
        <nav aria-label="Breadcrumb" className="mb-4">
          <ol className="flex items-center gap-2 text-body-sm text-on-surface-variant">
            {breadcrumbs.map((crumb, i) => (
              <li key={i} className="flex items-center gap-2">
                {i > 0 && <ChevronRight className="size-4" />}
                {crumb.href ? (
                  <Link href={crumb.href} className="hover:text-on-surface transition-colors">
                    {crumb.label}
                  </Link>
                ) : (
                  <span aria-current="page">{crumb.label}</span>
                )}
              </li>
            ))}
          </ol>
        </nav>
      )}

      <div className="flex flex-col gap-4 md:flex-row md:items-end md:justify-between">
        <div>
          <h1 className="text-display-md text-on-surface">{title}</h1>
          {subtitle && (
            <p className="mt-2 text-body-lg text-on-surface-variant">{subtitle}</p>
          )}
        </div>
        {actions && <div className="flex items-center gap-3">{actions}</div>}
      </div>
    </div>
  );
}
```

`actions` is a `ReactNode` slot so a server `PageHeader` can render a client `<Button>` inside without becoming client itself.

**Tests** (5): h1 renders title; subtitle conditional; actions slot conditional; breadcrumbs render with `ChevronRight` separators except before the first item; last breadcrumb without `href` gets `aria-current="page"`.

### 8.7 Routing — eleven placeholder pages

Each is a thin RSC, 5–15 lines, no client code. All use `<PageHeader />`.

```tsx
// src/app/page.tsx
import { PageHeader } from "@/components/layout/PageHeader";

export default function HomePage() {
  return (
    <PageHeader
      title="SLPA"
      subtitle="Player-to-player land auctions for Second Life — coming soon."
    />
  );
}
```

```tsx
// src/app/browse/page.tsx
import type { Metadata } from "next";
import { PageHeader } from "@/components/layout/PageHeader";

export const metadata: Metadata = { title: "Browse Auctions" };

export default function BrowsePage() {
  return (
    <PageHeader
      title="Browse Auctions"
      subtitle="Active land listings across the grid."
    />
  );
}
```

```tsx
// src/app/auction/[id]/page.tsx
import type { Metadata } from "next";
import { PageHeader } from "@/components/layout/PageHeader";

export const metadata: Metadata = { title: "Auction" };

export default async function AuctionPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  return (
    <PageHeader
      title={`Auction #${id}`}
      breadcrumbs={[
        { label: "Browse", href: "/browse" },
        { label: `Auction #${id}` },
      ]}
    />
  );
}
```

The remaining seven (`/dashboard`, `/login`, `/register`, `/forgot-password`, `/about`, `/terms`, `/contact`, `/partners`) follow the same shape — `metadata.title`, RSC, single `<PageHeader />`.

**Note for the contractor:** Next.js 16 makes dynamic-route `params` a `Promise`. You must `await` it before destructuring. This is a 15→16 mental-model break that bites anyone whose model is "params is just an object." See Gotchas section.

---

## 9. Testing infrastructure & test plan

### 9.1 `frontend/vitest.config.ts`

```ts
import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import { fileURLToPath } from "url";

export default defineConfig({
  plugins: [react()],
  resolve: {
    // MUST mirror tsconfig.json `paths`. Vitest does not read tsconfig
    // automatically, so the alias has to be declared in both places.
    // See spec doc → "Gotchas" → "@/* alias dual-resolver footgun".
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: "./vitest.setup.ts",
    globals: false,                                    // explicit imports only
    css: true,                                         // process Tailwind so class-based assertions work
    exclude: ["node_modules", ".next", "dist", "e2e/**"],
    include: ["src/**/*.{test,spec}.{ts,tsx}"],
  },
});
```

### 9.2 `frontend/vitest.setup.ts`

```ts
import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach, vi } from "vitest";

// next/font/google only resolves inside the Next build pipeline. Tests that
// transitively import app/layout.tsx (or anything that imports Manrope)
// blow up without this mock.
vi.mock("next/font/google", () => ({
  Manrope: () => ({
    className: "font-manrope",
    variable: "--font-manrope",
  }),
}));

// next/navigation hooks only work inside a real Next request context.
// NavLink uses usePathname; mock it once globally and let individual
// tests override per-case via `vi.mocked(usePathname).mockReturnValue(...)`.
vi.mock("next/navigation", () => ({
  usePathname: () => "/",
  useRouter: () => ({
    push: vi.fn(),
    replace: vi.fn(),
    refresh: vi.fn(),
    back: vi.fn(),
    forward: vi.fn(),
    prefetch: vi.fn(),
  }),
  useSearchParams: () => new URLSearchParams(),
}));

// Without globals:false-friendly automatic cleanup, RTL leaves the previous
// test's DOM in place and the next test gets stale nodes. Manual cleanup is
// the price of explicit imports.
afterEach(() => {
  cleanup();
});
```

### 9.3 `frontend/src/test/render.tsx`

```tsx
import { render, type RenderOptions } from "@testing-library/react";
import { ThemeProvider } from "next-themes";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactElement, ReactNode } from "react";

type RenderWithProvidersOptions = Omit<RenderOptions, "wrapper"> & {
  /** Initial theme. Default "light" for snapshot stability. */
  theme?: "light" | "dark";
  /**
   * If true, locks the theme via `forcedTheme` so `setTheme()` becomes
   * a no-op. Defaults to false so tests can observe theme transitions
   * (e.g., the ThemeToggle integration test).
   */
  forceTheme?: boolean;
};

function makeWrapper(theme: "light" | "dark", force: boolean) {
  return function Wrapper({ children }: { children: ReactNode }) {
    // Fresh QueryClient per test. Sharing across tests leaks query state
    // between specs and surfaces as flaky failures weeks later.
    const queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    });

    return (
      <ThemeProvider
        attribute="class"
        defaultTheme={theme}
        enableSystem={false}
        forcedTheme={force ? theme : undefined}
      >
        <QueryClientProvider client={queryClient}>
          {children}
        </QueryClientProvider>
      </ThemeProvider>
    );
  };
}

export function renderWithProviders(
  ui: ReactElement,
  { theme = "light", forceTheme = false, ...options }: RenderWithProvidersOptions = {}
) {
  return render(ui, { wrapper: makeWrapper(theme, forceTheme), ...options });
}

// Re-export RTL utilities so test files only import from one place.
export { screen, within, fireEvent, waitFor } from "@testing-library/react";
export { default as userEvent } from "@testing-library/user-event";
```

**Test rule**: every test file imports from `@/test/render`, never directly from `@testing-library/react`. This prevents the "first test forgets to wrap in providers and gets a confusing `useTheme` error" failure mode.

### 9.4 Test plan summary

| Module                | File                                                       | Cases |
|-----------------------|------------------------------------------------------------|-------|
| `lib/api.ts`          | `src/lib/api.test.ts`                                      | 5     |
| `lib/auth.ts`         | `src/lib/auth.test.ts`                                     | 1     |
| `lib/cn.ts`           | `src/lib/cn.test.ts`                                       | 2     |
| `Button`              | `src/components/ui/Button.test.tsx`                        | 6     |
| `IconButton`          | `src/components/ui/IconButton.test.tsx`                    | 4     |
| `Input`               | `src/components/ui/Input.test.tsx`                         | 5     |
| `Card`                | `src/components/ui/Card.test.tsx`                          | 3     |
| `StatusBadge`         | `src/components/ui/StatusBadge.test.tsx`                   | 5     |
| `Avatar`              | `src/components/ui/Avatar.test.tsx`                        | 4     |
| `ThemeToggle`         | `src/components/ui/ThemeToggle.test.tsx`                   | 3     |
| `Dropdown`            | `src/components/ui/Dropdown.test.tsx`                      | 5     |
| `AppShell`            | `src/components/layout/AppShell.test.tsx`                  | 1     |
| `Header`              | `src/components/layout/Header.test.tsx`                    | 5     |
| `MobileMenu`          | `src/components/layout/MobileMenu.test.tsx`                | 4     |
| `Footer`              | `src/components/layout/Footer.test.tsx`                    | 2     |
| `PageHeader`          | `src/components/layout/PageHeader.test.tsx`                | 5     |
| `NavLink`             | `src/components/layout/NavLink.test.tsx`                   | 4     |
| **Total**             |                                                            | **64**|

Expected runtime: ~2 seconds cold, sub-second in watch mode. If runtime ever crosses ~10 seconds, revisit happy-dom (the option B from question 4 we deliberately deferred). Filtered runs via `npm run test -- Button` keep the dev loop tight when working on a single primitive.

### 9.5 Testing principles (in spec order)

1. **Test the contract, not the implementation.** Assert that the right semantic class is *applied* (`bg-primary`, `text-on-surface`), not that Tailwind compiled it to a specific hex. Assert that handlers fire with the right argument, not how the component manages internal state.
2. **Test the integration where it's load-bearing.** `ThemeToggle` is the one place where mocking out next-themes would lose the test's value — its integration test must observe an actual class flip on `documentElement`.
3. **Don't test libraries.** We don't test that `next-themes` persists to localStorage (its job), that Headless UI's focus trap works (its job), or that Tailwind generates correct CSS (its job). We test that *our* code calls them correctly.
4. **Per-test mock reset.** Tests that override a global mock (`vi.mocked(usePathname).mockReturnValue("/browse")`) must reset in `beforeEach` so state doesn't leak:
   ```ts
   import { usePathname } from "next/navigation";
   import { vi, beforeEach, describe, it } from "vitest";

   const mockedUsePathname = vi.mocked(usePathname);

   describe("NavLink", () => {
     beforeEach(() => {
       mockedUsePathname.mockReset();
       mockedUsePathname.mockReturnValue("/");
     });
     // ...
   });
   ```

### 9.6 What we deliberately do NOT test

- **Placeholder pages individually.** Each is `<PageHeader title="..." />` with metadata. Testing them is testing PageHeader twice. The acceptance criterion that "every nav link routes" is verified manually via the smoke test.
- **Tailwind class output specifics.** We assert that the right semantic class is *applied*, not the compiled hex.
- **next-themes' own behavior.** We test our `ThemeToggle` calls `setTheme` correctly, not that next-themes persists to localStorage.
- **Headless UI's own a11y machinery.** We test our `Dropdown` opens/closes/fires onSelect, not that Headless UI's focus trap works.

---

## 10. Verification & "done"

### 10.1 `scripts/verify-coverage.sh`

```bash
#!/usr/bin/env bash
# Fails (exit 1) if any src/components/ui/*.tsx is missing a sibling .test.tsx.
# Excludes barrel files (index, icons) which are not testable on their own.

set -euo pipefail

missing=0
for f in src/components/ui/*.tsx; do
  base="$(basename "$f" .tsx)"
  case "$base" in
    index|icons) continue ;;
  esac
  if [[ ! -f "src/components/ui/${base}.test.tsx" ]]; then
    echo "MISSING TEST: $f"
    missing=1
  fi
done

if [[ "$missing" -eq 1 ]]; then
  exit 1
fi
echo "All UI primitives have sibling test files."
```

`chmod +x scripts/verify-coverage.sh` so `npm run verify:coverage` can execute it.

### 10.2 What "done" looks like

A reviewer or contractor can verify task completion by running:

1. **`npm run test`** — all 64 cases green.
2. **`npm run lint`** — no errors.
3. **`npm run build`** — completes without warnings.
4. **`npm run verify`** — passes all five grep rules:
   - No `dark:` strings in `src/components` or `src/app`.
   - No hex colors inside `className` or `style` attributes in `src/components` or `src/app`.
   - No inline `style={...}` props in `src/components` or `src/app`.
   - Every `src/components/ui/*.tsx` (excluding barrels) has a sibling `*.test.tsx`.
5. **Manual smoke test** (Part B below) — all steps pass in both modes.
6. **Root README sweep** (per `feedback_update_readme_each_task` memory) — root README's frontend section gets a one-paragraph update mentioning `npm run test`, `npm run verify`, and the shape of the component library.

### 10.3 Manual smoke test (paste into PR description)

```
[ ] cd frontend && npm run dev
[ ] Open http://localhost:3000 — landing placeholder renders with "SLPA" wordmark
[ ] Confirm dark mode is the default on first visit, no theme flash on initial paint
[ ] Toggle to light mode via header toggle
[ ] Refresh — light mode persists
[ ] Click Browse in desktop nav — routes to /browse, PageHeader renders
[ ] Click Dashboard — routes to /dashboard
[ ] Click Create Listing — routes to /auction/new (placeholder)
[ ] Click each footer link (About, Terms, Contact, Partners) — each routes
[ ] Open /auction/42 directly — breadcrumbs render with Browse → Auction #42
[ ] Resize viewport below md (768px) — hamburger appears
[ ] Click hamburger — drawer slides in, focus trapped
[ ] Press Escape — drawer closes, focus returns to hamburger
[ ] Open drawer again, click a nav link — drawer closes and route changes
[ ] Open browser dev tools → Application → Local Storage — confirm `theme` key persists
[ ] Repeat all steps in light mode
```

### 10.4 The grep rules as documentation

The grep rules are also documentation. When a contractor reads the spec, seeing
```
grep -rn "dark:" src/components src/app  →  expected empty output
```
is a crisper statement of the rule than a paragraph of prose. There's no wiggle room on "does my usage count as a dark variant?"

---

## 11. Gotchas reference

A catalog of footguns and their workarounds. Keep these handy when implementing.

### 11.1 `@/*` alias dual-resolver footgun

Vitest does **not** read `tsconfig.json` paths. Tests fail to resolve `@/components/...` imports unless the alias is declared in `vitest.config.ts` `resolve.alias` as well. Both files in section 9.1 + section 3.6.

### 11.2 `next/font/google` blows up jsdom

`next/font/google` only resolves inside the Next build pipeline. Any test that transitively imports `app/layout.tsx` blows up without the global mock in `vitest.setup.ts`. This is the most common "why is my test crashing on import?" failure for new Next + Vitest setups. Mock is in section 9.2.

### 11.3 `next/navigation` requires a request context

`usePathname`, `useRouter`, `useSearchParams` only work inside a real Next request context. `NavLink` uses `usePathname`, which means any test that goes through `<Header>` or `<MobileMenu>` transitively needs the mock. Global mock in `vitest.setup.ts`; per-test override via `vi.mocked(usePathname).mockReturnValue(...)` inside `beforeEach`.

### 11.4 Shared `QueryClient` flake

Sharing one `QueryClient` across tests means one test's cached queries leak into the next, surfacing as flaky failures weeks later with no obvious cause. `renderWithProviders` constructs a fresh client per test. Don't optimize this away.

### 11.5 `forcedTheme` is off-label for general test usage

`forcedTheme` is designed for "this page is always light mode" edge cases (documentation sites). Using it as a default in tests breaks the one test that legitimately needs to observe a theme transition (`ThemeToggle.test.tsx`). The opt-in `forceTheme` flag in `renderWithProviders` is the right tradeoff.

### 11.6 `enableSystem={false}` in tests

jsdom's `matchMedia` is a stub. Letting `next-themes` run system detection in tests is asking for nondeterminism. Always pass `enableSystem={false}` in test renders. (`renderWithProviders` does this.)

### 11.7 Module-level `QueryClient` in App Router

Constructing `QueryClient` at module scope (`const queryClient = new QueryClient(...)`) shares state across requests on the server. Always construct inside `useState(() => new QueryClient(...))` in a client component. Section 5.1.

### 11.8 Next.js 16 `params` is a Promise

Dynamic-route `params` is `Promise<{...}>` in Next 16 and must be `await`ed. Not destructurable directly. Section 8.7 example.

### 11.9 The header border that isn't there

The Header has **no border**. It uses `bg-surface/80 backdrop-blur-md` + scroll-aware `shadow-soft`. Do not add `border-b` "for definition" — that violates the no-line rule and cracks the foundation. Section 8.2.

### 11.10 `instanceof ApiError` across module boundaries

Bundler edge cases in RSC + client splits can produce duplicate class identities, breaking `instanceof ApiError` checks. Use the `isApiError` type guard from `@/lib/api`. Section 6.1.

### 11.11 `tailwind-merge` is the reason `cn` exists

Without `tailwind-merge`, passing `className="p-8"` to a primitive whose base classes include `p-4` produces `"p-4 p-8"` and Tailwind resolves to "whichever comes last in the generated CSS." `tailwind-merge` dedupes intelligently so consumer classes always win. Use `cn()` everywhere.

### 11.12 Stripping `@variant dark` is a feature

The `@variant dark (.dark &);` directive is **deliberately omitted** from `globals.css`. If the directive existed, `dark:bg-red-500` would compile to a working class and a single PR slipping through review would crack the no-`dark:` rule. Without the directive, `dark:bg-red-500` is parseable but produces no CSS — the failure teaches the rule.

---

## 12. Explicit non-goals

This table is the spec template for "not in scope, owned elsewhere." Future task specs should end with one. The point isn't bureaucracy — it's that "is this in scope?" has a definitive answer for every adjacent concern.

| Out of scope                                                  | Owned by                                     |
|----------------------------------------------------------------|---------------------------------------------|
| Real authentication, JWT handling, login/register form logic   | Task 01-07 (backend) + Task 01-08 (frontend) |
| Actual API calls from any component                            | Each domain task as it lands                 |
| TanStack Query devtools                                        | Task 01-08 (alongside first real `useQuery`)|
| Real landing page content, hero, marketing copy                | Task 01-10                                   |
| Real browse page (auction grid, filters, search)               | Phase 2                                      |
| Real auction detail page (bid panel, history, parcel viewer)   | Phase 2 / Phase 3                            |
| Real dashboard (My Bids, My Listings, Sales)                   | Phase 2 / Phase 3                            |
| Notification system (the bell icon stays a placeholder)        | Phase 5                                      |
| User profile dropdown actions (Profile, Settings, Sign out)    | Task 01-08                                   |
| Toast / snackbar primitive                                     | First task that needs it                     |
| General-purpose Modal / Dialog primitive (separate from MobileMenu) | First task that needs it                |
| Listbox, Combobox, Disclosure primitives                       | First task that needs each                   |
| Form validation wiring (React Hook Form + Zod)                 | Task 01-08                                   |
| Storybook                                                      | Optional, not currently planned              |
| Playwright e2e tests                                           | Phase 2                                      |
| Real domain components (`ListingCard`, `BidHistory`, etc.)     | Phase 2                                      |
| `components/auction/` and `components/user/` folders           | First task that needs each                   |
| Production-grade Dockerfile (multi-stage, no source mount)     | Phase 11 / pre-launch                        |
| Type scale tuning for `display-lg` if Curator hero feels heavy | Task 01-10                                   |
| Extracting `useScrolled()` hook to `src/lib/hooks/`            | Second consumer of scroll-aware lift         |
| `<Dropdown.Item>` compound API for dividers / group headers    | First task that needs dividers               |
