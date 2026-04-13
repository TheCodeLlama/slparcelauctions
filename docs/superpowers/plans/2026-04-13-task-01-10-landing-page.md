# Task 01-10: Landing Page Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the 7-line `/` stub with a public marketing landing page composed of Hero, How It Works, Features (asymmetric bento), and Bottom CTA sections, all using design-system tokens and working in both light and dark themes.

**Architecture:** 8 new React components in `components/marketing/` (plus 10 lucide icon re-exports, the verify-no-inline-styles allowlist update, and a rewritten `app/page.tsx`). Page-level logic is pure composition in a Server Component; auth-aware sections (`Hero`, `CtaSection`) and theme-aware sections (`HeroFeaturedParcel`, `FeatureCard`) cross the client boundary. Three image assets live in `frontend/public/landing/` (already committed in the worktree).

**Tech Stack:** Next.js 16 App Router, React 19.2, TypeScript 5, Tailwind CSS 4, `next-themes`, `next/image`, TanStack Query (via existing `useAuth`), Vitest 4 + `@testing-library/react`, MSW 2 for auth-state mocking.

---

## Overview

**Spec:** `docs/superpowers/specs/2026-04-13-task-01-10-landing-page-design.md` — the design source of truth. Read spec §5 (page structure), §6 (component specs with full code), §9 (client/server boundary map), and §10 (testing strategy) before implementation.

**Worktree:** This plan executes in `C:/Users/heath/Repos/Personal/slpa-task-01-10`, branched from `dev`. Commit and push to `task/01-10-landing-page`. Final PR targets `dev`, not `main`.

**Phases:**

| Phase | Tasks | What it proves                                                                                  |
|-------|-------|--------------------------------------------------------------------------------------------------|
| A     | 1     | Icons added + inline-style allowlist in place. No production code change yet.                   |
| B     | 2–4   | `LivePill`, `HeroFeaturedParcel`, `Hero` shipped with auth-aware CTAs and theme-aware image swap. |
| C     | 5–6   | `HowItWorksStep`, `HowItWorksSection` shipped with static content.                               |
| D     | 7–8   | `FeatureCard` (variants + optional backgroundImage), `FeaturesSection` asymmetric bento.         |
| E     | 9     | `CtaSection` with gradient + auth-hidden behavior.                                               |
| F     | 10–11 | `app/page.tsx` rewritten, README swept. FOOTGUNS §F.20 + §F.21 added.                            |
| G     | 12    | Full verify chain (tests, lint, verify scripts, build) + manual smoke + PR opened against `dev`. |

**12 tasks total.**

**Commit discipline:**
- One atomic commit per task unless the task explicitly says otherwise.
- Commit messages: conventional commits (`feat:`, `fix:`, `test:`, `docs:`, `chore:`).
- No AI/tool attribution footers. No `Co-Authored-By`. No `--no-verify` on pre-commit hooks — if a hook fails, fix the underlying issue.
- Push after each task so review can happen continuously.

**Load-bearing invariants:**
1. **`LivePill.tsx` has NO `"use client"` directive.** It is a Server Component with a header comment warning against adding hooks. See spec §6.1 and FOOTGUNS §F.20.
2. **`HeroFeaturedParcel.tsx` and `FeatureCard.tsx` use the `mounted` hydration guard** when reading `resolvedTheme` from `next-themes`. See spec §6.3, §6.7 and FOOTGUNS §F.21.
3. **`CtaSection.tsx` returns `null` when `useAuth().status === "authenticated"`.** Do not render an empty container. See spec §5.4, §6.8.
4. **Emojis are forbidden everywhere.** Icons come from `@/components/ui/icons` (lucide-react). No emoji characters in source, tests, fixtures, comments, docs, or PR bodies.
5. **No hex colors, no inline styles** outside the allowlist. The single inline-style exception is `CtaSection.tsx`'s radial-dot decoration; add the file to the verify-no-inline-styles allowlist in Task 1.
6. **No `border-*` classes for sectioning.** DESIGN.md §2 No-Line Rule. Use background shifts.
7. **Design-system tokens only.** No hex literals, no arbitrary color values. Existing tokens: `surface`, `surface-container-low`, `surface-container-lowest`, `surface-container`, `surface-container-high`, `surface-container-highest`, `primary`, `primary-container`, `primary-fixed-dim`, `on-surface`, `on-surface-variant`, `on-primary`, `on-primary-container`, `inverse-surface`, `inverse-on-surface`, `outline-variant`.
8. **`renderWithProviders` is the test render helper**, imported from `@/test/render`. It accepts `theme: "light" | "dark"` and `forceTheme: boolean` options that drive `next-themes`'s `forcedTheme` prop — this is the correct way to test theme-dependent rendering, NOT `vi.mock("next-themes")`.

**Commands:**

```bash
# From the worktree root C:/Users/heath/Repos/Personal/slpa-task-01-10:

cd frontend
npm test                          # full Vitest suite
npx vitest run src/components/marketing/<File>.test.tsx   # single test file
npm run lint                      # ESLint
npm run verify                    # 4 verify scripts (no-dark-variants, no-hex-colors, no-inline-styles, coverage)
npm run build                     # Next.js production build
npm run dev                       # Dev server at localhost:3000 for manual smoke test
```

---

## Phase A — Foundation

**Phase goal:** Add the 10 new icon re-exports and the inline-style allowlist update. Zero production logic — these are prerequisites the later tasks depend on.

---

### Task 1: Icon Re-Exports + `verify-no-inline-styles.sh` Allowlist

**Why:** Every subsequent task needs one or more of the 10 new lucide icons, and Task 9 (`CtaSection`) ships an inline `style={{ backgroundImage: ... }}` prop that will fail the existing verify script without the allowlist.

**Files:**
- Modify: `frontend/src/components/ui/icons.ts`
- Modify: `frontend/scripts/verify-no-inline-styles.sh`

---

- [ ] **Step 1: Add 10 lucide re-exports to `icons.ts`**

Open `frontend/src/components/ui/icons.ts`. The existing file re-exports 18 icons. Append the 10 new ones as a second `export { ... } from "lucide-react"` block so the existing block stays untouched.

Full file contents after the edit:

```typescript
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

// Task 01-10 landing page additions.
export {
  ShieldCheck,
  ListChecks,
  Gavel,
  CreditCard,
  Zap,
  Shield,
  Timer,
  BadgeCheck,
  Bot,
  Star,
} from "lucide-react";
```

- [ ] **Step 2: Verify the icons resolve**

Run: `cd frontend && npx tsc --noEmit`
Expected: PASS — no type errors. lucide-react exports all 10 of these.

- [ ] **Step 3: Rewrite `verify-no-inline-styles.sh` with allowlist support**

Open `frontend/scripts/verify-no-inline-styles.sh`. Replace the entire file contents with:

```bash
#!/usr/bin/env bash
# Fails (exit 1) if any inline style={...} prop appears in src/components or
# src/app, EXCEPT for files explicitly allowlisted below.
#
# Allowlist policy: every entry is a concession. New entries require a comment
# citing a spec section that explains why Tailwind utility classes can't
# express the style. Keep the list short.

set -uo pipefail

dirs=()
[[ -d src/components ]] && dirs+=(src/components)
[[ -d src/app ]] && dirs+=(src/app)

if [[ ${#dirs[@]} -eq 0 ]]; then
  echo "verify:no-inline-styles — neither src/components nor src/app exists, skipping"
  exit 0
fi

# Allowlisted file paths. Each entry MUST have a comment above it citing the
# spec section that justifies the exception.
#
# - src/components/marketing/CtaSection.tsx: decorative radial-gradient dot
#   pattern on the bottom CTA; Tailwind has no radial-gradient utility.
#   See spec docs/superpowers/specs/2026-04-13-task-01-10-landing-page-design.md §6.8.
allowlist=(
  "src/components/marketing/CtaSection.tsx"
)

# Build a single grep -v pipeline that filters out all allowlisted paths.
# Each allowlist entry contributes one `-e "^<path>:"` anchor so grep -v
# only strips lines whose path prefix matches exactly.
filter_args=()
for path in "${allowlist[@]}"; do
  filter_args+=(-e "^${path}:")
done

matches=$(grep -rn 'style={' "${dirs[@]}" | grep -v "${filter_args[@]}")

if [[ -n "$matches" ]]; then
  echo "$matches"
  echo ""
  echo "FAIL: inline style={...} props found above. Use Tailwind utility classes instead."
  echo "If a decorative CSS feature is unavailable in Tailwind, add the file to"
  echo "the allowlist array in frontend/scripts/verify-no-inline-styles.sh with"
  echo "a comment citing the spec section that justifies the exception."
  exit 1
fi

echo "verify:no-inline-styles — OK (no inline styles in ${dirs[*]} beyond the documented allowlist)"
exit 0
```

- [ ] **Step 4: Verify the script still passes on the current tree**

Run: `cd frontend && bash scripts/verify-no-inline-styles.sh`
Expected: `verify:no-inline-styles — OK (no inline styles in src/components src/app beyond the documented allowlist)` — the `CtaSection.tsx` file doesn't exist yet, so the allowlist entry is a no-op but still correctly configured.

- [ ] **Step 5: Run the full verify chain**

Run: `cd frontend && npm run verify`
Expected: all 4 verify scripts PASS.

- [ ] **Step 6: Run lint + test suite**

Run: `cd frontend && npm run lint && npm test`
Expected: lint clean, full suite green (164 existing tests still pass).

- [ ] **Step 7: Commit**

```bash
cd C:/Users/heath/Repos/Personal/slpa-task-01-10
git add frontend/src/components/ui/icons.ts frontend/scripts/verify-no-inline-styles.sh
git commit -m "chore(foundation): add landing page icons and verify-script allowlist

- icons.ts: append 10 lucide re-exports for Task 01-10 landing page
  components (ShieldCheck, ListChecks, Gavel, CreditCard, Zap, Shield,
  Timer, BadgeCheck, Bot, Star).
- verify-no-inline-styles.sh: add allowlist support with a single entry
  for src/components/marketing/CtaSection.tsx — decorative radial-gradient
  dot pattern that Tailwind cannot express. See spec §6.8."
git push
```

---

## Phase B — Hero Section

**Phase goal:** Ship the three components that make up the Hero: `LivePill` (shared primitive), `HeroFeaturedParcel` (theme-aware image card), and `Hero` (auth-aware composition).

---

### Task 2: `LivePill` Component + Smoke Test

**Why:** `LivePill` is the shared animated-pill primitive used in the Hero. It has a load-bearing "no hooks" rule documented in the file header (spec §6.1, FOOTGUNS §F.20) so it can be composed from both Server and Client Component parents without hydration boundary issues.

**Files:**
- Create: `frontend/src/components/marketing/LivePill.tsx`
- Create: `frontend/src/components/marketing/LivePill.test.tsx`

---

- [ ] **Step 1: Write the failing smoke test**

Create `frontend/src/components/marketing/LivePill.test.tsx`:

```typescript
// frontend/src/components/marketing/LivePill.test.tsx

import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { LivePill } from "./LivePill";

describe("LivePill", () => {
  it("renders the label text", () => {
    renderWithProviders(<LivePill>Live Auctions Active</LivePill>);
    expect(screen.getByText("Live Auctions Active")).toBeInTheDocument();
  });

  it("includes an element with animate-ping class for the pulse effect", () => {
    const { container } = renderWithProviders(
      <LivePill>Live Auctions Active</LivePill>
    );
    // The ping dot is a <span> with the Tailwind `animate-ping` class.
    // We assert its presence via container query, not getByRole, because
    // it's decorative and has no ARIA role.
    const pingElement = container.querySelector(".animate-ping");
    expect(pingElement).not.toBeNull();
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd frontend && npx vitest run src/components/marketing/LivePill.test.tsx`
Expected: FAIL — `Cannot find module './LivePill'`.

- [ ] **Step 3: Create `LivePill.tsx`**

Create `frontend/src/components/marketing/LivePill.tsx`:

```tsx
// frontend/src/components/marketing/LivePill.tsx
//
// Animated "live status" pill with a pulsing ping dot. Used in Hero to draw
// attention to active auctions; designed to be reusable on /browse, /auction/[id],
// or any page that needs a "something is happening right now" badge.
//
// DO NOT ADD HOOKS. This component has no "use client" directive so it can be
// composed by BOTH server-component parents (HowItWorksSection, FeaturesSection)
// AND client-component parents (Hero, CtaSection). Adding useEffect/useState
// without a "use client" directive would break server-side rendering for any
// server-parent consumer. Adding "use client" unnecessarily would ship the pulse
// animation's JS to every page that uses the pill.
//
// The ping animation is pure Tailwind `animate-ping` — no JS needed.
//
// See FOOTGUNS §F.20.

import type { ReactNode } from "react";
import { cn } from "@/lib/cn";

type LivePillProps = {
  children: ReactNode;
  className?: string;
};

export function LivePill({ children, className }: LivePillProps) {
  return (
    <div
      className={cn(
        "inline-flex w-fit items-center gap-2 rounded-full bg-surface-container-highest px-3 py-1",
        className
      )}
    >
      <span className="relative flex h-2 w-2">
        <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-primary opacity-75" />
        <span className="relative inline-flex h-2 w-2 rounded-full bg-primary" />
      </span>
      <span className="text-[10px] font-bold uppercase tracking-widest text-primary">
        {children}
      </span>
    </div>
  );
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd frontend && npx vitest run src/components/marketing/LivePill.test.tsx`
Expected: PASS — both tests green.

- [ ] **Step 5: Run lint**

Run: `cd frontend && npm run lint`
Expected: clean.

- [ ] **Step 6: Commit**

```bash
cd C:/Users/heath/Repos/Personal/slpa-task-01-10
git add frontend/src/components/marketing/LivePill.tsx \
        frontend/src/components/marketing/LivePill.test.tsx
git commit -m "feat(marketing): add LivePill server component

Shared animated 'live status' pill used in the landing page Hero and
available to future marketing/auction pages. Server Component by design
with a load-bearing 'no hooks' header comment so it can be composed from
both server and client parents without crossing the hydration boundary
unnecessarily. Ping animation is pure Tailwind (animate-ping) — zero JS.

Two smoke tests pin the label rendering and the presence of the ping
animation class."
git push
```

---

### Task 3: `HeroFeaturedParcel` Component + Theme-Swap Tests

**Why:** The right column of the Hero. Theme-aware: renders `hero-parcel-light.png` in light mode, `hero-parcel-dark.png` in dark mode, with a gradient overlay and neutral placeholder caption ("Featured Parcel Preview" / "Live auctions coming soon"). Uses the `mounted` hydration guard pattern (FOOTGUNS §F.21) to avoid SSR/CSR mismatch.

**Files:**
- Create: `frontend/src/components/marketing/HeroFeaturedParcel.tsx`
- Create: `frontend/src/components/marketing/HeroFeaturedParcel.test.tsx`

---

- [ ] **Step 1: Write the failing tests**

Create `frontend/src/components/marketing/HeroFeaturedParcel.test.tsx`:

```typescript
// frontend/src/components/marketing/HeroFeaturedParcel.test.tsx

import { describe, it, expect } from "vitest";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { HeroFeaturedParcel } from "./HeroFeaturedParcel";

describe("HeroFeaturedParcel", () => {
  it("renders the placeholder caption", async () => {
    renderWithProviders(<HeroFeaturedParcel />);
    expect(screen.getByText("Featured Parcel Preview")).toBeInTheDocument();
    expect(screen.getByText("Live auctions coming soon")).toBeInTheDocument();
  });

  it("renders the light hero image by default (theme=light)", async () => {
    renderWithProviders(<HeroFeaturedParcel />, { theme: "light", forceTheme: true });
    // After mount + useEffect fires, the image src should resolve to the light variant.
    await waitFor(() => {
      const img = screen.getByAltText("Featured Parcel Preview") as HTMLImageElement;
      expect(img.src).toContain("hero-parcel-light.png");
    });
  });

  it("renders the dark hero image when theme=dark", async () => {
    renderWithProviders(<HeroFeaturedParcel />, { theme: "dark", forceTheme: true });
    await waitFor(() => {
      const img = screen.getByAltText("Featured Parcel Preview") as HTMLImageElement;
      expect(img.src).toContain("hero-parcel-dark.png");
    });
  });
});
```

**Why `forceTheme: true`:** the `renderWithProviders` helper sets `forcedTheme={theme}` on the `next-themes` `ThemeProvider`, which forces `resolvedTheme` to the requested value unconditionally. This is the correct mechanism for testing theme-dependent rendering; `vi.mock("next-themes")` is unnecessary and noisier.

**Why `waitFor`:** the component uses a `mounted` state flag that flips to `true` in a `useEffect`. On initial render, `resolvedTheme` may be `undefined` (next-themes hasn't resolved yet), and the component renders the light variant as fallback. After the effect fires, the component re-renders with the resolved theme. `waitFor` lets RTL poll for the final rendered state.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd frontend && npx vitest run src/components/marketing/HeroFeaturedParcel.test.tsx`
Expected: FAIL — module not found.

- [ ] **Step 3: Create `HeroFeaturedParcel.tsx`**

Create `frontend/src/components/marketing/HeroFeaturedParcel.tsx`:

```tsx
// frontend/src/components/marketing/HeroFeaturedParcel.tsx
"use client";

import Image from "next/image";
import { useTheme } from "next-themes";
import { useEffect, useState } from "react";

const LIGHT_SRC = "/landing/hero-parcel-light.png";
const DARK_SRC = "/landing/hero-parcel-dark.png";

export function HeroFeaturedParcel() {
  const { resolvedTheme } = useTheme();
  const [mounted, setMounted] = useState(false);

  // Prevent SSR/CSR mismatch: next-themes only knows the real theme after
  // mount. Before mount, render the light variant. After mount, swap to the
  // correct variant based on resolvedTheme.
  //
  // See FOOTGUNS §F.21.
  useEffect(() => {
    setMounted(true);
  }, []);

  const src = mounted && resolvedTheme === "dark" ? DARK_SRC : LIGHT_SRC;

  return (
    <div className="group relative aspect-[4/5] w-full overflow-hidden rounded-xl shadow-elevated">
      <Image
        src={src}
        alt="Featured Parcel Preview"
        fill
        sizes="(min-width: 1024px) 41vw, 0px"
        priority
        className="object-cover transition-transform duration-700 group-hover:scale-105"
      />
      <div className="absolute inset-0 bg-gradient-to-t from-black/60 to-transparent" />
      <div className="absolute bottom-8 left-8 right-8 text-white">
        <p className="font-display text-2xl font-bold">Featured Parcel Preview</p>
        <p className="text-sm text-white/80">Live auctions coming soon</p>
      </div>
    </div>
  );
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd frontend && npx vitest run src/components/marketing/HeroFeaturedParcel.test.tsx`
Expected: PASS — 3 tests green.

If the light-theme test fails because `img.src` contains `hero-parcel-dark.png`, the `mounted` guard is being evaluated wrong. Double-check the condition: `mounted && resolvedTheme === "dark"`.

If both theme tests render the light variant, `forceTheme: true` isn't being passed to the Wrapper. Check that the test call site passes `{ theme: "dark", forceTheme: true }` and that `renderWithProviders` forwards `forceTheme` to `makeWrapper`.

- [ ] **Step 5: Run lint**

Run: `cd frontend && npm run lint`
Expected: clean.

- [ ] **Step 6: Commit**

```bash
cd C:/Users/heath/Repos/Personal/slpa-task-01-10
git add frontend/src/components/marketing/HeroFeaturedParcel.tsx \
        frontend/src/components/marketing/HeroFeaturedParcel.test.tsx
git commit -m "feat(marketing): add HeroFeaturedParcel theme-aware image card

Right column of the landing page Hero. Renders hero-parcel-light.png or
hero-parcel-dark.png via next/image based on useTheme().resolvedTheme,
using the 'mounted' hydration guard pattern to avoid SSR/CSR mismatch.
Caption reads 'Featured Parcel Preview' / 'Live auctions coming soon' —
neutral placeholder per spec §6.3 (no fabricated parcel names or prices
on a platform with zero users).

Three tests: caption renders, light theme → light image, dark theme →
dark image. Theme tests use renderWithProviders({ theme, forceTheme: true })
which drives next-themes via forcedTheme instead of mocking the library."
git push
```

---

### Task 4: `Hero` Component + Auth-Aware CTA Tests

**Why:** Top-level hero section. 7/5 editorial grid, composes `LivePill` + headline + 2 CTAs + `HeroFeaturedParcel`. CTAs swap based on `useAuth()`: unauthenticated secondary = "Start Selling → /register"; authenticated secondary = "Go to Dashboard → /dashboard". Primary "Browse Listings → /browse" is stable across both states.

**Files:**
- Create: `frontend/src/components/marketing/Hero.tsx`
- Create: `frontend/src/components/marketing/Hero.test.tsx`

---

- [ ] **Step 1: Write the failing tests**

Create `frontend/src/components/marketing/Hero.test.tsx`:

```typescript
// frontend/src/components/marketing/Hero.test.tsx

import { describe, it, expect, beforeEach } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { authHandlers } from "@/test/msw/handlers";
import { Hero } from "./Hero";

describe("Hero", () => {
  beforeEach(() => {
    // Default state: unauthenticated. The setup file already registers
    // refreshUnauthenticated as the default; explicit here for clarity.
    server.use(authHandlers.refreshUnauthenticated());
  });

  it("renders the headline and primary CTA", async () => {
    renderWithProviders(<Hero />);
    expect(
      screen.getByRole("heading", { name: /buy & sell second life land at auction/i })
    ).toBeInTheDocument();

    const browseLink = screen.getByRole("link", { name: /browse listings/i });
    expect(browseLink).toHaveAttribute("href", "/browse");
  });

  it("renders 'Start Selling → /register' for unauthenticated users", async () => {
    renderWithProviders(<Hero />);
    await waitFor(() => {
      const startSellingLink = screen.getByRole("link", { name: /start selling/i });
      expect(startSellingLink).toHaveAttribute("href", "/register");
    });
  });

  it("renders 'Go to Dashboard → /dashboard' for authenticated users", async () => {
    server.use(authHandlers.refreshSuccess());
    renderWithProviders(<Hero />);
    await waitFor(() => {
      const dashboardLink = screen.getByRole("link", { name: /go to dashboard/i });
      expect(dashboardLink).toHaveAttribute("href", "/dashboard");
    });
  });

  it("renders the LIVE AUCTIONS ACTIVE pill", () => {
    renderWithProviders(<Hero />);
    expect(screen.getByText(/live auctions active/i)).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd frontend && npx vitest run src/components/marketing/Hero.test.tsx`
Expected: FAIL — module not found.

- [ ] **Step 3: Create `Hero.tsx`**

Create `frontend/src/components/marketing/Hero.tsx`:

```tsx
// frontend/src/components/marketing/Hero.tsx
"use client";

import Link from "next/link";
import { Button } from "@/components/ui";
import { useAuth } from "@/lib/auth";
import { LivePill } from "./LivePill";
import { HeroFeaturedParcel } from "./HeroFeaturedParcel";

export function Hero() {
  const { status } = useAuth();
  const isAuthenticated = status === "authenticated";

  const secondaryHref = isAuthenticated ? "/dashboard" : "/register";
  const secondaryLabel = isAuthenticated ? "Go to Dashboard" : "Start Selling";

  return (
    <section className="relative min-h-[560px] overflow-hidden bg-surface md:min-h-[720px]">
      <div className="mx-auto grid w-full max-w-7xl grid-cols-12 gap-8 px-8 py-20 md:py-28">
        <div className="col-span-12 flex flex-col justify-center lg:col-span-7">
          <LivePill className="mb-6">Live Auctions Active</LivePill>

          <h1 className="mb-8 font-display text-5xl font-extrabold leading-[1.05] tracking-tight text-on-surface md:text-7xl">
            Buy &amp; Sell Second Life Land at Auction
          </h1>

          <p className="mb-10 max-w-xl text-xl leading-relaxed text-on-surface-variant">
            The premium digital land curator. Secure your virtual footprint through our
            verified auction house, featuring real-time bidding and exclusive parcel listings.
          </p>

          <div className="flex flex-wrap gap-4">
            <Link href="/browse">
              <Button variant="primary" size="lg">Browse Listings</Button>
            </Link>
            <Link href={secondaryHref}>
              <Button variant="secondary" size="lg">{secondaryLabel}</Button>
            </Link>
          </div>
        </div>

        <div className="hidden lg:col-span-5 lg:flex lg:items-center lg:justify-center">
          <HeroFeaturedParcel />
        </div>
      </div>
    </section>
  );
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd frontend && npx vitest run src/components/marketing/Hero.test.tsx`
Expected: PASS — 4 tests green.

**Common gotcha:** the authenticated test needs `waitFor` because `useAuth()` bootstraps via MSW which takes a microtask or two. Without `waitFor`, the DOM snapshot captures the unauthenticated state before the refresh resolves.

- [ ] **Step 5: Run the full frontend test suite**

Run: `cd frontend && npm test`
Expected: full suite still green. The new Hero tests add 4 passing tests on top of the previous count.

- [ ] **Step 6: Run lint**

Run: `cd frontend && npm run lint`
Expected: clean.

- [ ] **Step 7: Commit**

```bash
cd C:/Users/heath/Repos/Personal/slpa-task-01-10
git add frontend/src/components/marketing/Hero.tsx \
        frontend/src/components/marketing/Hero.test.tsx
git commit -m "feat(marketing): add Hero section with auth-aware CTAs

Top-level landing page hero. 7/5 editorial grid on lg+ breakpoints,
single-column below lg. Composes LivePill + headline + subtitle + 2
CTAs + HeroFeaturedParcel.

Primary CTA: 'Browse Listings' → /browse (stable across auth states).
Secondary CTA swaps on useAuth().status:
  - unauthenticated: 'Start Selling' → /register
  - authenticated:   'Go to Dashboard' → /dashboard

Four tests: headline renders, primary CTA always links to /browse,
unauthenticated secondary links to /register, authenticated secondary
links to /dashboard. Auth tests use MSW authHandlers.refreshSuccess /
refreshUnauthenticated to drive the useAuth bootstrap."
git push
```

---

## Phase C — How It Works Section

**Phase goal:** Ship the two components for the How It Works section: `HowItWorksStep` (single step card, reusable primitive) and `HowItWorksSection` (full section composing 4 steps).

---

### Task 5: `HowItWorksStep` Component + Smoke Test

**Why:** Single step card. Icon slot + title + body. Pure presentation, Server Component. Kept separate from `HowItWorksSection` per spec §5.2 and user directive ("free future-proofing" if onboarding/help pages ever need to reuse the step shape).

**Files:**
- Create: `frontend/src/components/marketing/HowItWorksStep.tsx`
- Create: `frontend/src/components/marketing/HowItWorksStep.test.tsx`

---

- [ ] **Step 1: Write the failing smoke test**

Create `frontend/src/components/marketing/HowItWorksStep.test.tsx`:

```typescript
// frontend/src/components/marketing/HowItWorksStep.test.tsx

import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { ShieldCheck } from "@/components/ui/icons";
import { HowItWorksStep } from "./HowItWorksStep";

describe("HowItWorksStep", () => {
  it("renders icon, title, and body", () => {
    renderWithProviders(
      <HowItWorksStep
        icon={<ShieldCheck data-testid="step-icon" />}
        title="Verify"
        body="Identity and land ownership verification."
      />
    );
    expect(screen.getByTestId("step-icon")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Verify" })).toBeInTheDocument();
    expect(
      screen.getByText(/identity and land ownership verification/i)
    ).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/components/marketing/HowItWorksStep.test.tsx`
Expected: FAIL — module not found.

- [ ] **Step 3: Create `HowItWorksStep.tsx`**

Create `frontend/src/components/marketing/HowItWorksStep.tsx`:

```tsx
// frontend/src/components/marketing/HowItWorksStep.tsx
import type { ReactNode } from "react";

type HowItWorksStepProps = {
  icon: ReactNode;
  title: string;
  body: string;
};

export function HowItWorksStep({ icon, title, body }: HowItWorksStepProps) {
  return (
    <div className="rounded-xl bg-surface-container-lowest p-8 transition-transform duration-300 hover:-translate-y-2">
      <div className="mb-6 flex size-12 items-center justify-center rounded-lg bg-primary/10 text-primary">
        {icon}
      </div>
      <h3 className="mb-3 font-display text-xl font-bold text-on-surface">{title}</h3>
      <p className="text-sm leading-relaxed text-on-surface-variant">{body}</p>
    </div>
  );
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/components/marketing/HowItWorksStep.test.tsx`
Expected: PASS.

- [ ] **Step 5: Run lint**

Run: `cd frontend && npm run lint`
Expected: clean.

- [ ] **Step 6: Commit**

```bash
cd C:/Users/heath/Repos/Personal/slpa-task-01-10
git add frontend/src/components/marketing/HowItWorksStep.tsx \
        frontend/src/components/marketing/HowItWorksStep.test.tsx
git commit -m "feat(marketing): add HowItWorksStep primitive

Single step card for the landing page's How It Works section. Server
Component; takes icon / title / body props. Intentionally separate from
HowItWorksSection so it can be imported directly by any future onboarding
or help page that needs the same step shape."
git push
```

---

### Task 6: `HowItWorksSection` Component + Smoke Test

**Why:** Full section with heading "Simple, Secure, Curated." + 4-step grid. Composes 4× `HowItWorksStep` with the canonical content (Verify / List / Auction / Settle). Server Component — pure presentation, no hooks.

**Files:**
- Create: `frontend/src/components/marketing/HowItWorksSection.tsx`
- Create: `frontend/src/components/marketing/HowItWorksSection.test.tsx`

---

- [ ] **Step 1: Write the failing smoke test**

Create `frontend/src/components/marketing/HowItWorksSection.test.tsx`:

```typescript
// frontend/src/components/marketing/HowItWorksSection.test.tsx

import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { HowItWorksSection } from "./HowItWorksSection";

describe("HowItWorksSection", () => {
  it("renders the heading and all four step titles", () => {
    renderWithProviders(<HowItWorksSection />);

    expect(
      screen.getByRole("heading", { name: /simple, secure, curated/i })
    ).toBeInTheDocument();

    expect(screen.getByRole("heading", { name: "Verify" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "List" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Auction" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Settle" })).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/components/marketing/HowItWorksSection.test.tsx`
Expected: FAIL — module not found.

- [ ] **Step 3: Create `HowItWorksSection.tsx`**

Create `frontend/src/components/marketing/HowItWorksSection.tsx`:

```tsx
// frontend/src/components/marketing/HowItWorksSection.tsx
import {
  CreditCard,
  Gavel,
  ListChecks,
  ShieldCheck,
} from "@/components/ui/icons";
import { HowItWorksStep } from "./HowItWorksStep";

const STEPS = [
  {
    icon: <ShieldCheck className="size-6" />,
    title: "Verify",
    body: "Identity and land ownership verification to ensure a safe environment for all participants.",
  },
  {
    icon: <ListChecks className="size-6" />,
    title: "List",
    body: "List your parcel with detailed dimensions, location metrics, and professional photography.",
  },
  {
    icon: <Gavel className="size-6" />,
    title: "Auction",
    body: "Engage in high-velocity real-time bidding with automated proxy options and sniping protection.",
  },
  {
    icon: <CreditCard className="size-6" />,
    title: "Settle",
    body: "Secure escrow services ensure funds and land ownership transfer smoothly and instantly.",
  },
] as const;

export function HowItWorksSection() {
  return (
    <section className="bg-surface-container-low px-8 py-32">
      <div className="mx-auto max-w-7xl">
        <div className="mb-20 flex flex-col justify-between gap-8 lg:flex-row lg:items-end">
          <div className="max-w-2xl">
            <h2 className="mb-6 font-display text-4xl font-bold text-on-surface md:text-5xl">
              Simple, Secure, Curated.
            </h2>
            <p className="text-lg text-on-surface-variant">
              We&apos;ve refined the process of digital land acquisition into four seamless
              steps designed for professional curators.
            </p>
          </div>
        </div>
        <div className="grid grid-cols-1 gap-8 md:grid-cols-2 lg:grid-cols-4">
          {STEPS.map((step) => (
            <HowItWorksStep
              key={step.title}
              icon={step.icon}
              title={step.title}
              body={step.body}
            />
          ))}
        </div>
      </div>
    </section>
  );
}
```

Note the `&apos;` escape for the apostrophe in "We've". The Next.js ESLint config enforces `react/no-unescaped-entities` on JSX text content — a raw `'` will fail lint.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/components/marketing/HowItWorksSection.test.tsx`
Expected: PASS.

- [ ] **Step 5: Run lint**

Run: `cd frontend && npm run lint`
Expected: clean.

- [ ] **Step 6: Commit**

```bash
cd C:/Users/heath/Repos/Personal/slpa-task-01-10
git add frontend/src/components/marketing/HowItWorksSection.tsx \
        frontend/src/components/marketing/HowItWorksSection.test.tsx
git commit -m "feat(marketing): add HowItWorksSection 4-step grid

Full section with heading 'Simple, Secure, Curated.' and a 4-step grid
(Verify / List / Auction / Settle). Server Component — composes 4 copies
of HowItWorksStep with lucide icons (ShieldCheck, ListChecks, Gavel,
CreditCard). Canonical step content taken verbatim from the Stitch
reference per spec §5.2.

Smoke test asserts the main heading plus all four step titles render."
git push
```

---

## Phase D — Features Section

**Phase goal:** Ship the two Features components: `FeatureCard` (variant-driven card with optional theme-aware background image) and `FeaturesSection` (asymmetric bento grid composing 6 feature cards).

---

### Task 7: `FeatureCard` Component + Variant/Theme Tests

**Why:** The most complex component in the task. Supports `size: "sm" | "lg"` (with `md:col-span-2` on lg), `variant: "surface" | "primary" | "dark"` (different background/foreground tokens), and an optional `backgroundImage: { light, dark }` prop that renders a decorative `next/image` with theme-aware swap. Uses the `mounted` hydration guard (FOOTGUNS §F.21) same as `HeroFeaturedParcel`.

**Files:**
- Create: `frontend/src/components/marketing/FeatureCard.tsx`
- Create: `frontend/src/components/marketing/FeatureCard.test.tsx`

---

- [ ] **Step 1: Write the failing tests**

Create `frontend/src/components/marketing/FeatureCard.test.tsx`:

```typescript
// frontend/src/components/marketing/FeatureCard.test.tsx

import { describe, it, expect } from "vitest";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { Zap } from "@/components/ui/icons";
import { FeatureCard } from "./FeatureCard";

describe("FeatureCard", () => {
  it("renders title, body, and icon slot", () => {
    renderWithProviders(
      <FeatureCard
        icon={<Zap data-testid="feature-icon" />}
        title="Real-Time Bidding"
        body="Our low-latency engine updates bids in milliseconds."
      />
    );
    expect(screen.getByTestId("feature-icon")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Real-Time Bidding" })).toBeInTheDocument();
    expect(
      screen.getByText(/our low-latency engine updates bids/i)
    ).toBeInTheDocument();
  });

  it("applies md:col-span-2 when size=lg and does not when size=sm", () => {
    const { container: largeContainer } = renderWithProviders(
      <FeatureCard
        icon={<Zap />}
        title="Large"
        body="A large card."
        size="lg"
      />
    );
    // The outermost div carries the grid classes.
    expect(largeContainer.firstChild).toHaveClass("md:col-span-2");

    const { container: smallContainer } = renderWithProviders(
      <FeatureCard
        icon={<Zap />}
        title="Small"
        body="A small card."
        size="sm"
      />
    );
    expect(smallContainer.firstChild).not.toHaveClass("md:col-span-2");
  });

  it("applies variant-specific background classes", () => {
    const { container: surfaceContainer } = renderWithProviders(
      <FeatureCard icon={<Zap />} title="S" body="." variant="surface" />
    );
    expect(surfaceContainer.firstChild).toHaveClass("bg-surface-container");

    const { container: primaryContainer } = renderWithProviders(
      <FeatureCard icon={<Zap />} title="P" body="." variant="primary" />
    );
    expect(primaryContainer.firstChild).toHaveClass("bg-primary-container");

    const { container: darkContainer } = renderWithProviders(
      <FeatureCard icon={<Zap />} title="D" body="." variant="dark" />
    );
    expect(darkContainer.firstChild).toHaveClass("bg-inverse-surface");
  });

  it("renders no decorative image when backgroundImage prop is omitted", () => {
    const { container } = renderWithProviders(
      <FeatureCard icon={<Zap />} title="X" body="." />
    );
    expect(container.querySelector("img")).toBeNull();
  });

  it("renders the light backgroundImage when theme=light", async () => {
    renderWithProviders(
      <FeatureCard
        icon={<Zap />}
        title="Real-Time Bidding"
        body="."
        backgroundImage={{
          light: "/landing/bidding-bg.png",
          dark: "/landing/bidding-bg.png",
        }}
      />,
      { theme: "light", forceTheme: true }
    );
    await waitFor(() => {
      const img = document.querySelector("img");
      expect(img).not.toBeNull();
      expect(img?.src).toContain("bidding-bg.png");
    });
  });

  it("renders the dark backgroundImage when theme=dark and the two sources differ", async () => {
    // Construct a synthetic dark-only path to prove the swap happens even if
    // real usage ships the same file for both themes.
    renderWithProviders(
      <FeatureCard
        icon={<Zap />}
        title="Real-Time Bidding"
        body="."
        backgroundImage={{
          light: "/landing/hero-parcel-light.png",
          dark: "/landing/hero-parcel-dark.png",
        }}
      />,
      { theme: "dark", forceTheme: true }
    );
    await waitFor(() => {
      const img = document.querySelector("img");
      expect(img).not.toBeNull();
      expect(img?.src).toContain("hero-parcel-dark.png");
    });
  });
});
```

**Why the last test uses `hero-parcel-*.png` instead of `bidding-bg.png`:** the real production usage passes the SAME file for both light and dark, so a simple dark-theme test couldn't distinguish "theme swap works" from "image src is constant". Using two genuinely different filenames proves the swap logic, not just the default.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd frontend && npx vitest run src/components/marketing/FeatureCard.test.tsx`
Expected: FAIL — module not found.

- [ ] **Step 3: Create `FeatureCard.tsx`**

Create `frontend/src/components/marketing/FeatureCard.tsx`:

```tsx
// frontend/src/components/marketing/FeatureCard.tsx
"use client";

import Image from "next/image";
import { useTheme } from "next-themes";
import { useEffect, useState, type ReactNode } from "react";
import { cn } from "@/lib/cn";

type FeatureCardSize = "sm" | "lg";
type FeatureCardVariant = "surface" | "primary" | "dark";

type FeatureCardProps = {
  icon: ReactNode;
  title: string;
  body: string;
  size?: FeatureCardSize;
  variant?: FeatureCardVariant;
  backgroundImage?: {
    light: string;
    dark: string;
  };
};

const sizeClasses: Record<FeatureCardSize, string> = {
  sm: "",
  lg: "md:col-span-2",
};

const variantClasses: Record<FeatureCardVariant, string> = {
  surface: "bg-surface-container text-on-surface",
  primary: "bg-primary-container text-on-primary-container",
  dark: "bg-inverse-surface text-inverse-on-surface",
};

export function FeatureCard({
  icon,
  title,
  body,
  size = "sm",
  variant = "surface",
  backgroundImage,
}: FeatureCardProps) {
  const { resolvedTheme } = useTheme();
  const [mounted, setMounted] = useState(false);

  // See FOOTGUNS §F.21 for the hydration guard pattern.
  useEffect(() => {
    setMounted(true);
  }, []);

  const bgSrc = backgroundImage
    ? mounted && resolvedTheme === "dark"
      ? backgroundImage.dark
      : backgroundImage.light
    : null;

  const iconColorClass =
    variant === "dark"
      ? "text-primary-fixed-dim"
      : variant === "primary"
        ? "text-on-primary-container"
        : "text-primary";

  const bodyColorClass =
    variant === "primary"
      ? "text-sm opacity-80"
      : variant === "dark"
        ? "text-sm text-white/60"
        : "text-sm text-on-surface-variant";

  return (
    <div
      className={cn(
        "group relative flex flex-col justify-between gap-8 overflow-hidden rounded-xl p-10",
        sizeClasses[size],
        variantClasses[variant]
      )}
    >
      {bgSrc ? (
        <div className="pointer-events-none absolute right-0 bottom-0 h-full w-1/2 opacity-10 transition-transform duration-500 group-hover:scale-110">
          <Image
            src={bgSrc}
            alt=""
            fill
            sizes="(min-width: 768px) 33vw, 0px"
            className="object-cover"
            aria-hidden
          />
        </div>
      ) : null}

      <div className={cn("relative z-10", iconColorClass)}>{icon}</div>

      <div className="relative z-10">
        <h3
          className={cn(
            "mb-3 font-display font-bold",
            size === "lg" ? "text-3xl" : "text-2xl"
          )}
        >
          {title}
        </h3>
        <p className={cn(size === "lg" ? "max-w-md" : "", bodyColorClass)}>
          {body}
        </p>
      </div>
    </div>
  );
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd frontend && npx vitest run src/components/marketing/FeatureCard.test.tsx`
Expected: PASS — 6 tests green.

If the variant-class test fails with "cannot find bg-surface-container on firstChild", check that `container.firstChild` is the outer `<div>` and not a wrapper. If RTL's `container` wraps things in an extra layer due to `next-themes` injection, use `container.querySelector("div")` or add a `data-testid` to the root div and query that way.

If the theme-swap test for `hero-parcel-dark.png` fails, the `mounted` guard is being evaluated before `useEffect` runs. `waitFor` should handle this, but if not, increase its timeout: `await waitFor(() => { ... }, { timeout: 2000 });`.

- [ ] **Step 5: Run the full frontend test suite**

Run: `cd frontend && npm test`
Expected: full suite green.

- [ ] **Step 6: Run lint**

Run: `cd frontend && npm run lint`
Expected: clean.

- [ ] **Step 7: Commit**

```bash
cd C:/Users/heath/Repos/Personal/slpa-task-01-10
git add frontend/src/components/marketing/FeatureCard.tsx \
        frontend/src/components/marketing/FeatureCard.test.tsx
git commit -m "feat(marketing): add FeatureCard with variants, sizes, and theme-aware backgroundImage

Single feature card used in the asymmetric bento grid of the Features
section. Props:
- size: 'sm' | 'lg' (lg applies md:col-span-2)
- variant: 'surface' | 'primary' | 'dark' (different bg/fg tokens)
- backgroundImage: optional { light, dark } with next/image theme swap

Uses the 'mounted' hydration guard pattern for the backgroundImage
theme swap (FOOTGUNS §F.21). Zero-hex, design-system-tokens-only.

Six tests: title/body/icon render, size class mapping, variant class
mapping, absence of image when prop omitted, light-theme source, dark-
theme source. The dark-theme test uses different filenames (hero-parcel-
light.png and hero-parcel-dark.png) to prove the swap logic rather than
testing a constant."
git push
```

---

### Task 8: `FeaturesSection` Component + Smoke Test

**Why:** Composes 6 `FeatureCard` instances in the asymmetric bento order. Server Component — pure composition, no hooks. The section itself is a Server Component even though its `FeatureCard` children are Client Components (Next.js 16 allows Server → Client import cleanly).

**Files:**
- Create: `frontend/src/components/marketing/FeaturesSection.tsx`
- Create: `frontend/src/components/marketing/FeaturesSection.test.tsx`

---

- [ ] **Step 1: Write the failing smoke test**

Create `frontend/src/components/marketing/FeaturesSection.test.tsx`:

```typescript
// frontend/src/components/marketing/FeaturesSection.test.tsx

import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { FeaturesSection } from "./FeaturesSection";

describe("FeaturesSection", () => {
  it("renders the heading and all six feature titles", () => {
    renderWithProviders(<FeaturesSection />);

    expect(
      screen.getByRole("heading", { name: /designed for performance/i })
    ).toBeInTheDocument();

    expect(
      screen.getByRole("heading", { name: "Real-Time Bidding" })
    ).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { name: "Secure Escrow" })
    ).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { name: "Snipe Protection" })
    ).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { name: "Verified Listings" })
    ).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { name: "Proxy Bidding" })
    ).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { name: "Reputation System" })
    ).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/components/marketing/FeaturesSection.test.tsx`
Expected: FAIL — module not found.

- [ ] **Step 3: Create `FeaturesSection.tsx`**

Create `frontend/src/components/marketing/FeaturesSection.tsx`:

```tsx
// frontend/src/components/marketing/FeaturesSection.tsx
import {
  BadgeCheck,
  Bot,
  Shield,
  Star,
  Timer,
  Zap,
} from "@/components/ui/icons";
import { FeatureCard } from "./FeatureCard";

export function FeaturesSection() {
  return (
    <section className="bg-surface px-8 py-32">
      <div className="mx-auto max-w-7xl">
        <div className="mb-20 text-center">
          <h2 className="mb-6 font-display text-4xl font-bold text-on-surface md:text-5xl">
            Designed for Performance
          </h2>
          <p className="mx-auto max-w-2xl text-lg text-on-surface-variant">
            Engineered to provide the most reliable land trading platform in the virtual ecosystem.
          </p>
        </div>

        <div className="grid grid-cols-1 gap-6 md:grid-cols-3">
          <FeatureCard
            size="lg"
            variant="surface"
            icon={<Zap className="size-8" />}
            title="Real-Time Bidding"
            body="Our low-latency engine updates bids in milliseconds, ensuring you never miss a critical moment in high-stakes auctions."
            backgroundImage={{
              light: "/landing/bidding-bg.png",
              dark: "/landing/bidding-bg.png",
            }}
          />
          <FeatureCard
            size="sm"
            variant="primary"
            icon={<Shield className="size-8" />}
            title="Secure Escrow"
            body="Automated multi-sig escrow for every transaction, protecting both the buyer's capital and the seller's asset."
          />
          <FeatureCard
            size="sm"
            variant="surface"
            icon={<Timer className="size-8" />}
            title="Snipe Protection"
            body="Last-minute bids automatically extend auction windows, preventing unfair last-second bidding tactics."
          />
          <FeatureCard
            size="lg"
            variant="dark"
            icon={<BadgeCheck className="size-8" />}
            title="Verified Listings"
            body="Every parcel is cross-referenced with region data to confirm tier, covenant, and dimensions before listing."
          />
          <FeatureCard
            size="sm"
            variant="surface"
            icon={<Bot className="size-8" />}
            title="Proxy Bidding"
            body="Set your maximum price and let our system bid incrementally on your behalf to win at the best possible price."
          />
          <FeatureCard
            size="lg"
            variant="surface"
            icon={<Star className="size-8" />}
            title="Reputation System"
            body="Trade with confidence using our transparent historical performance metrics for every buyer and seller."
          />
        </div>
      </div>
    </section>
  );
}
```

Note the apostrophes in "buyer's", "seller's", and "behalf" — these are all inside JSX string props (not JSX text), so no `&apos;` escaping is needed. Lint won't complain.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/components/marketing/FeaturesSection.test.tsx`
Expected: PASS.

- [ ] **Step 5: Run lint**

Run: `cd frontend && npm run lint`
Expected: clean.

- [ ] **Step 6: Commit**

```bash
cd C:/Users/heath/Repos/Personal/slpa-task-01-10
git add frontend/src/components/marketing/FeaturesSection.tsx \
        frontend/src/components/marketing/FeaturesSection.test.tsx
git commit -m "feat(marketing): add FeaturesSection asymmetric bento grid

Full section with heading 'Designed for Performance' and 6 FeatureCard
instances in the asymmetric bento layout from spec §5.3:

  - Real-Time Bidding (lg, surface, bidding-bg.png backgroundImage)
  - Secure Escrow (sm, primary)
  - Snipe Protection (sm, surface)
  - Verified Listings (lg, dark)
  - Proxy Bidding (sm, surface)
  - Reputation System (lg, surface, no +8k bubble per spec Q4b)

Server Component composing Client Component children — standard Next.js
16 RSC → CC pattern. Smoke test asserts the heading + all 6 titles."
git push
```

---

## Phase E — Bottom CTA

**Phase goal:** Ship the bottom CTA section with auth-aware visibility (hidden when authenticated) and the decorative radial-gradient dot pattern that the Task 1 allowlist was added for.

---

### Task 9: `CtaSection` Component + Auth Hide/Show Tests

**Why:** Final section of the landing page. Renders a gradient block with 2 CTAs for unauthenticated users; returns `null` when the user is authenticated (Spec §5.4, §6.8). Uses an inline `style` prop for the radial-gradient decoration — the one inline-style exception in the codebase, allowlisted in Task 1's verify script update.

**Files:**
- Create: `frontend/src/components/marketing/CtaSection.tsx`
- Create: `frontend/src/components/marketing/CtaSection.test.tsx`

---

- [ ] **Step 1: Write the failing tests**

Create `frontend/src/components/marketing/CtaSection.test.tsx`:

```typescript
// frontend/src/components/marketing/CtaSection.test.tsx

import { describe, it, expect, beforeEach } from "vitest";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { authHandlers } from "@/test/msw/handlers";
import { CtaSection } from "./CtaSection";

describe("CtaSection", () => {
  beforeEach(() => {
    // Default to unauthenticated; individual tests override with refreshSuccess.
    server.use(authHandlers.refreshUnauthenticated());
  });

  it("renders the sign-up prompt for unauthenticated users", async () => {
    renderWithProviders(<CtaSection />);
    expect(
      screen.getByRole("heading", { name: /ready to acquire your next parcel/i })
    ).toBeInTheDocument();

    const createAccount = screen.getByRole("link", { name: /create free account/i });
    expect(createAccount).toHaveAttribute("href", "/register");

    const viewAuctions = screen.getByRole("link", { name: /view active auctions/i });
    expect(viewAuctions).toHaveAttribute("href", "/browse");
  });

  it("returns null for authenticated users (heading not in DOM)", async () => {
    server.use(authHandlers.refreshSuccess());
    renderWithProviders(<CtaSection />);

    // Wait for the bootstrap useAuth() to resolve, then assert the heading is gone.
    await waitFor(() => {
      expect(
        screen.queryByRole("heading", { name: /ready to acquire your next parcel/i })
      ).toBeNull();
    });
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd frontend && npx vitest run src/components/marketing/CtaSection.test.tsx`
Expected: FAIL — module not found.

- [ ] **Step 3: Create `CtaSection.tsx`**

Create `frontend/src/components/marketing/CtaSection.tsx`:

```tsx
// frontend/src/components/marketing/CtaSection.tsx
"use client";

import Link from "next/link";
import { Button } from "@/components/ui";
import { useAuth } from "@/lib/auth";

export function CtaSection() {
  const { status } = useAuth();

  // Authenticated users don't need a sign-up prompt. Return null so the section
  // disappears entirely — no empty container, no leftover padding.
  if (status === "authenticated") return null;

  return (
    <section className="px-8 py-32">
      <div className="relative mx-auto max-w-7xl overflow-hidden rounded-[2rem] bg-gradient-to-br from-primary to-primary-container p-12 text-center md:p-24">
        {/*
          Inline style: Tailwind has no radial-gradient utility. This decorative
          dot pattern is the one inline-style exception in the codebase,
          allowlisted in frontend/scripts/verify-no-inline-styles.sh. See spec
          §6.8 and FOOTGUNS for the policy.
        */}
        <div
          className="absolute inset-0 opacity-20"
          style={{
            backgroundImage: "radial-gradient(var(--color-on-primary) 1px, transparent 1px)",
            backgroundSize: "40px 40px",
          }}
          aria-hidden
        />
        <div className="relative z-10">
          <h2 className="mb-8 font-display text-4xl font-extrabold tracking-tight text-on-primary md:text-6xl">
            Ready to acquire your next parcel?
          </h2>
          <p className="mx-auto mb-12 max-w-2xl text-xl text-on-primary/80">
            Join thousands of curators building their digital footprint on SLPA.
          </p>
          <div className="flex flex-wrap justify-center gap-6">
            <Link href="/register">
              <Button variant="primary" size="lg">Create Free Account</Button>
            </Link>
            <Link href="/browse">
              <Button variant="secondary" size="lg">View Active Auctions</Button>
            </Link>
          </div>
        </div>
      </div>
    </section>
  );
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd frontend && npx vitest run src/components/marketing/CtaSection.test.tsx`
Expected: PASS — 2 tests green.

- [ ] **Step 5: Run the verify chain (confirms allowlist works)**

Run: `cd frontend && npm run verify`
Expected: all 4 verify scripts PASS. Specifically, `verify:no-inline-styles` should now see the inline `style={...}` in `CtaSection.tsx`, match it against the allowlist, and filter it out — reporting "OK (no inline styles ... beyond the documented allowlist)".

If `verify:no-inline-styles` FAILS with `src/components/marketing/CtaSection.tsx` in the output, the allowlist entry in Task 1's script update isn't matching. Re-check:
- Task 1 allowlist entry is exactly `"src/components/marketing/CtaSection.tsx"`.
- The grep anchor uses `^` and `:` to match line prefixes produced by `grep -rn`.
- The directory `dirs` resolves to `src/components` (not `frontend/src/components`), so grep output paths are relative to the frontend directory.

- [ ] **Step 6: Run the full frontend test suite**

Run: `cd frontend && npm test`
Expected: full suite green.

- [ ] **Step 7: Run lint**

Run: `cd frontend && npm run lint`
Expected: clean. If `react/forbid-dom-props` or similar rule flags the inline style, add an `// eslint-disable-next-line react/forbid-dom-props -- decorative radial-gradient, see spec §6.8` comment above the `<div ... style={...}>` line. The Next.js default ESLint config does NOT include `react/forbid-dom-props`, so this should not be needed — but confirm before shipping.

- [ ] **Step 8: Commit**

```bash
cd C:/Users/heath/Repos/Personal/slpa-task-01-10
git add frontend/src/components/marketing/CtaSection.tsx \
        frontend/src/components/marketing/CtaSection.test.tsx
git commit -m "feat(marketing): add CtaSection with auth-hidden behavior

Bottom CTA section with gradient background, radial-dot decoration, and
two CTAs ('Create Free Account' → /register, 'View Active Auctions' →
/browse). Returns null when useAuth().status === 'authenticated' —
returning users never see the sign-up prompt.

The radial-dot decoration uses an inline style prop because Tailwind has
no radial-gradient utility. This is the codebase's one inline-style
exception, allowlisted in verify-no-inline-styles.sh (Task 1). A comment
in the component explains the exception and points to spec §6.8.

Two tests: unauthenticated shows heading + both CTA links, authenticated
renders null (heading absent from DOM after useAuth bootstraps)."
git push
```

---

## Phase F — Page Composition and Docs

**Phase goal:** Rewrite `app/page.tsx` to compose all 4 sections, update the README, and add FOOTGUNS §F.20 and §F.21.

---

### Task 10: Rewrite `app/page.tsx` + README Sweep

**Why:** Final production wire-up. `app/page.tsx` becomes a thin Server Component composing the 4 marketing sections. The README gets a one-liner mentioning the live landing page (per the auto-memory "update README each task" rule).

**Files:**
- Modify: `frontend/src/app/page.tsx`
- Modify: `README.md`

---

- [ ] **Step 1: Rewrite `app/page.tsx`**

Open `frontend/src/app/page.tsx`. Replace the entire file contents (currently the `PageHeader` stub) with:

```tsx
// frontend/src/app/page.tsx
import { Hero } from "@/components/marketing/Hero";
import { HowItWorksSection } from "@/components/marketing/HowItWorksSection";
import { FeaturesSection } from "@/components/marketing/FeaturesSection";
import { CtaSection } from "@/components/marketing/CtaSection";

export default function HomePage() {
  return (
    <>
      <Hero />
      <HowItWorksSection />
      <FeaturesSection />
      <CtaSection />
    </>
  );
}
```

Zero logic, zero inline JSX, pure composition. The `PageHeader` import from the old stub is gone — `page.tsx` no longer depends on `@/components/layout/PageHeader`.

- [ ] **Step 2: Run tests to make sure nothing broke**

Run: `cd frontend && npm test`
Expected: full suite green. Any test that imported from `@/app/page` or depended on the old stub's content will need updating — but there should be none.

- [ ] **Step 3: Run the production build**

Run: `cd frontend && npm run build`
Expected: PASS. Output should list `/` as a prerendered static route (the landing page is fully composable from server components and the client components it wraps don't prevent prerendering — they hydrate on the client but the initial HTML is server-rendered).

Watch for:
- "Missing Suspense boundary" errors on `useSearchParams` / `usePathname` — neither Hero nor CtaSection uses these, so this shouldn't happen. If it does, audit the auth-aware logic for stale imports.
- "React hooks in server component" errors — this would mean a component with hooks is missing its `"use client"` directive. Cross-check the client/server boundary map in spec §9.

- [ ] **Step 4: Run the dev server for manual smoke**

Run: `cd frontend && npm run dev`
Expected: dev server listens on `http://localhost:3000`.

**Manual verification checklist (do this before committing):**

1. Open `http://localhost:3000` in a browser.
2. **Light theme:** confirm Hero renders with live pill, headline, subtitle, two CTAs, and the `hero-parcel-light.png` on the right.
3. **Switch to dark theme** via the ThemeToggle in the header. Confirm:
   - Hero featured parcel swaps to `hero-parcel-dark.png`.
   - All 6 feature cards render correctly (Verified Listings should look distinctly dark against the dark background).
4. **Unauthenticated CTAs:** without logging in, confirm Hero secondary CTA reads "Start Selling" and the Bottom CTA section is visible with "Create Free Account".
5. **Authenticated CTAs:** log in via `/login` (use an existing test user or register a new one), navigate back to `/`, confirm:
   - Hero secondary CTA reads "Go to Dashboard".
   - Bottom CTA section is GONE entirely (no empty container, no padding).
6. **Responsive:** resize the browser to mobile width (< 768px). Confirm:
   - Hero collapses to single column, featured parcel card hides.
   - Features bento grid collapses to single column.
   - All text remains readable.
7. **Navigate away and back:** click a nav link to `/browse`, then the SLPA logo to return to `/`. Confirm the page re-renders cleanly with no hydration errors in the console.
8. Stop the dev server with Ctrl+C.

If any manual check fails, the task is NOT complete. Debug, fix, and re-verify before committing.

- [ ] **Step 5: Update `README.md`**

Open `README.md`. Find the frontend section (it should mention the auth pages from Task 01-08 and possibly the dev harness from Task 01-09). Update the paragraph to mention the landing page:

Example edit (adjust wording to match the file's existing tone):

Find:
```markdown
### Frontend

The Next.js app at `frontend/` implements the Task 01-08 auth pages (/register, /login, /forgot-password, /dashboard).
```

Replace with:
```markdown
### Frontend

The Next.js app at `frontend/` implements:
- `/` — public marketing landing page with hero, 4-step flow, features bento grid, and auth-aware CTAs (Task 01-10)
- `/register`, `/login`, `/forgot-password`, `/dashboard` — auth pages with JWT session bootstrap (Task 01-08)
- `/dev/ws-test` — development-only WebSocket harness, 404s in production builds (Task 01-09)
```

If the README uses a different structure, adapt the edit to match. The important thing: `/` is mentioned as the landing page, not the old "coming soon" stub.

- [ ] **Step 6: Commit**

```bash
cd C:/Users/heath/Repos/Personal/slpa-task-01-10
git add frontend/src/app/page.tsx README.md
git commit -m "feat(app): rewrite / page as landing page composition

Replace the 7-line PageHeader 'coming soon' stub with a composition of
the four landing page sections built in Tasks 2-9:

  <Hero />                (auth-aware CTAs)
  <HowItWorksSection />   (4-step grid)
  <FeaturesSection />     (6-card asymmetric bento)
  <CtaSection />          (auth-hidden gradient block)

Zero inline JSX, zero logic — the page is pure composition. All business
logic lives in the section components.

README updated to mention the new landing page alongside the existing
auth and dev-harness pages."
git push
```

---

### Task 11: FOOTGUNS §F.20 + §F.21 Additions

**Why:** Encode the two WebSocket-related invariants that this task added into the permanent FOOTGUNS ledger. §F.20 locks the `LivePill` "no hooks" rule; §F.21 locks the `next-themes` `mounted` hydration guard pattern.

**Files:**
- Modify: `docs/implementation/FOOTGUNS.md`

---

- [ ] **Step 1: Locate the end of §F.19 in FOOTGUNS.md**

Use Grep: `grep -n "F\.1[7-9]\|F\.20\|F\.21" docs/implementation/FOOTGUNS.md` to find line numbers. §F.20 and §F.21 should come immediately after the body of §F.19 (added in Task 01-09).

- [ ] **Step 2: Append §F.20 — `LivePill` no-hooks rule**

Append to `docs/implementation/FOOTGUNS.md` right after the last paragraph of §F.19:

```markdown
### F.20 `LivePill` has no "use client" directive — do not add hooks

**Rule:** `components/marketing/LivePill.tsx` is a Server Component by design. It has no `"use client"` directive so it can be composed from both Server Component parents (HowItWorksSection, FeaturesSection) and Client Component parents (Hero, CtaSection) without forcing the entire parent tree to cross the client boundary. Adding `useEffect`, `useState`, or any React hook WITHOUT adding `"use client"` will build-error when imported from a Server parent. Adding `"use client"` unnecessarily ships the pulse animation's JS to every page consumer.

**Why:** Task 01-10 locked this shape in the brainstorm. The animated ping dot is achieved via Tailwind's `animate-ping` class — pure CSS, zero JavaScript. The component takes one prop (`children: ReactNode`) and that's enough. If future work needs per-instance state (e.g., fading the pill in/out on hover with JS), extract a separate `LivePillInteractive.tsx` client component instead of mutating the base.

**How to apply:** when touching `LivePill.tsx`, read the header comment first. If you're tempted to add a hook, stop and ask: does this really need JavaScript, or can Tailwind's animation utilities handle it? If JavaScript is required, create a new component with `"use client"` rather than breaking the existing one's dual-composability contract.
```

- [ ] **Step 3: Append §F.21 — `next-themes` mounted hydration guard**

Append immediately after §F.20:

````markdown
### F.21 `next-themes` + SSR: use the `mounted` hydration guard pattern

**Rule:** When a component reads `useTheme().resolvedTheme` to swap assets or classes based on the active theme, it MUST first check a `mounted` state that flips to `true` in a `useEffect`. Without the guard, the server renders one variant (theme unknown → `undefined`) and the client renders another (theme known → `"dark"`), causing a hydration mismatch that React logs loudly in dev and may render the wrong variant for a flash in production.

**The pattern:**

```tsx
const { resolvedTheme } = useTheme();
const [mounted, setMounted] = useState(false);
useEffect(() => { setMounted(true); }, []);
const variant = mounted && resolvedTheme === "dark" ? "dark" : "light";
```

**Why:** `next-themes` cannot know the real theme on the server because it depends on `localStorage` (user's stored preference) and `prefers-color-scheme` (browser media query), neither of which exists during SSR. The library injects the correct theme class onto the `<html>` element via a blocking script BEFORE React hydrates, but the JS `useTheme()` return value still reads `undefined` during the first React render pass. The `mounted` guard converts that first-render mismatch into a deterministic "always light, then swap after mount" sequence, which React can reconcile without warnings.

Both `HeroFeaturedParcel` and `FeatureCard` (for the `backgroundImage` variant) use this pattern in Task 01-10. Applies to any future component that reads `resolvedTheme`.

**How to apply:** when writing a component that needs theme-dependent rendering, copy the 4-line `mounted` guard verbatim. Do not use `theme` instead of `resolvedTheme` — `theme` can be `"system"`, which isn't a renderable value. For tests, use `renderWithProviders(<X />, { theme: "dark", forceTheme: true })` from `@/test/render` rather than mocking `next-themes` — the test helper's `forcedTheme` prop drives `resolvedTheme` deterministically.
````

Note the triple-backtick fence around the F.21 block — that's because the rule contains an inline code block with its own triple backticks, and markdown nested code blocks need one outer fence with a different length than the inner fence. The ```` ``` ```` pattern (four-space indented code) won't work here because the surrounding paragraph text is left-justified.

**Alternative:** if your markdown tooling doesn't handle the nested fence cleanly, use `<pre><code>` tags or indent the code block 4 spaces inside a paragraph break. Both are valid. Pick the one your repo's markdown linter accepts.

- [ ] **Step 4: Verify the FOOTGUNS changes render cleanly**

Open `docs/implementation/FOOTGUNS.md` in a markdown preview (VS Code, a browser, etc.). Scroll to §F.20 and §F.21. Confirm:
- Both sections have their headings rendered correctly.
- The code block inside §F.21 renders as a code block, not as escaped text.
- No leftover triple-backtick artifacts.

- [ ] **Step 5: Commit**

```bash
cd C:/Users/heath/Repos/Personal/slpa-task-01-10
git add docs/implementation/FOOTGUNS.md
git commit -m "docs(footguns): add F.20 and F.21 for Task 01-10

Two new entries in the frontend FOOTGUNS section:

- F.20: LivePill has no 'use client' directive by design — adding hooks
  would break server-parent composability. Pure-Tailwind animate-ping is
  sufficient; if JS behavior is needed, create a separate client-component
  variant.
- F.21: next-themes + SSR requires the 'mounted' hydration guard pattern
  when reading resolvedTheme. Both HeroFeaturedParcel and FeatureCard use
  this pattern in Task 01-10. Tests should drive forcedTheme via
  renderWithProviders, not mock next-themes."
git push
```

---

## Phase G — Final Verification

**Phase goal:** Run the full verify chain one more time against the complete task, confirm the manual smoke test passes end-to-end, and open the PR against `dev`.

---

### Task 12: Full Verify Chain + Manual Smoke + PR

**Why:** End-of-task sanity check. Everything should already be green from individual task commits, but bundling a clean top-to-bottom run before PR open catches any cross-task interactions.

**Files:**
- No file changes. This task is pure verification and PR open.

---

- [ ] **Step 1: Verify branch state**

```bash
cd C:/Users/heath/Repos/Personal/slpa-task-01-10
git status
git log --oneline origin/dev..HEAD
```

Expected: clean working tree, branch matches origin, ~14-15 commits ahead of dev (3 spec/plan/asset commits from brainstorm + 12 task commits).

- [ ] **Step 2: Full frontend test suite**

```bash
cd frontend
npm test
```

Expected: PASS. Count should be previous baseline + ~15 new tests from the 8 new marketing components:
- LivePill: 2 tests
- HeroFeaturedParcel: 3 tests
- Hero: 4 tests
- HowItWorksStep: 1 test
- HowItWorksSection: 1 test
- FeatureCard: 6 tests
- FeaturesSection: 1 test
- CtaSection: 2 tests
- **Total new: 20 tests** (slightly over the ~15 target but acceptable — comes from FeatureCard having 6 variant-mapping tests)

- [ ] **Step 3: Lint**

```bash
cd frontend && npm run lint
```

Expected: clean.

- [ ] **Step 4: Verify chain**

```bash
cd frontend && npm run verify
```

Expected: all 4 verify scripts PASS:
- `verify:no-dark-variants` — OK
- `verify:no-hex-colors` — OK
- `verify:no-inline-styles` — OK (with CtaSection allowlist entry active)
- `verify:coverage` — OK

- [ ] **Step 5: Production build**

```bash
cd frontend && npm run build
```

Expected: PASS. `/` should show as a prerendered static route in the output. Watch for:
- No hydration warnings.
- No "missing Suspense" errors.
- No unused import warnings.

- [ ] **Step 6: Manual smoke test — fresh end-to-end run**

```bash
cd frontend && npm run dev
```

Open `http://localhost:3000` and walk through the manual checklist from Task 10 Step 4 one more time. Specifically:
- [ ] Light theme: Hero + all sections render correctly.
- [ ] Dark theme: Hero featured parcel swaps to dark image. Verified Listings card looks distinctly dark.
- [ ] Unauthenticated: Hero shows "Start Selling → /register", Bottom CTA visible.
- [ ] Authenticated (log in + reload `/`): Hero shows "Go to Dashboard → /dashboard", Bottom CTA absent.
- [ ] Mobile responsive (resize to <768px): Hero collapses, bento grid collapses.
- [ ] Navigate away + back: no hydration errors in console.

Stop the dev server with Ctrl+C.

- [ ] **Step 7: Open the PR**

```bash
cd C:/Users/heath/Repos/Personal/slpa-task-01-10
gh pr create --base dev --title "feat(marketing): Task 01-10 — landing page at /" --body "$(cat <<'EOF'
## Summary

Replaces the 7-line `PageHeader` stub at `/` with a public marketing landing page composed of four sections: Hero, How It Works, Features (asymmetric bento), and Bottom CTA. All sections use design-system tokens, work in both light and dark themes, and compose from reusable components in `components/marketing/`.

**Components shipped** (all in `frontend/src/components/marketing/`):
- `LivePill` — animated pulse pill, Server Component with load-bearing "no hooks" rule (FOOTGUNS §F.20)
- `Hero` — 7/5 editorial grid with auth-aware CTAs (`useAuth()`)
- `HeroFeaturedParcel` — theme-aware hero image (`useTheme()` + `mounted` hydration guard)
- `HowItWorksStep` + `HowItWorksSection` — 4-step grid (Verify / List / Auction / Settle)
- `FeatureCard` + `FeaturesSection` — asymmetric bento of 6 feature cards with optional theme-aware backgroundImage
- `CtaSection` — gradient block with auth-hidden behavior (returns `null` when authenticated)

**Auth-aware behavior:**
- Unauthenticated: Hero secondary CTA = "Start Selling → /register", Bottom CTA visible.
- Authenticated: Hero secondary CTA = "Go to Dashboard → /dashboard", Bottom CTA absent.

**Assets:** 3 decorative PNGs in `frontend/public/landing/` (hero-parcel-light.png, hero-parcel-dark.png, bidding-bg.png), downloaded from the Stitch template CDN during brainstorming and committed as part of this PR.

**Tests:** 20 new tests across 8 test files. Substantive coverage for auth-aware CTA swap (Hero), auth-hidden visibility (CtaSection), variant mapping + theme-aware backgroundImage (FeatureCard), theme-aware image swap (HeroFeaturedParcel). Smoke coverage for pure-presentation components.

**FOOTGUNS added:**
- §F.20 — LivePill has no `"use client"` directive; do not add hooks
- §F.21 — `next-themes` + SSR requires the `mounted` hydration guard pattern

**Dropped from Stitch reference** (documented in spec):
- Stats Bar entirely (fake numbers on a zero-user platform)
- 3 fake user avatars on the Reputation System card (marketing-honesty concern)
- "+8k reviews" bubble (same reason)
- Fake hero parcel name/price caption (replaced with neutral "Featured Parcel Preview" / "Live auctions coming soon")

## Test plan

**Automated (all green in this session):**
- [x] Frontend `npm test`: 20 new tests + full existing suite pass
- [x] Frontend `npm run lint`: clean
- [x] Frontend `npm run verify`: all 4 scripts pass (including the new CtaSection inline-style allowlist entry)
- [x] Frontend `npm run build`: `/` prerenders as static route

**Manual smoke (reviewer should verify):**
- [ ] `npm run dev` then open `http://localhost:3000`
- [ ] Light theme: all 4 sections render, hero-parcel-light.png on right
- [ ] Dark theme: hero swaps to hero-parcel-dark.png, all cards render
- [ ] Unauthenticated: Hero secondary = "Start Selling", Bottom CTA visible
- [ ] Authenticated (log in, return to `/`): Hero secondary = "Go to Dashboard", Bottom CTA gone
- [ ] Mobile (<768px): single-column layout, bento collapses
- [ ] Navigate away + back: no hydration console errors

## Scope

- Spec: `docs/superpowers/specs/2026-04-13-task-01-10-landing-page-design.md`
- Plan: `docs/superpowers/plans/2026-04-13-task-01-10-landing-page.md`
- 12 implementation commits + 3 earlier commits for spec/plan/assets
EOF
)"
```

- [ ] **Step 8: Capture the PR URL and report**

After `gh pr create` completes, it prints the PR URL. Record it for the reviewer. Task 01-10 is now complete and awaiting human review.

---

## Self-Review Notes

Controller's sanity check before handing off to subagent-driven execution.

**1. Spec coverage check:**

| Spec section | Implemented by |
|---|---|
| §5.1 Hero section layout | Task 4 (Hero.tsx) |
| §5.2 How It Works section | Tasks 5 (HowItWorksStep) + 6 (HowItWorksSection) |
| §5.3 Features asymmetric bento | Tasks 7 (FeatureCard) + 8 (FeaturesSection) |
| §5.4 Bottom CTA + auth-hidden behavior | Task 9 (CtaSection) |
| §6.1 LivePill server component + no-hooks rule | Task 2 (LivePill) + Task 11 (FOOTGUNS §F.20) |
| §6.2 Hero auth-aware CTAs | Task 4 |
| §6.3 HeroFeaturedParcel theme swap + mounted guard | Task 3 |
| §6.4 HowItWorksSection composition | Task 6 |
| §6.5 HowItWorksStep primitive | Task 5 |
| §6.6 FeaturesSection bento composition | Task 8 |
| §6.7 FeatureCard variants + backgroundImage | Task 7 |
| §6.8 CtaSection auth-hidden + inline-style allowlist | Tasks 1 (allowlist) + 9 (CtaSection) |
| §6.9 app/page.tsx rewrite | Task 10 |
| §7 Icon additions | Task 1 |
| §8 Asset inventory | Already committed during brainstorm (pre-Phase A) |
| §9 Client/server boundary map | Distributed across Tasks 2-9 (each component's `"use client"` decision) |
| §10 Testing strategy | Each task ships its test file; Task 12 runs the full suite |
| §11 Design system conformance | Verified by Task 12's `npm run verify` chain |
| §12 File inventory | Matches task-by-task create/modify lists |
| §13 FOOTGUNS additions | Task 11 |

All spec sections mapped. No gaps.

**2. Placeholder scan:** every step contains either exact code, exact commands with expected output, or a specific enough instruction with debugging guidance. No TBDs, no "handle errors appropriately", no "similar to Task N".

**3. Type consistency:** cross-checked the following identifiers:

- `LivePill` props: `{ children: ReactNode, className?: string }` — consumed by `Hero` as `<LivePill className="mb-6">Live Auctions Active</LivePill>`. ✓
- `HeroFeaturedParcel` takes no props — consumed by `Hero` as `<HeroFeaturedParcel />`. ✓
- `HowItWorksStep` props: `{ icon: ReactNode, title: string, body: string }` — consumed by `HowItWorksSection` via the STEPS array. ✓
- `FeatureCard` props: `{ icon, title, body, size?, variant?, backgroundImage? }` — consumed by `FeaturesSection` with 6 call sites, all matching the shape. ✓
- `useAuth()` return shape: `{ status: "loading" | "authenticated" | "unauthenticated", user: AuthUser | null }` — consumed by `Hero` and `CtaSection` via `status === "authenticated"` checks. ✓
- `useTheme().resolvedTheme`: `"light" | "dark" | undefined` — consumed by `HeroFeaturedParcel` and `FeatureCard` with `mounted && resolvedTheme === "dark"` guard. ✓
- `renderWithProviders` signature: `(ui, { theme?, forceTheme?, ...options }) => RenderResult` — consumed by Hero, HeroFeaturedParcel, FeatureCard, CtaSection tests. ✓

No drift. Every identifier used in a later task is defined in an earlier task or in pre-existing project code that the plan cites explicitly.
