# Task 01-10: Landing Page — Design Spec

**Task brief:** `docs/implementation/epic-01/task-10-landing-page.md`
**Date:** 2026-04-13
**Base branch:** `dev`
**Worktree:** `C:/Users/heath/Repos/Personal/slpa-task-01-10`
**Author:** brainstorming skill session

---

## 1. Goal

Replace the 7-line `/` stub with a public landing page that explains SLPA to unauthenticated visitors and drives sign-ups, while remaining useful to authenticated users via auth-aware CTAs. No new backend work. No API contracts. Pure frontend composition of reusable marketing components backed by the existing `AppShell` + design-system tokens.

This task ships:
- 8 new React components in `components/marketing/` (plus 10 icon re-exports in `components/ui/icons.ts` and a rewritten `app/page.tsx`).
- 3 downloaded image assets in `frontend/public/landing/`, theme-aware for light and dark mode.
- ~15 unit tests mirroring the 8 components.

It does NOT ship: stats bar, fake user avatars, fake social-proof counters, Playwright E2E, pixel-diff visual regression.

## 2. Alignment With The Brief

The task brief at `docs/implementation/epic-01/task-10-landing-page.md` is well-scoped. Three explicit interpretation decisions:

1. **"Redirect logged-in users to `/browse` OR show the landing page with adjusted CTAs (your choice)"** — we chose **show the landing page with auth-aware CTAs** (Q1). Rationale: redirect-on-landing is jarring for returning users who clicked the logo to go home, and a client-side redirect would flash the landing page before redirecting (our access token bootstraps client-side via the 401 interceptor).
2. **"Stats bar ('X Active Auctions') can use hardcoded placeholder numbers for now"** — we chose to **drop the Stats Bar entirely** (user directive). Specific fabricated numbers on a product with zero users read as marketing lies and contradict the "verified / trustworthy" brand.
3. **"3-4 step visual flow"** — we chose **4 steps** (Verify / List / Auction / Settle) matching the Stitch reference verbatim.

No other brief corrections.

## 3. Decision Log (Locked During Brainstorm)

| ID | Question | Lock |
|---|---|---|
| Q1 | Authenticated user behavior on `/` | **B** — show landing page with auth-aware CTA swap |
| Q2 | Stitch image assets | **B** — download 2 parcel/data images, drop the 3 fake avatars. Dark-mode Stitch also provided a distinct hero image, so 3 images total: `hero-parcel-light.png`, `hero-parcel-dark.png`, `bidding-bg.png` |
| Q3 | Features grid layout | **A** — asymmetric bento matching Stitch. `FeatureCard` supports `size="sm"\|"lg"` + `variant="surface"\|"primary"\|"dark"` + optional `backgroundImage` prop via `next/image` |
| Q4a | Hero featured parcel caption | **(iii)** — neutral placeholder: "Featured Parcel Preview" / "Live auctions coming soon" |
| Q4b | "+8k reviews" bubble on Reputation card | **(ii)** — drop entirely |
| Q4c | How It Works subtitle copy | **(i)** — keep Stitch's generic editorial copy verbatim |
| Q5a | Component inventory | 8 new marketing components + 10 icon additions + `page.tsx` rewrite |
| Q5b | Client/server boundary | Push client boundary as deep as possible. `page.tsx` + section files that need `useAuth()` / `useTheme()` become Client Components; the rest stay Server Components |
| Q5c | Testing budget | 8 test files, ~15 tests, substantive tests for auth/theme/variant logic + smoke tests for pure-presentation components |
| Q5d | Out of scope | Playwright E2E, pixel-diff, responsive-breakpoint unit tests |
| Q5e | `LivePill` composition | Server Component with explicit "no hooks" header comment so future contributors don't break its dual server/client composability |

## 4. Architecture Overview

```
Browser (http://localhost:3000/)
┌──────────────────────────────────────────────────────────────────┐
│ <AppShell>                        (layout.tsx, unchanged)        │
│   <Header>                        (sticky glass, auth-aware)     │
│   <main>                                                          │
│     <HomePage>                    (app/page.tsx, Server Comp)    │
│       <Hero/>                     (Client: useAuth)              │
│       │  ├─ <LivePill>            (Server Comp, no hooks)        │
│       │  ├─ 2× <Button>           (ui primitive)                 │
│       │  └─ <HeroFeaturedParcel>  (Client: useTheme + next/image)│
│       │                                                           │
│       <HowItWorksSection>         (Server Comp)                  │
│       │  └─ 4× <HowItWorksStep>   (Server Comp)                  │
│       │                                                           │
│       <FeaturesSection>           (Server Comp)                  │
│       │  └─ 6× <FeatureCard>      (Client if backgroundImage,    │
│       │                            else Server Comp)             │
│       │                                                           │
│       <CtaSection/>               (Client: useAuth, returns null │
│                                    when authenticated)           │
│   </main>                                                         │
│   <Footer>                        (existing, unchanged)          │
│ </AppShell>                                                       │
└──────────────────────────────────────────────────────────────────┘
```

**Client/server boundary rule:** Push the `"use client"` directive as deep into the tree as possible. `app/page.tsx` stays a Server Component (composition only). Only components that actually call React hooks (`useAuth`, `useTheme`, `useState`, etc.) cross the client boundary. Pure presentation components serialize to static HTML and ship zero JavaScript.

## 5. Page Structure (Top to Bottom)

### 5.1 Hero Section

**Layout:** 7/5 editorial grid on `lg` breakpoint and above. Single column on `md` and below with the featured parcel card hidden.

**Left column (7/12):**
- `<LivePill>LIVE AUCTIONS ACTIVE</LivePill>` — animated ping pill in the amber accent palette.
- Display headline: "Buy & Sell Second Life Land at Auction" (font-display, text-5xl md:text-7xl, font-extrabold, tracking-tight).
- Subtitle: "The premium digital land curator. Secure your virtual footprint through our verified auction house, featuring real-time bidding and exclusive parcel listings." (text-xl, text-on-surface-variant, max-w-xl).
- Two CTAs (auth-aware):
  - **Unauthenticated:** `<Button variant="primary">Browse Listings</Button>` (→ `/browse`) + `<Button variant="secondary">Start Selling</Button>` (→ `/register`)
  - **Authenticated:** `<Button variant="primary">Browse Listings</Button>` (→ `/browse`) + `<Button variant="secondary">Go to Dashboard</Button>` (→ `/dashboard`)

**Right column (5/12, hidden on `md` and below):** `<HeroFeaturedParcel />` — theme-swapped image with gradient overlay and caption.

**Section styling:**
- `relative min-h-[560px] md:min-h-[720px]` (not the Stitch reference's 921px — we're not adding top padding for a fixed nav since AppShell's Header is sticky, not fixed).
- `overflow-hidden bg-surface`
- Inner: `max-w-7xl mx-auto w-full grid grid-cols-12 gap-8 px-8`

### 5.2 How It Works Section

**Layout:** Single heading block + 4-column grid.

**Heading block:**
- h2: "Simple, Secure, Curated." (font-display, text-4xl md:text-5xl, font-bold)
- Subtitle: "We've refined the process of digital land acquisition into four seamless steps designed for professional curators." (kept from Stitch verbatim per Q4c)

**4-step grid:** `grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-8`. Each step is a `<HowItWorksStep>`:

| # | Title | Body | Icon (lucide) |
|---|---|---|---|
| 1 | Verify | Identity and land ownership verification to ensure a safe environment for all participants. | `ShieldCheck` |
| 2 | List | List your parcel with detailed dimensions, location metrics, and professional photography. | `ListChecks` |
| 3 | Auction | Engage in high-velocity real-time bidding with automated proxy options and sniping protection. | `Gavel` |
| 4 | Settle | Secure escrow services ensure funds and land ownership transfer smoothly and instantly. | `CreditCard` |

**Section styling:**
- `py-32 px-8 bg-surface-container-low` (Background shift from hero's `bg-surface` — No-Line Rule from DESIGN.md §2)
- Inner: `max-w-7xl mx-auto`

### 5.3 Features Section

**Layout:** Single heading block + asymmetric bento grid.

**Heading block:**
- h2: "Designed for Performance" (font-display, text-4xl md:text-5xl, font-bold, text-center)
- Subtitle: "Engineered to provide the most reliable land trading platform in the virtual ecosystem." (max-w-2xl mx-auto)

**Bento grid:** `grid grid-cols-1 md:grid-cols-3 gap-6`. Six feature cards with alternating 2-span / 1-span layout:

| # | Title | Body | `size` | `variant` | `icon` | `backgroundImage` |
|---|---|---|---|---|---|---|
| 1 | Real-Time Bidding | Our low-latency engine updates bids in milliseconds, ensuring you never miss a critical moment in high-stakes auctions. | `lg` | `surface` | `Zap` | `{ light: "/landing/bidding-bg.png", dark: "/landing/bidding-bg.png" }` |
| 2 | Secure Escrow | Automated multi-sig escrow for every transaction, protecting both the buyer's capital and the seller's asset. | `sm` | `primary` | `Shield` | — |
| 3 | Snipe Protection | Last-minute bids automatically extend auction windows, preventing unfair last-second bidding tactics. | `sm` | `surface` | `Timer` | — |
| 4 | Verified Listings | Every parcel is cross-referenced with region data to confirm tier, covenant, and dimensions before listing. | `lg` | `dark` | `BadgeCheck` | — |
| 5 | Proxy Bidding | Set your maximum price and let our system bid incrementally on your behalf to win at the best possible price. | `sm` | `surface` | `Bot` | — |
| 6 | Reputation System | Trade with confidence using our transparent historical performance metrics for every buyer and seller. | `lg` | `surface` | `Star` | — |

**Important:** the "+8k" bubble from the Stitch reference is **dropped** (Q4b). The Reputation System card is a plain large card with icon + title + body only.

**Section styling:**
- `py-32 px-8 bg-surface` (back to base surface after HowItWorksSection's container-low)
- Inner: `max-w-7xl mx-auto`

### 5.4 Bottom CTA Section

**Layout:** Single centered rounded container with gradient background.

**Content (unauthenticated only):**
- h2: "Ready to acquire your next parcel?" (font-display, text-4xl md:text-6xl, font-extrabold, text-on-primary)
- Paragraph: "Join thousands of curators building their digital footprint on SLPA." (text-on-primary/80, max-w-2xl)
- Two buttons:
  - Primary white-on-primary: "Create Free Account" → `/register`
  - Secondary primary-container: "View Active Auctions" → `/browse`

**Authenticated behavior:** `CtaSection` returns `null`. The entire section disappears. No padding, no empty container, no "back to top" nonsense. The Features section becomes the last thing above the footer.

**Section styling (when rendered):**
- Outer: `py-32 px-8`
- Inner: `max-w-7xl mx-auto bg-gradient-to-br from-primary to-primary-container rounded-[2rem] p-12 md:p-24 text-center relative overflow-hidden`
- Optional decorative radial-dot pattern using pure CSS `background-image` (no extra assets).

## 6. Component Specs

All files use conventional React 19.2 / TypeScript 5 / Tailwind CSS 4 idioms. All accept a `className?: string` escape hatch merged via `cn()` from `@/lib/cn`. All use semantic design tokens only — no hex literals, no inline styles.

### 6.1 `LivePill.tsx`

**Location:** `frontend/src/components/marketing/LivePill.tsx`
**Type:** Server Component (NO `"use client"` directive)

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

**Tests (`LivePill.test.tsx`):** One smoke test rendering a label, asserting both the label text appears and an element with `animate-ping` class exists.

### 6.2 `Hero.tsx`

**Location:** `frontend/src/components/marketing/Hero.tsx`
**Type:** Client Component (`"use client"`)

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

**Tests (`Hero.test.tsx`):**
1. Unauthenticated → secondary button reads "Start Selling" and links to `/register`.
2. Authenticated → secondary button reads "Go to Dashboard" and links to `/dashboard`.
3. Primary "Browse Listings" button always renders and links to `/browse`.

Uses the existing `render()` helper from `src/test/render.tsx` which provides the TanStack Query context. MSW handler overrides provide the `refreshSuccess` / `refreshUnauthenticated` responses per test.

### 6.3 `HeroFeaturedParcel.tsx`

**Location:** `frontend/src/components/marketing/HeroFeaturedParcel.tsx`
**Type:** Client Component (`"use client"`)

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

  // Prevent SSR/CSR mismatch: next-themes only knows the real theme after mount.
  // Before mount we render the light variant (matches `defaultTheme="dark"` in
  // providers.tsx via CSS — next-themes injects the `class="dark"` attribute).
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

**Why the `mounted` guard:** `next-themes` returns `resolvedTheme === undefined` on the server. Using it directly during SSR would cause a hydration mismatch because the server renders one image and the client swaps it on mount. The `mounted` flag ensures the first render is always the light variant and the theme-aware swap happens cleanly after hydration. This is the standard `next-themes` pattern.

**Tests (`HeroFeaturedParcel.test.tsx`):**
1. Renders light variant when `resolvedTheme === "light"` (or undefined on first render).
2. Renders dark variant when `resolvedTheme === "dark"` after mount (use `vi.mock("next-themes")` to control the return value).
3. Caption text "Featured Parcel Preview" + "Live auctions coming soon" present.

### 6.4 `HowItWorksSection.tsx`

**Location:** `frontend/src/components/marketing/HowItWorksSection.tsx`
**Type:** Server Component

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

Icon imports: `CreditCard`, `Gavel`, `ListChecks`, `ShieldCheck` — all four are consumed by the `STEPS` array.

**Tests (`HowItWorksSection.test.tsx`):** One smoke test asserting the heading "Simple, Secure, Curated." appears and all 4 step titles render ("Verify", "List", "Auction", "Settle").

### 6.5 `HowItWorksStep.tsx`

**Location:** `frontend/src/components/marketing/HowItWorksStep.tsx`
**Type:** Server Component

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

**Tests (`HowItWorksStep.test.tsx`):** One smoke test rendering with props and asserting icon slot + title + body all appear.

### 6.6 `FeaturesSection.tsx`

**Location:** `frontend/src/components/marketing/FeaturesSection.tsx`
**Type:** Server Component

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

The section composes exactly 6 `FeatureCard` instances in the bento order documented in §5.3.

**Tests (`FeaturesSection.test.tsx`):** One smoke test asserting the heading "Designed for Performance" + all 6 feature titles render.

### 6.7 `FeatureCard.tsx`

**Location:** `frontend/src/components/marketing/FeatureCard.tsx`
**Type:** Client Component (`"use client"`) because `backgroundImage` support requires `useTheme()` for light/dark swap. Cards without `backgroundImage` still technically run as client — acceptable overhead (~1KB compressed for the wrapper) since all 6 cards live in the same section.

**Alternative considered:** Split into `<FeatureCard>` (Server, no image) and `<FeatureCardWithImage>` (Client, with image) to keep 5 of 6 cards on the server. Rejected: adds complexity for marginal savings, and Next.js's Client Component overhead amortizes cleanly across siblings.

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
  surface:
    "bg-surface-container text-on-surface",
  primary:
    "bg-primary-container text-on-primary-container",
  dark:
    "bg-inverse-surface text-inverse-on-surface",
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

  useEffect(() => {
    setMounted(true);
  }, []);

  const bgSrc = backgroundImage
    ? mounted && resolvedTheme === "dark"
      ? backgroundImage.dark
      : backgroundImage.light
    : null;

  return (
    <div
      className={cn(
        "group relative overflow-hidden rounded-xl p-10",
        "flex flex-col justify-between gap-8",
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

      <div className="relative z-10 text-primary">
        {variant === "primary" || variant === "dark" ? (
          <span className={variant === "dark" ? "text-primary-fixed-dim" : "text-on-primary-container"}>
            {icon}
          </span>
        ) : (
          icon
        )}
      </div>

      <div className="relative z-10">
        <h3
          className={cn(
            "mb-3 font-display font-bold",
            size === "lg" ? "text-3xl" : "text-2xl"
          )}
        >
          {title}
        </h3>
        <p
          className={cn(
            size === "lg" ? "max-w-md" : "",
            variant === "primary"
              ? "text-sm opacity-80"
              : variant === "dark"
                ? "text-sm text-white/60"
                : "text-sm text-on-surface-variant"
          )}
        >
          {body}
        </p>
      </div>
    </div>
  );
}
```

**Tests (`FeatureCard.test.tsx`):**
1. Renders title + body + icon slot.
2. `size="lg"` applies `md:col-span-2`; `size="sm"` does not.
3. `variant="surface"` renders `bg-surface-container`; `variant="primary"` renders `bg-primary-container`; `variant="dark"` renders `bg-inverse-surface`.
4. `backgroundImage` prop with light/dark swap:
   - `resolvedTheme === "light"` or `undefined` → light src in the `<Image>`.
   - `resolvedTheme === "dark"` after mount → dark src.
5. No `backgroundImage` prop → no `<Image>` in the DOM.

### 6.8 `CtaSection.tsx`

**Location:** `frontend/src/components/marketing/CtaSection.tsx`
**Type:** Client Component (`"use client"`)

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

**Note on inline style:** the radial-dot pattern uses a `style={{ backgroundImage: ... }}` prop with CSS variables. This is the ONE case where inline style is justified: we can't express a `radial-gradient` via Tailwind utility classes cleanly, and the pattern is decorative. This is a minor exception to the "no inline styles" verify-script rule. If `verify:no-inline-styles` flags this file, add the file to the script's allowlist with a comment explaining the exception, OR move the radial-dot pattern to a scoped CSS module. **Plan should pick one path before implementation.**

**Tests (`CtaSection.test.tsx`):**
1. Unauthenticated → section renders with heading + both buttons.
2. Authenticated → `render()` returns no CTA section content (assert the heading text is NOT in the DOM).

### 6.9 `app/page.tsx` (rewrite)

**Location:** `frontend/src/app/page.tsx`
**Type:** Server Component

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

Zero JSX outside of composition. Zero logic. All content lives in the composed sections. The page is exactly what "pages are thin" means in CONVENTIONS.md §Frontend.

**Existing stub removed:** the current `page.tsx` imports `PageHeader` and renders a "coming soon" placeholder. That entire content is replaced by the composition above. No `PageHeader` import remains.

## 7. Icon Additions

Add to `frontend/src/components/ui/icons.ts` (append to the existing lucide re-export block):

```typescript
// Existing re-exports stay unchanged. Append:
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

Ten new icons. All are stock `lucide-react` exports — no SVG hand-authoring.

## 8. Assets Inventory

All three images are already downloaded into `frontend/public/landing/` in the worktree (committed as part of this task):

| File | Size | Dimensions | Used By |
|---|---|---|---|
| `hero-parcel-light.png` | 294 KB | 512×512 | `HeroFeaturedParcel` when `resolvedTheme !== "dark"` |
| `hero-parcel-dark.png` | 306 KB | 512×512 | `HeroFeaturedParcel` when `resolvedTheme === "dark"` |
| `bidding-bg.png` | 377 KB | 512×512 | `FeatureCard` backgroundImage for Real-Time Bidding card, both themes |

**Source:** Downloaded from the Stitch template's CDN (`lh3.googleusercontent.com/aida-public/...`) as PNGs. These are AI-generated decorative assets, license unclear — our use is limited to a marketing landing page with no commercial claims about the imagery. If a license concern arises later, they can be swapped out without touching component code by replacing the files and keeping the filenames.

**Why `.png` over `.jpg`:** the CDN returned PNGs even though the Stitch source used `.jpg` in references. The files ARE PNGs per `file(1)` inspection. We preserve the native format.

**Not downloaded:**
- The three fake user avatars for the Reputation System card (dropped per Q2-B).
- Any stock nav / profile photos (not in our scope — `AppShell` is unchanged).

## 9. Client/Server Boundary Map

| Component | Directive | Reason |
|---|---|---|
| `app/page.tsx` | (none — Server) | Pure composition |
| `Hero.tsx` | `"use client"` | `useAuth()` |
| `HeroFeaturedParcel.tsx` | `"use client"` | `useTheme()` + `useEffect/useState` for hydration guard |
| `HowItWorksSection.tsx` | (none — Server) | Pure presentation, no hooks |
| `HowItWorksStep.tsx` | (none — Server) | Pure presentation, no hooks |
| `FeaturesSection.tsx` | (none — Server) | Pure composition — children are Client Components but importing them from a Server parent is allowed |
| `FeatureCard.tsx` | `"use client"` | `useTheme()` for optional `backgroundImage` swap |
| `CtaSection.tsx` | `"use client"` | `useAuth()` |
| `LivePill.tsx` | (none — Server) | **Load-bearing**: imported by BOTH Hero (Client) and potentially HowItWorksSection (Server) in the future. Must stay hook-free |

**Server → Client → Server import rules (Next.js 16 App Router):**
- A Server Component can import and render a Client Component. ✓ (e.g., `FeaturesSection` → `FeatureCard`)
- A Client Component can import and render a Server Component... BUT the imported component becomes part of the client bundle. ✓ (e.g., `Hero` → `LivePill` still works, `LivePill` gets bundled into the client chunk for Hero)
- A Server Component cannot use React hooks. ✓ Enforced by TypeScript via `"use client"` and by Next.js's build.

**Footgun captured in `LivePill.tsx` header comment (§6.1):** Because `LivePill` has no `"use client"`, adding `useState`/`useEffect` would:
1. Build-error when imported by `HowItWorksSection` (Server parent): "hooks not allowed in server components".
2. Build-successfully when imported by `Hero` (Client parent) — creating inconsistent behavior depending on import path.
The file-header comment exists to prevent this.

## 10. Testing Strategy

**Total budget:** 8 test files, ~15 tests.

### 10.1 Substantive tests (logic to pin)

- **`Hero.test.tsx`** (3 tests)
  - `renders browse and start-selling CTAs for unauthenticated users` — MSW override: `refreshUnauthenticated`. Assert secondary button text "Start Selling" and `href="/register"`.
  - `renders browse and go-to-dashboard CTAs for authenticated users` — MSW override: `refreshSuccess`. Use `waitFor` to let the bootstrap query resolve. Assert secondary button text "Go to Dashboard" and `href="/dashboard"`.
  - `primary browse button always links to /browse regardless of auth state` — runs both MSW variants, asserts primary button href is stable.

- **`CtaSection.test.tsx`** (2 tests)
  - `renders the sign-up prompt for unauthenticated users` — MSW: `refreshUnauthenticated`. Assert heading "Ready to acquire your next parcel?" appears.
  - `returns null for authenticated users` — MSW: `refreshSuccess`. `waitFor` the bootstrap, then assert the heading is NOT in the DOM (`queryByRole("heading", { name: /ready to acquire/i })`).

- **`FeatureCard.test.tsx`** (5 tests)
  - `renders title, body, and icon slot` — smoke.
  - `size="lg" applies md:col-span-2; size="sm" does not` — class assertions.
  - `variant="surface|primary|dark" applies the correct background class` — 3 case iterations.
  - `backgroundImage prop renders a next/image with the light src by default` — mock `next-themes`'s `useTheme` to return `{ resolvedTheme: "light" }`, assert image `src` attribute.
  - `backgroundImage prop renders the dark src when resolvedTheme === "dark" after mount` — mock `useTheme` with `{ resolvedTheme: "dark" }`, `waitFor` the hydration guard, assert dark src.

- **`HeroFeaturedParcel.test.tsx`** (3 tests)
  - `renders light hero image by default` — no theme mock override, asserts `hero-parcel-light.png` in `<img>` src.
  - `renders dark hero image when resolvedTheme === "dark"` — mocked theme, `waitFor` mount guard.
  - `renders placeholder caption` — asserts "Featured Parcel Preview" and "Live auctions coming soon" text.

### 10.2 Smoke tests (render-only)

- **`LivePill.test.tsx`** (1 test) — renders label text, has element with `animate-ping` class.
- **`HowItWorksStep.test.tsx`** (1 test) — renders with props, all three of `icon` / `title` / `body` slots populated.
- **`HowItWorksSection.test.tsx`** (1 test) — heading "Simple, Secure, Curated." + all 4 step titles present.
- **`FeaturesSection.test.tsx`** (1 test) — heading "Designed for Performance" + all 6 feature titles present.

### 10.3 Existing test infrastructure reused

- `src/test/render.tsx` — project render helper wrapping `QueryClientProvider` + MSW integration. Used by Hero, CtaSection, HeroFeaturedParcel tests.
- `src/test/msw/handlers.ts` — `refreshSuccess` + `refreshUnauthenticated` handlers for auth-aware test cases.
- `vi.mock("next-themes")` — per-test override for `useTheme()` in HeroFeaturedParcel and FeatureCard tests. The existing `vitest.setup.ts` already mocks `next/font/google` and `next/navigation`, so adding `next-themes` at the per-test level is consistent.

### 10.4 Not tested (scope cuts)

- **Pixel-diff visual regression.** The final verification step manually opens `http://localhost:3000` in both light and dark themes and compares against `docs/stitch_generated-design/{light,dark}_mode/landing_page/screen.png`.
- **Responsive breakpoint collapse.** Manual verification at mobile (<768px), tablet (768-1024px), desktop (>1024px). The grid/flex classes are declarative and Tailwind's built-in breakpoint utilities don't need custom tests.
- **`next/image` internals.** We trust Next.js's own test coverage. We verify `Image` is invoked with the correct src; we don't verify the image actually loads.
- **E2E / Playwright.** Out of scope per Q5e. The substantive behavior (auth-aware CTAs) is fully covered by component tests with mocked `useAuth()`.

## 11. Design System Conformance

**Colors:** all tokens, no hex literals. Verified against `frontend/scripts/verify-no-hex-colors.sh`. The bento grid uses `bg-surface-container`, `bg-primary-container`, `bg-inverse-surface`. Text uses `text-on-surface`, `text-on-surface-variant`, `text-on-primary-container`, `text-inverse-on-surface`, `text-white/60` (opacity-modified white is the idiomatic way to express "subtitle text on a dark card" in Tailwind and is already used elsewhere in the codebase — NOT a hex literal).

**Typography:** `font-display` (Manrope) on all h1/h2/h3 headings per DESIGN.md §3. `text-on-surface` for body copy. No arbitrary font-size values — all via Tailwind's scale utilities.

**Spacing:** `py-32` for major sections (generous top-padding "to let the content breathe" per DESIGN.md §3). `px-8` gutters matching the rest of the app.

**Elevation:** `shadow-elevated` for the HeroFeaturedParcel card. `shadow-soft` for FeatureCard via the existing Button primitive's class. No hand-rolled shadows.

**No-Line Rule (DESIGN.md §2):** every section transition uses a background shift, not a divider. Hero → HowItWorks uses `bg-surface` → `bg-surface-container-low`. HowItWorks → Features uses `bg-surface-container-low` → `bg-surface`. Features → CTA uses `bg-surface` → (CTA has its own gradient container). Zero `border-*` classes for sectioning.

**Intentional asymmetry (DESIGN.md §1):** the Hero uses a 7/5 editorial split. The Features bento alternates 2-span / 1-span cards. The How-It-Works grid is symmetric (4 equal columns) — this is fine because the asymmetry doesn't need to pervade every section, just the hero and the showcase grids.

**Glass & Gradient (DESIGN.md §2):** the primary CTA buttons inherit the `from-primary to-primary-container` gradient via the existing `Button variant="primary"` class. The CtaSection also uses `bg-gradient-to-br from-primary to-primary-container`. Both are consistent with the design system rule.

**Dark mode first-class:** all components render cleanly in both `light` and `dark` via `next-themes`. No `dark:` variant classes are used — the design-system tokens auto-swap based on the active theme class on the root. This is verified by the existing `verify:no-dark-variants.sh` script, which the plan will run.

## 12. File Inventory

### 12.1 Create (12 files)

**Components (9 files):**
- `frontend/src/components/marketing/LivePill.tsx`
- `frontend/src/components/marketing/LivePill.test.tsx`
- `frontend/src/components/marketing/Hero.tsx`
- `frontend/src/components/marketing/Hero.test.tsx`
- `frontend/src/components/marketing/HeroFeaturedParcel.tsx`
- `frontend/src/components/marketing/HeroFeaturedParcel.test.tsx`
- `frontend/src/components/marketing/HowItWorksSection.tsx`
- `frontend/src/components/marketing/HowItWorksSection.test.tsx`
- `frontend/src/components/marketing/HowItWorksStep.tsx`
- `frontend/src/components/marketing/HowItWorksStep.test.tsx`
- `frontend/src/components/marketing/FeaturesSection.tsx`
- `frontend/src/components/marketing/FeaturesSection.test.tsx`
- `frontend/src/components/marketing/FeatureCard.tsx`
- `frontend/src/components/marketing/FeatureCard.test.tsx`
- `frontend/src/components/marketing/CtaSection.tsx`
- `frontend/src/components/marketing/CtaSection.test.tsx`

That's 8 production files + 8 test files = **16 new code files in `components/marketing/`**.

**Assets (3 files, already downloaded in worktree):**
- `frontend/public/landing/hero-parcel-light.png`
- `frontend/public/landing/hero-parcel-dark.png`
- `frontend/public/landing/bidding-bg.png`

### 12.2 Modify (3 files)

- `frontend/src/app/page.tsx` — rewrite from stub to composition of 4 marketing sections.
- `frontend/src/components/ui/icons.ts` — add 10 new lucide re-exports.
- `README.md` — sweep for staleness, mention the landing page being live at `/`.

### 12.3 Possibly modify (1 file)

- `frontend/package.json` — verify `next-themes` is already a dependency. It is (added in Task 01-06). No change needed.

### 12.4 Delete

Nothing.

## 13. FOOTGUNS Additions

One or two new entries in the frontend section (`§F`) of `docs/implementation/FOOTGUNS.md`. Numbers continue from the existing §F.19 added in Task 01-09.

### §F.20 `LivePill` has no "use client" directive — do not add hooks

**Rule:** `components/marketing/LivePill.tsx` is a Server Component by design. It has no `"use client"` directive so it can be composed from both Server Component parents (HowItWorksSection, FeaturesSection) and Client Component parents (Hero, CtaSection) without forcing the entire parent tree to cross the client boundary. Adding `useEffect`, `useState`, or any React hook WITHOUT adding `"use client"` will build-error when imported from a Server parent. Adding `"use client"` unnecessarily ships the pulse animation's JS to every page consumer.

**Why:** Task 01-10 locked this shape in the brainstorm. The animated ping dot is achieved via Tailwind's `animate-ping` class — pure CSS, zero JavaScript. The component takes one prop (`children: ReactNode`) and that's enough. If future work needs per-instance state (e.g., fading the pill in/out on hover with JS), extract a separate `LivePillInteractive.tsx` client component instead of mutating the base.

**How to apply:** when touching `LivePill.tsx`, read the header comment first. If you're tempted to add a hook, stop and ask: does this really need JavaScript, or can Tailwind's animation utilities handle it? If JavaScript is required, create a new component with `"use client"` rather than breaking the existing one's dual-composability contract.

### §F.21 `next-themes` + SSR: use the `mounted` hydration guard pattern

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

**How to apply:** when writing a component that needs theme-dependent rendering, copy the 4-line `mounted` guard verbatim. Do not use `theme` instead of `resolvedTheme` — `theme` can be `"system"`, which isn't a renderable value.

## 14. Out Of Scope / Followups

1. **Task 07-04 homepage featured view.** The marketing components (`Hero`, `FeatureCard`, `HowItWorksStep`, etc.) are placed in `components/marketing/` specifically so Task 07-04 can import them for the post-auth home experience. Task 07-04 will either (a) reuse `FeatureCard` for a "featured auctions" grid or (b) extract a new `ParcelCard` primitive that shares `FeatureCard`'s layout DNA.
2. **Stats Bar rehydration.** Dropped in Task 01-10 per user directive. If the platform ships real stats later, add a `StatsBar` component to `components/marketing/` that reads from a backend stats endpoint (Phase 2+).
3. **User-testimonial carousel.** A modern landing page often has real user quotes. SLPA has zero real users today. When that changes, add a `TestimonialSection` component. Intentionally omitted from scope.
4. **Copy rewrite.** Most of the page copy is lifted verbatim from the Stitch reference. A real marketing/brand pass would rewrite it. Out of scope — this task ships "placeholder copy that sounds good", not a brand voice exercise.
5. **Animated hero entrance.** The Stitch reference is static. Future polish could add `framer-motion` entrance animations. Intentionally omitted — adds dependency + complexity for visual delta.
6. **E2E tests via Playwright.** Covered by component tests. If the page grows in behavioral complexity (e.g., lead-gen forms), revisit.
7. **A/B testing framework.** Marketing pages benefit from experimentation; we have none yet. Revisit when there's something to measure.
8. **SEO metadata.** The `app/page.tsx` does not export a `metadata` constant in this task. The `RootLayout` already provides `title.default = "SLPA — Second Life Parcel Auctions"` and a meta description, which is sufficient for `/`. Page-specific metadata (`og:image`, `twitter:card`) is a Phase 2 SEO pass.

## 15. Acceptance Criteria Mapping

From `task-10-landing-page.md`:

| Brief AC | Covered by |
|---|---|
| Landing page loads at `/` for unauthenticated users | §5, `app/page.tsx` rewrite, Hero/CtaSection auth-branching logic |
| All sections render correctly in both dark and light mode | §6 (every section uses semantic tokens), §11 design-system conformance, §10.1 HeroFeaturedParcel/FeatureCard theme-swap tests, manual verification step |
| CTAs link to the correct pages | §6.2 Hero tests, §6.8 CtaSection tests |
| Responsive on mobile, tablet, and desktop | §5 grid/flex classes with Tailwind breakpoints, manual verification |
| Page matches both the light and dark mode Stitch references | Manual verification step — open `http://localhost:3000` in both themes and compare against `screen.png` files |
| Every visually distinct section is its own component, composed in `app/page.tsx` | §5, §6, §12.1 component inventory — 8 marketing components composed from 4 section wrappers in `page.tsx` |
| No authentication required to view | §2 brief alignment — redirect dropped, landing always renders |

## 16. Risks and Mitigations

| Risk | Mitigation |
|---|---|
| Hydration mismatch on theme swap (HeroFeaturedParcel, FeatureCard) | §13 F.21 footgun + `mounted` guard pattern verbatim in both components |
| `LivePill` dual-composability gets broken by a future hook addition | §13 F.20 footgun + explicit load-bearing comment in `LivePill.tsx` header |
| `verify:no-inline-styles` fails on the CtaSection radial-dot pattern | Plan picks ONE path (add to allowlist OR move pattern to `globals.css`) before implementation begins. Covered in §6.8 |
| Stitch CDN images 404 in the future | Images already downloaded and committed to `frontend/public/landing/` — no runtime dependency on the CDN |
| Authenticated user loads `/` → sees Bottom CTA flash briefly before `useAuth()` resolves → CtaSection returns null → re-render | `useAuth()` returns `{status: "loading"}` during bootstrap, then `"authenticated"` or `"unauthenticated"`. `CtaSection` treats `"loading"` as "not authenticated yet" — shows the CTA. This is the correct behavior: returning users see the CTA for 100-200ms during bootstrap, then it disappears if they're logged in. An acceptable trade-off vs. hiding all content during the loading state. |
| Hero image assets (800KB total) load eagerly and degrade LCP | `HeroFeaturedParcel` uses `priority` on the `<Image>` component (LCP hint). `FeatureCard` background image is decorative and NOT priority — `next/image` lazy-loads it when scrolled into view |
| Features section's client boundary ships all 6 cards as client-bundled JS even though only 1 has backgroundImage | Acceptable cost per §6.7 alternative-considered note. If bundle size becomes a concern, split into `FeatureCard` (server) + `FeatureCardWithImage` (client). |
| Copy-verbatim from Stitch contains marketing-speak the user later wants rewritten | Captured as §14 followup #4. No scope cut. |

## 17. Self-Review Notes

Controller's own sanity check before handing off to `writing-plans`. Remove before merge if policy says so — leaving here for plan-writer visibility.

**Placeholder scan:** no TBDs, no "implement later". Every component has full code. Every test has exact assertion guidance.

**Internal consistency:**
- §5 (page structure), §6 (component specs), §10 (testing), §12 (file inventory), §13 (footguns) all agree on: 8 new components + 10 icon additions + `page.tsx` rewrite + 3 image assets + 2 footgun entries.
- Sample code in §6.4 and §6.6 reviewed for import hygiene — unused imports (`Gavel` in `FeaturesSection`, `BadgeCheck` in `HowItWorksSection`) removed inline so the spec code is copy-paste-ready.

**Scope check:** single subsystem (public marketing page), pure additive + one stub replacement. Not decomposable — the 4 sections are interdependent as a composed experience, and shipping half would be worse than shipping nothing.

**Ambiguity check:**
- "Authenticated user sees `CtaSection`?" — no, returns null (§5.4, §6.8, §10.1).
- "Who owns the `mounted` hydration guard?" — both `HeroFeaturedParcel` and `FeatureCard` (§6.3, §6.7), with `next-themes` footgun §F.21 as the rule.
- "Is the `bidding-bg.png` image used in both themes?" — yes, single file, see §8 and the `FeaturesSection` props in §6.6.
- "Does `FeatureCard` stay a Server Component when `backgroundImage` is omitted?" — no, it's a Client Component across the board (§6.7 alternative-considered).
- "Is the inline style in CtaSection OK?" — it's an exception that the plan must resolve one of two ways (§6.8).
