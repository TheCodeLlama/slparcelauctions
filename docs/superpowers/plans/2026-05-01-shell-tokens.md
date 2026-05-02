# Shell + Tokens Implementation Plan (Cluster 1)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the new design's tokens, theme attribute, fonts, and chrome (Header / Footer / MobileMenu / ThemeToggle / AppShell + new `WalletPill` primitive) so the entire site looks redesigned at the chrome level even while page bodies remain on M3 until subsequent clusters.

**Architecture:** Additive token strategy — new `--color-*`/`--radius-*`/`--shadow-*`/`--font-*` vars added to the existing `@theme` block in `frontend/src/app/globals.css`. M3 tokens stay until cluster 15. Theme attribute switches from `class` to `data-theme` (one-line provider change + selector rename). Existing layout/UI primitives are skinned in place — file names, prop APIs, and tests preserved. `HeaderWalletIndicator.tsx` is deleted and replaced by a new `WalletPill.tsx` primitive that consumes the same `useCurrentUser` + `useWallet` + `useWalletWsSubscription` hooks.

**Tech Stack:** Next.js 16.2.3, React 19.2.4, TypeScript 5, Tailwind CSS 4 (with `@theme` block), `next-themes`, `next/font/google`, Vitest, Testing Library.

**Spec:** [`docs/superpowers/specs/2026-05-01-frontend-redesign-design.md`](../specs/2026-05-01-frontend-redesign-design.md)

**Visual source of truth:** `docs/final-design/slparcels-website/project/` — read `styles.css`, `shell.jsx`, `primitives.jsx`, `icons.jsx`, `modals-extras.jsx` (mobile drawer section).

**Behavioral source of truth:** existing `frontend/src/` — every hook, API client, and middleware referenced below is preserved exactly as-is.

**Verify scripts (run before every commit in this plan):** `cd frontend && npm run lint && npm run verify && npm test -- --run`

---

## File Structure

**Modified files** (skin in place — file names + prop APIs preserved):
- `frontend/src/app/globals.css` — add new `@theme` token block; rename `.dark` → `[data-theme="dark"]`; add new tokens' dark overrides in same block
- `frontend/src/app/providers.tsx` — flip `attribute="class"` → `attribute="data-theme"`; flip `defaultTheme="dark"` → `"light"`
- `frontend/src/app/layout.tsx` — replace Manrope import with Inter + JetBrains_Mono; update `body` className
- `frontend/src/components/layout/AppShell.tsx` — light skin (background tokens)
- `frontend/src/components/layout/Header.tsx` — full re-skin to design's structure; consume new `WalletPill`
- `frontend/src/components/layout/Footer.tsx` — slim re-skin
- `frontend/src/components/layout/MobileMenu.tsx` — right-side drawer reskin per design
- `frontend/src/components/ui/ThemeToggle.tsx` — Sun/Moon icon button per design
- `frontend/src/components/ui/icons.ts` — extend with any missing icons (verified per task)

**Created files:**
- `frontend/src/components/ui/WalletPill.tsx` — new pill primitive (avatar + balance) replacing `HeaderWalletIndicator`
- `frontend/src/components/ui/WalletPill.test.tsx` — unit test for the new pill

**Deleted files:**
- `frontend/src/components/wallet/HeaderWalletIndicator.tsx` — replaced by `WalletPill`
  (no test file existed; never had one in git history)

**Untouched (verified by reading first):**
- `frontend/src/lib/wallet/use-wallet.ts`, `use-wallet-ws.ts`
- `frontend/src/lib/user.ts` (`useCurrentUser`)
- `frontend/src/lib/auth.ts` (`useAuth`)
- `frontend/src/components/auth/UserMenuDropdown.tsx`
- `frontend/src/components/notifications/NotificationBell.tsx`

---

## Task 1: Extend `@theme` with new design tokens

**Files:**
- Modify: `frontend/src/app/globals.css:3-161` (extend the existing `@theme` block; M3 tokens stay)

- [ ] **Step 1: Read the existing `@theme` block end-to-end**

Read `frontend/src/app/globals.css` lines 1-161. Confirm shape: opening `@import "tailwindcss";` then `@theme { ... }` then `.dark { ... }` override.

- [ ] **Step 2: Append new design tokens to the `@theme` block**

Edit `frontend/src/app/globals.css`. Inside the existing `@theme { ... }` block (just before its closing `}` at line 161), append the following block. Hex values are exact from `docs/final-design/slparcels-website/project/styles.css:3-74`. Tailwind v4 generates utilities (`bg-brand`, `text-fg-muted`, `border-border-subtle`, `rounded-md`, `shadow-md`, etc.) from these names automatically.

```css
  /* ===== New design tokens (cluster 1 — coexist with M3 above) ===== */
  /* Brand */
  --color-brand: #E3631E;
  --color-brand-hover: #C9551A;
  --color-brand-soft: #FFF1E8;
  --color-brand-border: #F8D2B5;

  /* Backgrounds — warm neutrals */
  --color-bg: #FFFFFF;
  --color-bg-subtle: #FAFAF9;
  --color-bg-muted: #F4F4F2;
  --color-bg-hover: #F0EFEC;
  --color-surface-raised: #FFFFFF;
  /* note: --color-surface already exists from M3; we keep its M3 value during transition */

  /* Foregrounds */
  --color-fg: #18181B;
  --color-fg-muted: #52525B;
  --color-fg-subtle: #71717A;
  --color-fg-faint: #A1A1AA;

  /* Borders */
  --color-border: #E7E5E0;
  --color-border-strong: #D4D2CC;
  --color-border-subtle: #EFEDE8;

  /* Semantic — flat, no on-* pairs */
  --color-success-flat: #15803D;
  --color-success-bg: #F0FDF4;
  --color-warning-flat: #B45309;
  --color-warning-bg: #FFFBEB;
  --color-danger-flat: #B91C1C;
  --color-danger-bg: #FEF2F2;
  --color-info-flat: #1D4ED8;
  --color-info-bg: #EFF6FF;

  /* Radii — values from new design (override M3 values for `sm`, `lg`) */
  --radius-xs: 4px;
  --radius-sm: 6px;
  --radius-md: 10px;
  --radius-lg: 14px;
  --radius-xl: 18px;
  --radius-pill: 999px;

  /* Shadows — subtle */
  --shadow-xs: 0 1px 2px rgba(24,24,27,.04);
  --shadow-sm: 0 1px 3px rgba(24,24,27,.06), 0 1px 2px rgba(24,24,27,.04);
  --shadow-md: 0 4px 12px -2px rgba(24,24,27,.06), 0 2px 6px -2px rgba(24,24,27,.04);
  --shadow-lg: 0 12px 28px -8px rgba(24,24,27,.12), 0 6px 12px -4px rgba(24,24,27,.06);
  --ring-color: rgba(227,99,30,.18);

  /* Layout */
  --container-w: 1320px;
  --header-h: 60px;
```

**Naming note:** `--color-success`, `--color-warning`, `--color-danger`, `--color-info` already exist from M3 with different values. The new flat-semantic colors land as `--color-success-flat` / `--color-warning-flat` / `--color-danger-flat` / `--color-info-flat` to avoid collision. Cluster 15 cleanup renames them back to the unsuffixed form once M3 is removed. `--color-success-bg` etc. are net-new and don't collide.

**Tailwind utility names produced:** `bg-brand`, `bg-brand-hover`, `bg-brand-soft`, `border-brand-border`, `bg-bg`, `bg-bg-subtle`, `bg-bg-muted`, `bg-bg-hover`, `bg-surface-raised`, `text-fg`, `text-fg-muted`, `text-fg-subtle`, `text-fg-faint`, `border-border`, `border-border-strong`, `border-border-subtle`, `bg-success-flat`, `bg-success-bg`, `text-success-flat`, `bg-warning-flat`, `bg-warning-bg`, `text-warning-flat`, `bg-danger-flat`, `bg-danger-bg`, `text-danger-flat`, `bg-info-flat`, `bg-info-bg`, `text-info-flat`, `rounded-xs`, `rounded-sm`, `rounded-md`, `rounded-lg`, `rounded-xl`, `rounded-pill`, `shadow-xs`, `shadow-sm`, `shadow-md`, `shadow-lg`.

- [ ] **Step 3: Run frontend lint + verify scripts to confirm CSS parses**

Run: `cd frontend && npm run lint && npm run verify`
Expected: both clean. Verify scripts only scan `src/components` and `src/app` for hex literals in className/style — `globals.css` is exempt.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/globals.css
git commit -m "redesign(shell): add new design tokens to @theme

Brand orange (#E3631E), warm-neutral bg/fg/border palette, flat
semantic colors with -flat suffix to avoid M3 collisions, new radii
(xs/sm/md/lg/xl/pill), subtle shadows (xs/sm/md/lg). M3 tokens stay
during transition (cluster 15 cleanup removes them)."
```

---

## Task 2: Switch theme attribute from class to data-theme

**Files:**
- Modify: `frontend/src/app/providers.tsx:34` (one-line `attribute` change + `defaultTheme`)
- Modify: `frontend/src/app/globals.css:163` (rename `.dark` selector → `[data-theme="dark"]`)
- Modify: `frontend/src/app/globals.css` (append new tokens' dark overrides inside the same `[data-theme="dark"]` block)

- [ ] **Step 1: Read `providers.tsx` line 34**

```bash
grep -n 'ThemeProvider' frontend/src/app/providers.tsx
```

Expected: `ThemeProvider attribute="class" defaultTheme="dark" enableSystem`.

- [ ] **Step 2: Flip `attribute` and `defaultTheme`**

Edit `frontend/src/app/providers.tsx` line 34:

```tsx
// before
<ThemeProvider attribute="class" defaultTheme="dark" enableSystem>

// after
<ThemeProvider attribute="data-theme" defaultTheme="light" enableSystem>
```

Rationale: design is light-primary; `[data-theme="dark"]` is the new design's CSS selector. `next-themes` localStorage key (`theme`) is unchanged so persisted user preferences survive.

- [ ] **Step 3: Rename `.dark` selector in globals.css**

Edit `frontend/src/app/globals.css`. Find the existing `.dark { ... }` block (currently line ~163). Replace the opening selector:

```css
/* before */
.dark {
  /* Only the tokens that DIFFER from light. ... */

/* after */
[data-theme="dark"] {
  /* Only the tokens that DIFFER from light. ... */
```

Leave the body of overrides untouched — M3 dark mode keeps working during transition.

- [ ] **Step 4: Append new design tokens' dark overrides inside the same `[data-theme="dark"]` block**

Inside the `[data-theme="dark"]` block (just before its closing `}`), append:

```css

  /* New design tokens — dark overrides (values from final-design styles.css:76-105) */
  --color-bg: #0E0E10;
  --color-bg-subtle: #131316;
  --color-bg-muted: #1A1A1E;
  --color-bg-hover: #232328;
  --color-surface-raised: #1C1C21;

  --color-border: #2A2A30;
  --color-border-strong: #3A3A42;
  --color-border-subtle: #1F1F24;

  --color-fg: #F4F4F5;
  --color-fg-muted: #B4B4BB;
  --color-fg-subtle: #8B8B93;
  --color-fg-faint: #5C5C63;

  --color-brand-soft: rgba(227,99,30,.14);
  --color-brand-border: rgba(227,99,30,.32);

  --color-success-bg: rgba(34,197,94,.12);
  --color-warning-bg: rgba(234,179,8,.12);
  --color-danger-bg: rgba(239,68,68,.12);
  --color-info-bg: rgba(59,130,246,.12);

  --shadow-xs: 0 1px 2px rgba(0,0,0,.4);
  --shadow-sm: 0 1px 3px rgba(0,0,0,.4);
  --shadow-md: 0 4px 12px -2px rgba(0,0,0,.5);
  --shadow-lg: 0 12px 28px -8px rgba(0,0,0,.6);
```

- [ ] **Step 5: Verify the dev server still renders**

The user keeps `npm run dev` running. Browse to `http://localhost:3000`. Verify:
- Light mode is the default on a fresh-cookie browser (try in an Incognito window)
- Toggling theme via the existing `ThemeToggle` button still works
- `<html>` element has `data-theme="dark"` attribute when in dark mode (open devtools, inspect `<html>`)

If the toggle does nothing or `class="dark"` still appears, the provider didn't reload — the bind-mounted source needs the dev server's HMR to pick up the change, which it does for `app/` routes automatically.

- [ ] **Step 6: Run lint + verify**

```bash
cd frontend && npm run lint && npm run verify
```
Expected: clean.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/providers.tsx frontend/src/app/globals.css
git commit -m "redesign(shell): switch theme attribute to data-theme

next-themes attribute=class -> data-theme; defaultTheme dark -> light
to match design's light-primary system. .dark selector renamed to
[data-theme=dark] in globals.css; M3 dark overrides preserved.
localStorage key (theme) unchanged so persisted preferences survive.

New design tokens get their dark-mode overrides in the same selector
block."
```

---

## Task 3: Swap fonts (Manrope → Inter + JetBrains Mono)

**Files:**
- Modify: `frontend/src/app/layout.tsx:2,7-12,28-29` (replace `Manrope` import + class wiring)
- Modify: `frontend/src/app/globals.css:81-83` (point `--font-sans`/`--font-display`/`--font-body` at Inter; add `--font-mono` for JetBrains)

- [ ] **Step 1: Replace the font import block in `layout.tsx`**

Edit `frontend/src/app/layout.tsx`:

```tsx
// before
import { Manrope } from "next/font/google";
// ...
const manrope = Manrope({
  subsets: ["latin"],
  display: "swap",
  variable: "--font-manrope",
  weight: ["300", "400", "500", "600", "700", "800"],
});

// after
import { Inter, JetBrains_Mono } from "next/font/google";
// ...
const inter = Inter({
  subsets: ["latin"],
  display: "swap",
  variable: "--font-inter",
  weight: ["400", "500", "600", "700", "800"],
});

const jetbrainsMono = JetBrains_Mono({
  subsets: ["latin"],
  display: "swap",
  variable: "--font-jetbrains-mono",
  weight: ["400", "500", "600"],
});
```

- [ ] **Step 2: Update the `<html>` className to wire both font variables**

Edit the `<html>` element in `layout.tsx`:

```tsx
// before
<html lang="en" className={manrope.variable} suppressHydrationWarning>

// after
<html lang="en" className={`${inter.variable} ${jetbrainsMono.variable}`} suppressHydrationWarning>
```

- [ ] **Step 3: Update `body` className to use new tokens**

Edit the `<body>` element in `layout.tsx`:

```tsx
// before
<body className="min-h-screen font-sans bg-surface text-on-surface antialiased">

// after
<body className="min-h-screen font-sans bg-bg text-fg antialiased">
```

`font-sans` resolves to whatever `--font-sans` points to in `@theme`. The next step rewires that variable to Inter.

- [ ] **Step 4: Update `--font-sans`/`--font-display`/`--font-body` in `globals.css` and add `--font-mono`**

Edit `frontend/src/app/globals.css` lines 81-83:

```css
/* before */
--font-sans: var(--font-manrope), system-ui, sans-serif;
--font-display: var(--font-manrope), system-ui, sans-serif;
--font-body: var(--font-manrope), system-ui, sans-serif;

/* after */
--font-sans: var(--font-inter), ui-sans-serif, system-ui, -apple-system, sans-serif;
--font-display: var(--font-inter), ui-sans-serif, system-ui, sans-serif;
--font-body: var(--font-inter), ui-sans-serif, system-ui, sans-serif;
--font-mono: var(--font-jetbrains-mono), ui-monospace, SFMono-Regular, monospace;
```

The aliases keep `font-display`/`font-body` Tailwind utilities (which existing components use, e.g. `Header.tsx:42` `font-display`) working without renaming. `font-mono` becomes a new Tailwind utility.

- [ ] **Step 5: Visual confirm**

Reload the browser. Verify the page renders in Inter (visual: sharper, more geometric than Manrope). Inspect any element with `font-mono` (none yet — will appear in later clusters); verify `font-family` resolves to JetBrains Mono.

- [ ] **Step 6: Run tests, lint, verify**

```bash
cd frontend && npm run lint && npm run verify && npm test -- --run
```
Expected: all green. If any test snapshots reference `font-manrope` or specific font-family values, update them in this commit.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/layout.tsx frontend/src/app/globals.css
git commit -m "redesign(shell): swap Manrope for Inter + JetBrains Mono

Manrope replaced with Inter (variable --font-inter, weights 400-800)
as the body/sans/display font; JetBrains Mono added (variable
--font-jetbrains-mono, weights 400-600) wired to --font-mono so
font-mono utility works in upcoming clusters.

font-display and font-body utilities now alias to Inter so existing
component code (Header logo etc.) keeps rendering without renaming."
```

---

## Task 4: Create `WalletPill` primitive

**Files:**
- Create: `frontend/src/components/ui/WalletPill.tsx`
- Create: `frontend/src/components/ui/WalletPill.test.tsx`
- Modify: `frontend/src/components/ui/index.ts` (export `WalletPill`)

The new pill consumes the same hooks `HeaderWalletIndicator` consumes (`useCurrentUser`, `useWallet`, `useWalletWsSubscription`). Visual treatment: rounded-full pill with brand-orange `L$` icon + tabular-nums available amount, hover popover showing balance/reserved/queued/available + penalty warning. Matches design's `.wallet-pill` CSS in `styles.css:262-285` and the `Header` markup at `shell.jsx:40-43`.

- [ ] **Step 1: Read existing tests in this directory for patterns**

```bash
grep -l 'render' frontend/src/components/ui/*.test.tsx | head -3
```

Read one of them (e.g. `Button.test.tsx`) to confirm the test conventions: `describe`/`it`/`render` from Testing Library, mocking patterns for hooks via Vitest `vi.mock(...)`.

- [ ] **Step 2: Write the failing test**

Create `frontend/src/components/ui/WalletPill.test.tsx`:

```tsx
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import { WalletPill } from "./WalletPill";

vi.mock("@/lib/wallet/use-wallet", () => ({
  useWallet: vi.fn(),
}));
vi.mock("@/lib/wallet/use-wallet-ws", () => ({
  useWalletWsSubscription: vi.fn(),
}));
vi.mock("@/lib/user", () => ({
  useCurrentUser: vi.fn(),
}));

import { useWallet } from "@/lib/wallet/use-wallet";
import { useCurrentUser } from "@/lib/user";

describe("WalletPill", () => {
  beforeEach(() => {
    vi.mocked(useCurrentUser).mockReturnValue({
      data: { id: 1, verified: true },
    } as ReturnType<typeof useCurrentUser>);
  });

  it("renders L$ available amount with tabular-nums", () => {
    vi.mocked(useWallet).mockReturnValue({
      data: { balance: 12000, reserved: 2000, available: 10000, penaltyOwed: 0, queuedForWithdrawal: 0 },
    } as ReturnType<typeof useWallet>);

    render(<WalletPill />);

    expect(screen.getByText(/10,000/)).toBeInTheDocument();
    expect(screen.getByLabelText(/wallet/i)).toHaveAttribute("href", "/wallet");
  });

  it("returns null for unverified users", () => {
    vi.mocked(useCurrentUser).mockReturnValue({
      data: { id: 1, verified: false },
    } as ReturnType<typeof useCurrentUser>);
    vi.mocked(useWallet).mockReturnValue({ data: undefined } as ReturnType<typeof useWallet>);

    const { container } = render(<WalletPill />);
    expect(container).toBeEmptyDOMElement();
  });

  it("renders L$ 0 while wallet is loading", () => {
    vi.mocked(useWallet).mockReturnValue({ data: undefined } as ReturnType<typeof useWallet>);

    render(<WalletPill />);
    expect(screen.getByText(/L\$\s*0\b/)).toBeInTheDocument();
  });
});
```

- [ ] **Step 3: Run test to verify it fails**

```bash
cd frontend && npm test -- --run WalletPill
```
Expected: FAIL with "Cannot find module './WalletPill'" or similar.

- [ ] **Step 4: Implement `WalletPill.tsx`**

Create `frontend/src/components/ui/WalletPill.tsx`:

```tsx
"use client";

import Link from "next/link";
import { useCurrentUser } from "@/lib/user";
import { useWallet } from "@/lib/wallet/use-wallet";
import { useWalletWsSubscription } from "@/lib/wallet/use-wallet-ws";
import { cn } from "@/lib/cn";

function formatLindens(amount: number): string {
  return `L$ ${amount.toLocaleString()}`;
}

export function WalletPill() {
  const { data: user } = useCurrentUser();
  const verified = user?.verified === true;
  const { data: wallet } = useWallet(verified);
  useWalletWsSubscription(verified);

  if (!verified) return null;

  const available = wallet?.available ?? 0;

  return (
    <Link
      href="/wallet"
      aria-label="Wallet"
      className={cn(
        "group relative hidden md:inline-flex items-center gap-2",
        "rounded-pill border border-border bg-bg-subtle",
        "px-3 py-1.5 text-sm font-semibold text-fg",
        "transition-colors hover:border-border-strong",
        "focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
      )}
    >
      <span
        aria-hidden
        className={cn(
          "grid h-[18px] w-[18px] place-items-center rounded-full",
          "bg-brand text-white text-[9px] font-bold leading-none"
        )}
      >
        L$
      </span>
      <span className="tabular-nums">{formatLindens(available)}</span>
    </Link>
  );
}
```

This is the *minimal* pill that satisfies the test and the design's basic `.wallet-pill` shape (`styles.css:262-285`). The hover popover with balance breakdown and penalty warning is added in step 6 below — keep this step focused on getting the test green.

- [ ] **Step 5: Run test to verify it passes**

```bash
cd frontend && npm test -- --run WalletPill
```
Expected: PASS (3/3).

- [ ] **Step 6: Add the hover popover with balance breakdown**

Extend `WalletPill.tsx` with the same popover shape `HeaderWalletIndicator` had (balance / reserved / queued for withdrawal / available + penalty warning). Replace the component body with:

```tsx
"use client";

import Link from "next/link";
import { AlertTriangle, ArrowRight } from "@/components/ui/icons";
import { useCurrentUser } from "@/lib/user";
import { useWallet } from "@/lib/wallet/use-wallet";
import { useWalletWsSubscription } from "@/lib/wallet/use-wallet-ws";
import { cn } from "@/lib/cn";

function formatLindens(amount: number): string {
  return `L$ ${amount.toLocaleString()}`;
}

/**
 * Persistent wallet pill rendered in the global Header. Verified-only —
 * returns null for guests and unverified users. Reveal-only-via-Tailwind
 * popover (group/peer mechanics) on hover/focus shows the full
 * Balance / Reserved / Queued / Available breakdown plus a penalty
 * warning row when penaltyOwed > 0. Loading state renders L$ 0 to
 * avoid layout shift; useWallet polls every 30 s and the WS topic
 * delivers live updates via useWalletWsSubscription.
 */
export function WalletPill() {
  const { data: user } = useCurrentUser();
  const verified = user?.verified === true;
  const { data: wallet } = useWallet(verified);
  useWalletWsSubscription(verified);

  if (!verified) return null;

  const balance = wallet?.balance ?? 0;
  const reserved = wallet?.reserved ?? 0;
  const available = wallet?.available ?? 0;
  const penaltyOwed = wallet?.penaltyOwed ?? 0;
  const queuedForWithdrawal = wallet?.queuedForWithdrawal ?? 0;

  return (
    <Link
      href="/wallet"
      aria-label="Wallet"
      className={cn(
        "group relative hidden md:inline-flex items-center gap-2",
        "rounded-pill border border-border bg-bg-subtle",
        "px-3 py-1.5 text-sm font-semibold text-fg",
        "transition-colors hover:border-border-strong",
        "focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
      )}
    >
      <span
        aria-hidden
        className={cn(
          "grid h-[18px] w-[18px] place-items-center rounded-full",
          "bg-brand text-white text-[9px] font-bold leading-none"
        )}
      >
        L$
      </span>
      <span className="tabular-nums">{formatLindens(available)}</span>

      <div
        role="tooltip"
        className={cn(
          "absolute right-0 top-full z-50 mt-2 w-64 p-3",
          "rounded-lg border border-border bg-surface shadow-lg",
          "invisible pointer-events-none opacity-0",
          "transition-opacity",
          "group-hover:visible group-hover:pointer-events-auto group-hover:opacity-100",
          "group-focus-visible:visible group-focus-visible:pointer-events-auto group-focus-visible:opacity-100"
        )}
      >
        <div className="mb-2 text-xs font-semibold uppercase tracking-wide text-fg-subtle">
          Wallet
        </div>
        <dl className="space-y-1 text-sm">
          <div className="flex justify-between">
            <dt className="text-fg-muted">Balance</dt>
            <dd className="tabular-nums text-fg">{formatLindens(balance)}</dd>
          </div>
          <div className="flex justify-between">
            <dt className="text-fg-muted">Reserved</dt>
            <dd className="tabular-nums text-fg">{formatLindens(reserved)}</dd>
          </div>
          {queuedForWithdrawal > 0 && (
            <div className="flex justify-between">
              <dt className="text-fg-muted">Queued for withdrawal</dt>
              <dd className="tabular-nums text-fg">{formatLindens(queuedForWithdrawal)}</dd>
            </div>
          )}
          <div className="flex justify-between font-medium">
            <dt className="text-fg">Available</dt>
            <dd className="tabular-nums text-fg">{formatLindens(available)}</dd>
          </div>
        </dl>
        {penaltyOwed > 0 && (
          <div className={cn(
            "mt-3 flex gap-2 rounded-md border border-warning-flat/40 bg-warning-bg p-2 text-xs"
          )}>
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-warning-flat" aria-hidden />
            <div>
              <div className="text-warning-flat">
                Penalty owed: {formatLindens(penaltyOwed)}
              </div>
              <span className="mt-1 inline-flex items-center gap-1 underline text-warning-flat">
                Pay penalty
                <ArrowRight className="h-3 w-3" aria-hidden />
              </span>
            </div>
          </div>
        )}
        <span className="mt-3 inline-flex items-center gap-1 text-xs text-brand underline">
          View activity
          <ArrowRight className="h-3 w-3" aria-hidden />
        </span>
      </div>
    </Link>
  );
}
```

- [ ] **Step 7: Re-run test to confirm still green**

```bash
cd frontend && npm test -- --run WalletPill
```
Expected: PASS (3/3). The test asserts only the visible amount + href + unverified null + loading 0 — the popover's invisible-by-default state doesn't affect those assertions.

- [ ] **Step 8: Export `WalletPill` from the barrel**

Edit `frontend/src/components/ui/index.ts`. Find the existing exports list and add `WalletPill`:

```ts
export { WalletPill } from "./WalletPill";
```

(If the file uses a different barrel pattern — e.g. `export * from "./Button"` — match that pattern. Read the file first.)

- [ ] **Step 9: Run lint + verify**

```bash
cd frontend && npm run lint && npm run verify
```
Expected: clean.

- [ ] **Step 10: Commit**

```bash
git add frontend/src/components/ui/WalletPill.tsx frontend/src/components/ui/WalletPill.test.tsx frontend/src/components/ui/index.ts
git commit -m "redesign(shell): add WalletPill primitive

Replaces HeaderWalletIndicator. Same hooks (useCurrentUser, useWallet,
useWalletWsSubscription), same data shape, same null-for-unverified
behavior, same Tailwind group/peer hover popover. New visual treatment
per design's .wallet-pill CSS: rounded-pill border, brand-orange L\$
icon, tabular-nums amount.

HeaderWalletIndicator deletion lands with the Header re-skin (next
commit) so the chain stays atomic per file."
```

---

## Task 5: Skin `AppShell`

**Files:**
- Modify: `frontend/src/components/layout/AppShell.tsx`

- [ ] **Step 1: Read the current `AppShell.tsx` to understand its shape**

```bash
cat frontend/src/components/layout/AppShell.tsx
```

Confirm: it composes `Header` + children + `Footer` and applies a global background class (likely `bg-surface` or `bg-background`).

- [ ] **Step 2: Update background classes to new tokens**

Replace any `bg-surface`, `bg-background`, `text-on-surface`, `text-on-background` references with new equivalents:
- `bg-surface` → `bg-bg`
- `bg-background` → `bg-bg`
- `text-on-surface` → `text-fg`
- `text-on-background` → `text-fg`
- `bg-surface-container-low` → `bg-bg-subtle`
- `bg-surface-container` → `bg-bg-muted`

Leave the structural composition (Header / main / Footer flex layout) alone.

- [ ] **Step 3: Run AppShell test**

```bash
cd frontend && npm test -- --run AppShell
```
Expected: green. Snapshot mismatches indicate the test asserts on specific class names — update the snapshot in this commit.

- [ ] **Step 4: Run lint + verify**

```bash
cd frontend && npm run lint && npm run verify
```
Expected: clean.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/layout/AppShell.tsx
git commit -m "redesign(shell): skin AppShell tokens

bg-surface/bg-background -> bg-bg, text-on-surface -> text-fg,
surface-container-* -> bg-subtle/bg-muted. Structural composition
(Header/main/Footer flex layout) unchanged."
```

---

## Task 6: Skin `Footer`

**Files:**
- Modify: `frontend/src/components/layout/Footer.tsx`
- Modify (if needed): `frontend/src/components/layout/Footer.test.tsx`

Reference: design's footer in `shell.jsx:155-172` and `.ftr` styles in `styles.css:585-608`.

- [ ] **Step 1: Replace the body of `Footer.tsx`**

Replace `frontend/src/components/layout/Footer.tsx` with:

```tsx
import Link from "next/link";

export function Footer() {
  return (
    <footer className="mt-auto border-t border-border bg-bg-subtle py-8">
      <div className="mx-auto flex w-full max-w-[var(--container-w)] flex-wrap items-center justify-between gap-4 px-6">
        <div className="flex items-center gap-2.5">
          <span
            aria-hidden
            className={
              "grid h-[22px] w-[22px] place-items-center rounded-[5px] " +
              "bg-brand text-white text-[10px] font-extrabold tracking-tight"
            }
          >
            SL
          </span>
          <span className="text-xs text-fg-subtle">
            © {new Date().getFullYear()} SLPA · Independent marketplace, not affiliated with Linden Lab.
          </span>
        </div>
        <nav className="flex flex-wrap gap-6">
          <FooterLink href="/about">About</FooterLink>
          <FooterLink href="/contact">Contact</FooterLink>
          <FooterLink href="/partners">Partners</FooterLink>
          <FooterLink href="/terms">Terms</FooterLink>
        </nav>
      </div>
    </footer>
  );
}

function FooterLink({ href, children }: { href: string; children: React.ReactNode }) {
  return (
    <Link
      href={href}
      className="text-sm text-fg-muted transition-colors hover:text-fg"
    >
      {children}
    </Link>
  );
}
```

Notes:
- `max-w-[var(--container-w)]` reads the new `--container-w: 1320px` token (Tailwind v4 supports `var(...)` in arbitrary value brackets).
- Brand "SL" mark mirrors the design's `.hdr-logo-mark` at footer scale.
- Links: About / Contact / Partners / Terms (matches existing routes; Privacy / Fees / Trust+Safety from the design's footer don't have current routes — adding them is for a later cluster, so cluster-1 footer keeps existing links to avoid 404s).

- [ ] **Step 2: Run Footer tests**

```bash
cd frontend && npm test -- --run Footer
```
Expected: most assertions pass since visible text is preserved. If the test asserts on `bg-surface-container-low` directly, update the assertion to `bg-bg-subtle`.

- [ ] **Step 3: Run lint + verify**

```bash
cd frontend && npm run lint && npm run verify
```
Expected: clean.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/components/layout/Footer.tsx frontend/src/components/layout/Footer.test.tsx
git commit -m "redesign(shell): skin Footer

Slim single-row footer with brand mark + copyright on left and links
cluster on right (About / Contact / Partners / Terms). border-t,
bg-bg-subtle, max-w resolved via --container-w (1320px)."
```

---

## Task 7: Skin `Header`

**Files:**
- Modify: `frontend/src/components/layout/Header.tsx`
- Modify (if needed): `frontend/src/components/layout/Header.test.tsx`
- Delete: `frontend/src/components/wallet/HeaderWalletIndicator.tsx`
- Delete: `frontend/src/components/wallet/HeaderWalletIndicator.test.tsx`

Reference: design's `Header` at `shell.jsx:3-58`, `.hdr*` styles at `styles.css:151-285`. The new design has 60px sticky bar with logo mark + nav + actions cluster (search · theme · notifications · wallet pill · profile · mobile hamburger).

- [ ] **Step 1: Read existing `Header.tsx` to confirm consumed dependencies**

Already done — Header consumes `useAuth`, `MenuIcon`, `Button`, `IconButton`, `ThemeToggle`, `MobileMenu`, `NavLink`, `UserMenuDropdown`, `NotificationBell`, `HeaderWalletIndicator`. The new Header keeps every dependency except `HeaderWalletIndicator`, which is replaced by `WalletPill`.

- [ ] **Step 2: Replace `Header.tsx` body**

Replace `frontend/src/components/layout/Header.tsx` with:

```tsx
"use client";

import Link from "next/link";
import { useState } from "react";
import { MenuIcon, Search } from "@/components/ui/icons";
import { Button, IconButton, ThemeToggle, WalletPill } from "@/components/ui";
import { useAuth } from "@/lib/auth";
import { cn } from "@/lib/cn";
import { MobileMenu } from "./MobileMenu";
import { NavLink } from "./NavLink";
import { UserMenuDropdown } from "@/components/auth/UserMenuDropdown";
import { NotificationBell } from "@/components/notifications/NotificationBell";

export function Header() {
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const { status, user } = useAuth();

  return (
    <>
      <header
        className={cn(
          "sticky top-0 z-50 h-[var(--header-h)]",
          "border-b border-border bg-bg/85 backdrop-blur"
        )}
      >
        <div className="mx-auto flex h-full w-full max-w-[var(--container-w)] items-center gap-6 px-6">
          <Link href="/" className="flex shrink-0 items-center gap-2.5">
            <span
              aria-hidden
              className={cn(
                "grid h-7 w-7 place-items-center rounded-[7px]",
                "bg-brand text-white text-[13px] font-extrabold leading-none"
              )}
            >
              SL
            </span>
            <span className="text-base font-bold tracking-tight text-fg">
              Parcels
            </span>
          </Link>

          <nav className="hidden flex-1 items-center gap-1 md:flex">
            <NavLink variant="header" href="/browse">Browse</NavLink>
            <NavLink variant="header" href="/listings/new">Sell parcel</NavLink>
            <NavLink variant="header" href="/dashboard">Dashboard</NavLink>
            {status === "authenticated" && user.role === "ADMIN" && (
              <NavLink variant="header" href="/admin">Admin</NavLink>
            )}
          </nav>

          <div className="flex shrink-0 items-center gap-1.5">
            <IconButton
              aria-label="Search"
              variant="tertiary"
              className="hidden md:inline-flex"
            >
              <Search className="h-[18px] w-[18px]" />
            </IconButton>
            <ThemeToggle />
            <div id="curator-tray-slot" />
            <NotificationBell />
            <WalletPill />

            {status === "loading" ? null : status === "authenticated" ? (
              <UserMenuDropdown user={user} />
            ) : (
              <div className="hidden items-center gap-2 md:flex">
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

Notes:
- 60px height via `h-[var(--header-h)]`.
- Sticky + blurred bg (`bg-bg/85 backdrop-blur`) per design's `.hdr` rule.
- Brand mark + "Parcels" text mirrors design.
- Nav items: Browse, Sell parcel, Dashboard, Admin (conditional). Wallet link removed from nav since `WalletPill` is the wallet entry point.
- Removed: scroll listener (the new design doesn't change appearance on scroll — sticky bar stays the same).
- Curator tray slot, NotificationBell, UserMenuDropdown unchanged — all existing wiring preserved.
- `WalletPill` replaces `HeaderWalletIndicator`. `WalletPill` handles its own `verified` gating and returns null for guests/unverified.

- [ ] **Step 3: Verify the `Search` icon exists in `icons.ts`**

```bash
grep -E '\bSearch\b' frontend/src/components/ui/icons.ts
```

If `Search` is not exported (it's a common Lucide icon, likely is), add it:

```ts
// Append to frontend/src/components/ui/icons.ts (or wherever icons are re-exported from lucide-react)
export { Search } from "lucide-react";
```

(Read the existing `icons.ts` first; match its existing pattern of either `export { Foo } from "lucide-react"` or `export const Foo = (...) => <svg ...>`.)

- [ ] **Step 4: Delete `HeaderWalletIndicator.tsx` and its test**

```bash
git rm frontend/src/components/wallet/HeaderWalletIndicator.tsx frontend/src/components/wallet/HeaderWalletIndicator.test.tsx
```

If any *other* file imports `HeaderWalletIndicator`, the build will fail. Find them first:

```bash
grep -rn 'HeaderWalletIndicator' frontend/src
```

If anything besides `Header.tsx` references it, update or remove the reference in this same commit.

- [ ] **Step 5: Run Header test**

```bash
cd frontend && npm test -- --run Header
```
Expected: tests assert on text content (`Browse`, `Dashboard`, etc.) and accessible roles — those still pass. If a test asserts on `bg-surface/80` or specific scroll behavior or exact button order, update the assertion to match new structure (e.g. `Sign in` link still present when unauthenticated; `Browse` nav link still present).

- [ ] **Step 6: Run all tests + lint + verify**

```bash
cd frontend && npm run lint && npm run verify && npm test -- --run
```
Expected: all green.

- [ ] **Step 7: Visual confirm**

Open `http://localhost:3000` in the browser. Verify:
- Header is 60px tall, sticky at top, white bg with bottom border
- Logo: orange "SL" square + "Parcels" text
- Nav links visible at desktop width
- Wallet pill visible (when authenticated + verified) showing `L$ <amount>`
- Theme toggle works (sun/moon icon flips, page swaps light/dark)
- Mobile width (≤768px / md breakpoint): nav collapses to hamburger; logo + theme + hamburger remain
- Hamburger opens the mobile menu (existing behavior — full reskin in next task)

- [ ] **Step 8: Commit**

```bash
git add frontend/src/components/layout/Header.tsx frontend/src/components/layout/Header.test.tsx frontend/src/components/ui/icons.ts
git rm --cached frontend/src/components/wallet/HeaderWalletIndicator.tsx frontend/src/components/wallet/HeaderWalletIndicator.test.tsx 2>/dev/null || true
git add frontend/src/components/wallet/HeaderWalletIndicator.tsx frontend/src/components/wallet/HeaderWalletIndicator.test.tsx 2>/dev/null
git commit -m "redesign(shell): skin Header + delete HeaderWalletIndicator

60px sticky bar with brand SL mark + Parcels wordmark, nav (Browse /
Sell parcel / Dashboard / Admin), and right cluster (search · theme ·
notifications · wallet pill · profile / mobile hamburger). All
existing wiring preserved: useAuth, NotificationBell, UserMenuDropdown,
curator-tray slot. WalletPill replaces HeaderWalletIndicator (same
hooks, new visuals)."
```

(If `git rm` already staged the deletions, just `git add` plus `git commit` works — the conditional commands above handle either path.)

---

## Task 8: Skin `MobileMenu`

**Files:**
- Modify: `frontend/src/components/layout/MobileMenu.tsx`
- Modify (if needed): `frontend/src/components/layout/MobileMenu.test.tsx`

Reference: design's `MobileNavDrawer` in `modals-extras.jsx` (mobile drawer section near the bottom of that file) and `.hdr` patterns in `styles.css`.

- [ ] **Step 1: Read existing `MobileMenu.tsx` to understand its current shape and props**

```bash
cat frontend/src/components/layout/MobileMenu.tsx
```

Confirm: takes `open` and `onClose` props; uses the existing `Drawer` primitive (or composes its own overlay).

- [ ] **Step 2: Replace `MobileMenu.tsx` with the new design**

Open `frontend/src/components/layout/MobileMenu.tsx` and replace its body with the structure below. The exact file path of the `Drawer` primitive may need reading first — look at `frontend/src/components/ui/Drawer.tsx` to confirm its prop API (`open`, `onClose`, `side` or similar):

```bash
head -40 frontend/src/components/ui/Drawer.tsx
```

Assuming `Drawer` exposes `open`, `onClose`, and a `side="left" | "right"` prop (read the file to verify; if the prop is named differently, adapt below):

```tsx
"use client";

import Link from "next/link";
import { Drawer, ThemeToggle } from "@/components/ui";
import { useAuth } from "@/lib/auth";

type MobileMenuProps = {
  open: boolean;
  onClose: () => void;
};

export function MobileMenu({ open, onClose }: MobileMenuProps) {
  const { status, user } = useAuth();

  return (
    <Drawer open={open} onClose={onClose} side="right">
      <div className="flex h-full w-[300px] flex-col bg-bg">
        {status === "authenticated" && (
          <div className="border-b border-border px-5 py-4">
            <div className="text-sm font-semibold text-fg">{user.displayName}</div>
            <div className="font-mono text-xs text-fg-subtle">
              {user.verified ? "Verified" : "Unverified"}
            </div>
          </div>
        )}

        <nav className="flex flex-1 flex-col gap-1 p-3">
          <MobileNavLink href="/browse" onClose={onClose}>Browse</MobileNavLink>
          <MobileNavLink href="/listings/new" onClose={onClose}>Sell parcel</MobileNavLink>
          <MobileNavLink href="/dashboard" onClose={onClose}>Dashboard</MobileNavLink>
          <MobileNavLink href="/wallet" onClose={onClose}>Wallet</MobileNavLink>
          {status === "authenticated" && user.role === "ADMIN" && (
            <MobileNavLink href="/admin" onClose={onClose}>Admin</MobileNavLink>
          )}
        </nav>

        <div className="flex items-center justify-between border-t border-border px-5 py-3">
          <span className="text-xs text-fg-subtle">Theme</span>
          <ThemeToggle />
        </div>

        <div className="flex flex-wrap gap-4 border-t border-border px-5 py-3">
          <MobileFooterLink href="/about" onClose={onClose}>About</MobileFooterLink>
          <MobileFooterLink href="/contact" onClose={onClose}>Contact</MobileFooterLink>
          <MobileFooterLink href="/terms" onClose={onClose}>Terms</MobileFooterLink>
        </div>
      </div>
    </Drawer>
  );
}

function MobileNavLink({
  href,
  onClose,
  children,
}: {
  href: string;
  onClose: () => void;
  children: React.ReactNode;
}) {
  return (
    <Link
      href={href}
      onClick={onClose}
      className={
        "block rounded-sm px-3 py-2 text-sm font-medium text-fg-muted " +
        "transition-colors hover:bg-bg-hover hover:text-fg"
      }
    >
      {children}
    </Link>
  );
}

function MobileFooterLink({
  href,
  onClose,
  children,
}: {
  href: string;
  onClose: () => void;
  children: React.ReactNode;
}) {
  return (
    <Link
      href={href}
      onClick={onClose}
      className="text-xs text-fg-muted hover:text-fg"
    >
      {children}
    </Link>
  );
}
```

If `Drawer` doesn't yet support `side="right"`, add the prop in this task. Read `Drawer.tsx`; if it only supports a single side, extend it:

```tsx
// In Drawer.tsx
type DrawerProps = {
  open: boolean;
  onClose: () => void;
  side?: "left" | "right";
  children: React.ReactNode;
};

// Then in the render: choose Tailwind classes based on side
const sideClasses = side === "left"
  ? "left-0 border-r"
  : "right-0 border-l";
```

If the existing `Drawer` already handles this, skip. Either way, keep the test updated.

- [ ] **Step 3: Run MobileMenu test**

```bash
cd frontend && npm test -- --run MobileMenu
```
Expected: green. Update assertions for any specific class-name checks that no longer apply (e.g. specific bg colors).

- [ ] **Step 4: Run lint + verify + visual**

```bash
cd frontend && npm run lint && npm run verify && npm test -- --run
```

Open browser at mobile width. Click hamburger. Verify drawer slides in from the right, shows nav + theme + footer-links, closes on backdrop click + Escape.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/layout/MobileMenu.tsx frontend/src/components/layout/MobileMenu.test.tsx frontend/src/components/ui/Drawer.tsx 2>/dev/null
git commit -m "redesign(shell): skin MobileMenu as right-side drawer

300px right-side slide-in drawer matching design's MobileNavDrawer:
account header (verified status), nav links (Browse / Sell parcel /
Dashboard / Wallet / Admin), theme toggle row, footer links. Drawer
side prop extended to support 'right' if it didn't already."
```

---

## Task 9: Skin `ThemeToggle`

**Files:**
- Modify: `frontend/src/components/ui/ThemeToggle.tsx`

Reference: design's theme toggle in `shell.jsx:30-32` — Sun icon when in dark mode (clicking goes to light), Moon icon when in light mode.

- [ ] **Step 1: Read the existing `ThemeToggle.tsx`**

```bash
cat frontend/src/components/ui/ThemeToggle.tsx
```

It already uses `next-themes` `useTheme()` and renders an icon. Likely already close to what we need.

- [ ] **Step 2: Update visual treatment**

Open `frontend/src/components/ui/ThemeToggle.tsx`. Update the rendered button to use the new icon-button shape (36×36, `rounded-sm`, hover `bg-bg-hover`) and the Sun/Moon icons from `lucide-react`. Replace the existing render with:

```tsx
"use client";

import { useTheme } from "next-themes";
import { useEffect, useState } from "react";
import { Moon, Sun } from "@/components/ui/icons";
import { cn } from "@/lib/cn";

export function ThemeToggle() {
  const { resolvedTheme, setTheme } = useTheme();
  const [mounted, setMounted] = useState(false);

  useEffect(() => setMounted(true), []);

  const isDark = mounted && resolvedTheme === "dark";

  return (
    <button
      type="button"
      aria-label={isDark ? "Switch to light mode" : "Switch to dark mode"}
      onClick={() => setTheme(isDark ? "light" : "dark")}
      className={cn(
        "grid h-9 w-9 place-items-center rounded-sm",
        "text-fg-muted transition-colors",
        "hover:bg-bg-hover hover:text-fg",
        "focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
      )}
    >
      {mounted ? (
        isDark ? <Sun className="h-[18px] w-[18px]" /> : <Moon className="h-[18px] w-[18px]" />
      ) : (
        // Render a placeholder during SSR to avoid hydration mismatch
        <span className="h-[18px] w-[18px]" />
      )}
    </button>
  );
}
```

`Moon` and `Sun` are standard Lucide exports — verify they're available in `icons.ts`:

```bash
grep -E '\b(Sun|Moon)\b' frontend/src/components/ui/icons.ts
```

If missing, append `export { Sun, Moon } from "lucide-react";` (matching the existing pattern).

- [ ] **Step 3: Run ThemeToggle test**

```bash
cd frontend && npm test -- --run ThemeToggle
```
Expected: green. If it asserts on specific class-names update them. The aria-label, click handler, and rendered icon (sun in dark mode, moon in light mode) should be the test contract.

- [ ] **Step 4: Run lint + verify**

```bash
cd frontend && npm run lint && npm run verify
```

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/ui/ThemeToggle.tsx frontend/src/components/ui/icons.ts
git commit -m "redesign(shell): skin ThemeToggle to icon-button form

36x36 rounded-sm hover-bg-hover icon button with Sun (dark mode active)
or Moon (light mode active). next-themes useTheme wiring unchanged.
Hydration-safe placeholder during SSR avoids mismatch."
```

---

## Task 10: Final integration verification + push

- [ ] **Step 1: Run the full verify suite**

```bash
cd frontend && npm run lint && npm run verify && npm test -- --run
```
Expected: all green. If anything fails, fix it in this task before pushing.

- [ ] **Step 2: Spot-check key pages in the browser**

Open `http://localhost:3000` in the browser. Walk through:
- `/` (home) — page body still M3 styled, but chrome is the new design (brand orange logo, Inter font, slim border-bottom header, slim footer)
- `/browse` — same
- `/login` — page body M3, chrome new
- Toggle theme via header — both modes work (light is default; dark applies `data-theme="dark"` on `<html>`)
- Resize to mobile — hamburger appears, drawer slides from right, nav + theme + footer-links present
- Authenticated user (if you have a test account) — wallet pill renders with L$ amount; popover shows on hover

If any of the above is broken, add a follow-up task in this plan documenting what failed and how it was fixed.

- [ ] **Step 3: Verify `git status` is clean and the commit history is right**

```bash
cd /Users/heath/Repos/personal/slparcelauctions
git status
git log --oneline -15
```
Expected: working tree clean. Recent commits should be the cluster-1 series:
- `redesign(shell): skin ThemeToggle to icon-button form`
- `redesign(shell): skin MobileMenu as right-side drawer`
- `redesign(shell): skin Header + delete HeaderWalletIndicator`
- `redesign(shell): skin Footer`
- `redesign(shell): skin AppShell tokens`
- `redesign(shell): add WalletPill primitive`
- `redesign(shell): swap Manrope for Inter + JetBrains Mono`
- `redesign(shell): switch theme attribute to data-theme`
- `redesign(shell): add new design tokens to @theme`

- [ ] **Step 4: Push**

```bash
git push origin heath/update-frontend
```
Expected: clean push, no rejected refs.

- [ ] **Step 5: Mark cluster 1 done**

Cluster 1 complete. The site's chrome (header, footer, mobile drawer, theme toggle, font, tokens) is fully redesigned. Page bodies remain on M3 until cluster 2 (home page) lands.

The next plan to write: `docs/superpowers/plans/<date>-home.md` for cluster 2. Begin that plan with the per-cluster work order from spec Section 5 step 1: backend audit (read `frontend/src/app/page.tsx` to understand what data the home page consumes; grep backend controllers for any new endpoints the design's home introduces; produce gap list before writing the plan tasks).
