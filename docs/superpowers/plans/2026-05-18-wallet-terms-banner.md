# Wallet Terms Banner Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a site-wide, dismissible "Accept Wallet Terms" banner and make the listing-fee hard gate open the existing wallet-terms modal instead of a bespoke section.

**Architecture:** A new client `WalletTermsBanner` is slotted into `AppShell` between `<Header />` and `<main>`. It reads `useAuth` + `useWallet`, shows only for verified users who haven't accepted terms and haven't dismissed the nudge (per-browser, versioned `localStorage`). The existing reusable `WalletTermsModal` is reused unchanged. `ActivateListingPanel` drops its bespoke gate section and opens that modal on the blocked Activate click. No backend changes; no new context/provider.

**Tech Stack:** Next.js 16 / React 19 / TypeScript 5 / Tailwind CSS 4, TanStack Query, Vitest + Testing Library + MSW.

Spec: `docs/superpowers/specs/2026-05-18-wallet-terms-modal-everywhere-design.md`

---

## File Structure

New:

- `frontend/src/lib/wallet/terms-banner-dismissed.ts` — SSR-safe `localStorage` get/set for the per-browser dismissal, keyed by terms version (version passed in as an argument so this lib has no component dependency).
- `frontend/src/lib/wallet/terms-banner-dismissed.test.ts` — unit tests for the helper.
- `frontend/src/components/wallet/WalletTermsBanner.tsx` — the site-wide banner client component.
- `frontend/src/components/wallet/WalletTermsBanner.test.tsx` — banner visibility + action tests.

Modified:

- `frontend/src/components/layout/AppShell.tsx` — insert `<WalletTermsBanner />` after `<Header />`.
- `frontend/src/components/listing/ActivateListingPanel.tsx` — remove the bespoke terms section; open the modal from the Activate click and from the `WALLET_TERMS_NOT_ACCEPTED` error branch.
- `frontend/src/components/listing/ActivateListingPanel.test.tsx` — rewrite the terms-gate test for the new flow.
- `README.md` — staleness sweep at the end (project convention).

Key existing APIs (verified against the codebase):

- `useAuth(): AuthSession` from `@/lib/auth` — `{ status: "loading" | "authenticated" | "unauthenticated", user: AuthUser | null }`; `AuthUser.verified: boolean`.
- `useWallet(enabled: boolean)` from `@/lib/wallet/use-wallet` — `useQuery<WalletView>`; `WalletView.termsAccepted: boolean`. When `enabled` is false the query is disabled and `data` is `undefined`.
- `WalletTermsModal` from `@/components/wallet/WalletTermsModal` — props `{ open: boolean; onClose: () => void; onAccepted?: () => void }`. It already invalidates `walletQueryKey` on accept. `WALLET_TERMS_VERSION` (`"1.0"`) is exported from the same module.
- `Button` from `@/components/ui/Button` — supports `variant="primary" | "secondary" | "tertiary"` and `size="sm"`.
- Icons `Wallet` and `X` are exported from `@/components/ui/icons`.
- Test helpers from `@/test/render`: `renderWithProviders(ui, { auth: "authenticated" | "anonymous", authUser })`, `screen`, `userEvent`, `waitFor`. `mockUser` (from `@/test/msw/fixtures`) defaults `verified: false`.

---

## Task 1: localStorage dismissal helper

**Files:**
- Create: `frontend/src/lib/wallet/terms-banner-dismissed.ts`
- Test: `frontend/src/lib/wallet/terms-banner-dismissed.test.ts`

- [ ] **Step 1: Write the failing test**

Create `frontend/src/lib/wallet/terms-banner-dismissed.test.ts`:

```ts
import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import {
  termsBannerDismissalKey,
  isTermsBannerDismissed,
  dismissTermsBanner,
} from "./terms-banner-dismissed";

describe("terms-banner-dismissed", () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("namespaces the key by terms version", () => {
    expect(termsBannerDismissalKey("1.0")).toBe(
      "slpa.walletTermsBannerDismissed.v1.0",
    );
    expect(termsBannerDismissalKey("2.3")).toBe(
      "slpa.walletTermsBannerDismissed.v2.3",
    );
  });

  it("defaults to not-dismissed", () => {
    expect(isTermsBannerDismissed("1.0")).toBe(false);
  });

  it("round-trips a dismissal for a given version", () => {
    dismissTermsBanner("1.0");
    expect(isTermsBannerDismissed("1.0")).toBe(true);
  });

  it("dismissal is scoped to the version that set it", () => {
    dismissTermsBanner("1.0");
    expect(isTermsBannerDismissed("2.0")).toBe(false);
  });

  it("is SSR-safe: no window means not-dismissed and no throw", () => {
    vi.stubGlobal("window", undefined);
    expect(isTermsBannerDismissed("1.0")).toBe(false);
    expect(() => dismissTermsBanner("1.0")).not.toThrow();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/lib/wallet/terms-banner-dismissed.test.ts`
Expected: FAIL — `Failed to resolve import "./terms-banner-dismissed"`.

- [ ] **Step 3: Write minimal implementation**

Create `frontend/src/lib/wallet/terms-banner-dismissed.ts`:

```ts
/**
 * Per-browser dismissal state for the site-wide wallet-terms nudge banner
 * (see WalletTermsBanner). This is purely a UI nudge suppressor — the real
 * acceptance state lives server-side on the user. The key is namespaced by
 * the accepted terms version so a future terms bump re-shows the banner to
 * users who previously dismissed it, with no server change.
 *
 * The version is passed in by the caller rather than imported here so this
 * module has no dependency on the modal component and stays trivially
 * unit-testable. All access is guarded for SSR / the Amplify build, where
 * `window` is undefined.
 */
export function termsBannerDismissalKey(version: string): string {
  return `slpa.walletTermsBannerDismissed.v${version}`;
}

export function isTermsBannerDismissed(version: string): boolean {
  if (typeof window === "undefined") return false;
  try {
    return window.localStorage.getItem(termsBannerDismissalKey(version)) === "1";
  } catch {
    // localStorage can throw in private mode / when disabled — treat as
    // not-dismissed so the banner still nudges.
    return false;
  }
}

export function dismissTermsBanner(version: string): void {
  if (typeof window === "undefined") return;
  try {
    window.localStorage.setItem(termsBannerDismissalKey(version), "1");
  } catch {
    // Best-effort: a failed write just means the banner shows again next
    // load. Acceptable for a nudge.
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/lib/wallet/terms-banner-dismissed.test.ts`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/lib/wallet/terms-banner-dismissed.ts frontend/src/lib/wallet/terms-banner-dismissed.test.ts
git commit -m "feat(wallet): per-browser versioned terms-banner dismissal helper"
```

---

## Task 2: WalletTermsBanner component

**Files:**
- Create: `frontend/src/components/wallet/WalletTermsBanner.tsx`
- Test: `frontend/src/components/wallet/WalletTermsBanner.test.tsx`

- [ ] **Step 1: Write the failing test**

Create `frontend/src/components/wallet/WalletTermsBanner.test.tsx`:

```tsx
import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
} from "@/test/render";
import type { WalletView } from "@/types/wallet";
import { mockUser } from "@/test/msw/fixtures";
import { WALLET_TERMS_VERSION } from "@/components/wallet/WalletTermsModal";
import { termsBannerDismissalKey } from "@/lib/wallet/terms-banner-dismissed";

vi.mock("@/lib/auth", () => ({
  useAuth: vi.fn(),
}));

vi.mock("@/lib/wallet/use-wallet", async () => {
  const actual = await vi.importActual<
    typeof import("@/lib/wallet/use-wallet")
  >("@/lib/wallet/use-wallet");
  return { ...actual, useWallet: vi.fn() };
});

import { useAuth } from "@/lib/auth";
import { useWallet } from "@/lib/wallet/use-wallet";
import { WalletTermsBanner } from "./WalletTermsBanner";

function walletView(overrides: Partial<WalletView> = {}): WalletView {
  return {
    balance: 0,
    reserved: 0,
    available: 0,
    penaltyOwed: 0,
    queuedForWithdrawal: 0,
    termsAccepted: false,
    termsVersion: null,
    termsAcceptedAt: null,
    recentLedger: [],
    ...overrides,
  };
}

function setAuth(opts: { authenticated: boolean; verified?: boolean }) {
  vi.mocked(useAuth).mockReturnValue(
    opts.authenticated
      ? {
          status: "authenticated",
          user: { ...mockUser, verified: opts.verified ?? true },
        }
      : { status: "unauthenticated", user: null },
  );
}

function setWallet(data: WalletView | undefined) {
  vi.mocked(useWallet).mockReturnValue({
    data,
  } as unknown as ReturnType<typeof useWallet>);
}

const BANNER = /Accept the SLParcels Wallet Terms of Use/i;

describe("WalletTermsBanner", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.localStorage.clear();
  });

  it("shows for a verified user who has not accepted terms", () => {
    setAuth({ authenticated: true, verified: true });
    setWallet(walletView({ termsAccepted: false }));
    renderWithProviders(<WalletTermsBanner />);
    expect(screen.getByText(BANNER)).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /Accept Wallet Terms/i }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /Don't show again/i }),
    ).toBeInTheDocument();
  });

  it("is hidden for guests / unauthenticated", () => {
    setAuth({ authenticated: false });
    setWallet(undefined);
    renderWithProviders(<WalletTermsBanner />);
    expect(screen.queryByText(BANNER)).not.toBeInTheDocument();
  });

  it("is hidden for an authenticated but unverified user", () => {
    setAuth({ authenticated: true, verified: false });
    setWallet(undefined);
    renderWithProviders(<WalletTermsBanner />);
    expect(screen.queryByText(BANNER)).not.toBeInTheDocument();
  });

  it("renders nothing while the wallet query is loading", () => {
    setAuth({ authenticated: true, verified: true });
    setWallet(undefined);
    renderWithProviders(<WalletTermsBanner />);
    expect(screen.queryByText(BANNER)).not.toBeInTheDocument();
  });

  it("is hidden once terms are accepted", () => {
    setAuth({ authenticated: true, verified: true });
    setWallet(walletView({ termsAccepted: true }));
    renderWithProviders(<WalletTermsBanner />);
    expect(screen.queryByText(BANNER)).not.toBeInTheDocument();
  });

  it("is hidden when dismissed in localStorage for the current version", async () => {
    window.localStorage.setItem(
      termsBannerDismissalKey(WALLET_TERMS_VERSION),
      "1",
    );
    setAuth({ authenticated: true, verified: true });
    setWallet(walletView({ termsAccepted: false }));
    renderWithProviders(<WalletTermsBanner />);
    await waitFor(() =>
      expect(screen.queryByText(BANNER)).not.toBeInTheDocument(),
    );
  });

  it("opens the WalletTermsModal on Accept Wallet Terms", async () => {
    setAuth({ authenticated: true, verified: true });
    setWallet(walletView({ termsAccepted: false }));
    renderWithProviders(<WalletTermsBanner />);
    await userEvent.click(
      screen.getByRole("button", { name: /Accept Wallet Terms/i }),
    );
    expect(
      screen.getByRole("heading", { name: /SLParcels Wallet Terms of Use/i }),
    ).toBeInTheDocument();
  });

  it("Don't show again writes localStorage and hides the banner", async () => {
    setAuth({ authenticated: true, verified: true });
    setWallet(walletView({ termsAccepted: false }));
    renderWithProviders(<WalletTermsBanner />);
    await userEvent.click(
      screen.getByRole("button", { name: /Don't show again/i }),
    );
    expect(
      window.localStorage.getItem(
        termsBannerDismissalKey(WALLET_TERMS_VERSION),
      ),
    ).toBe("1");
    await waitFor(() =>
      expect(screen.queryByText(BANNER)).not.toBeInTheDocument(),
    );
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/components/wallet/WalletTermsBanner.test.tsx`
Expected: FAIL — `Failed to resolve import "./WalletTermsBanner"`.

- [ ] **Step 3: Write minimal implementation**

Create `frontend/src/components/wallet/WalletTermsBanner.tsx`:

```tsx
"use client";

import { useEffect, useState } from "react";
import { Button } from "@/components/ui/Button";
import { Wallet } from "@/components/ui/icons";
import { useAuth } from "@/lib/auth";
import { useWallet } from "@/lib/wallet/use-wallet";
import {
  WalletTermsModal,
  WALLET_TERMS_VERSION,
} from "@/components/wallet/WalletTermsModal";
import {
  isTermsBannerDismissed,
  dismissTermsBanner,
} from "@/lib/wallet/terms-banner-dismissed";

/**
 * Site-wide nudge banner under the global header. Verified users who have
 * not accepted the wallet Terms of Use see a single persistent prompt with
 * a one-click path to the acceptance modal, plus a per-browser "Don't show
 * again" suppressor. Returns null in every other case (guest, unverified,
 * wallet still loading, terms accepted, dismissed).
 *
 * The dismissal only hides this passive nudge. Actions that genuinely
 * cannot proceed without accepted terms (the listing-fee hard gate) still
 * open the same modal — see ActivateListingPanel.
 *
 * Spec: docs/superpowers/specs/2026-05-18-wallet-terms-modal-everywhere-design.md
 */
export function WalletTermsBanner() {
  const { status, user } = useAuth();
  const verified = status === "authenticated" && user?.verified === true;
  const { data: wallet } = useWallet(verified);

  const [dismissed, setDismissed] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);

  // Read the per-browser dismissal only after mount. localStorage is
  // unavailable during SSR / the Amplify build, and reading it during
  // render would desync hydration.
  useEffect(() => {
    setDismissed(isTermsBannerDismissed(WALLET_TERMS_VERSION));
  }, []);

  if (!verified) return null;
  if (!wallet) return null; // loading — no flash, no layout jump
  if (wallet.termsAccepted) return null;
  if (dismissed) return null;

  return (
    <>
      <div
        role="region"
        aria-label="Wallet terms"
        className="border-b border-border bg-bg-subtle"
      >
        <div className="mx-auto flex w-full max-w-[var(--container-w)] flex-wrap items-center gap-3 px-6 py-2.5">
          <Wallet className="h-4 w-4 shrink-0 text-brand" aria-hidden />
          <p className="flex-1 text-sm text-fg">
            Accept the SLParcels Wallet Terms of Use to pay listing fees and
            use your wallet.
          </p>
          <Button
            variant="primary"
            size="sm"
            onClick={() => setModalOpen(true)}
          >
            Accept Wallet Terms
          </Button>
          <Button
            variant="tertiary"
            size="sm"
            onClick={() => {
              dismissTermsBanner(WALLET_TERMS_VERSION);
              setDismissed(true);
            }}
          >
            Don&apos;t show again
          </Button>
        </div>
      </div>
      <WalletTermsModal
        open={modalOpen}
        onClose={() => setModalOpen(false)}
      />
    </>
  );
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/components/wallet/WalletTermsBanner.test.tsx`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/wallet/WalletTermsBanner.tsx frontend/src/components/wallet/WalletTermsBanner.test.tsx
git commit -m "feat(wallet): site-wide dismissible wallet-terms nudge banner"
```

---

## Task 3: Wire the banner into AppShell

**Files:**
- Modify: `frontend/src/components/layout/AppShell.tsx`
- Test: `frontend/src/components/layout/AppShell.test.tsx` (run only — no edit expected)

- [ ] **Step 1: Modify AppShell**

Replace the entire contents of `frontend/src/components/layout/AppShell.tsx` with:

```tsx
import { Header } from "./Header";
import { Footer } from "./Footer";
import { WalletTermsBanner } from "@/components/wallet/WalletTermsBanner";

export function AppShell({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex min-h-screen flex-col">
      <Header />
      <WalletTermsBanner />
      <main className="flex-1">{children}</main>
      <Footer />
    </div>
  );
}
```

- [ ] **Step 2: Run the existing AppShell test to verify it still passes**

Run: `cd frontend && npx vitest run src/components/layout/AppShell.test.tsx`
Expected: PASS unchanged. The test mocks `@/lib/auth` `useAuth` → `{ status: "unauthenticated", user: null }`, so `WalletTermsBanner` computes `verified = false` and returns null; Header/main/Footer assertions are unaffected.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/components/layout/AppShell.tsx
git commit -m "feat(wallet): mount WalletTermsBanner site-wide under the header"
```

---

## Task 4: ActivateListingPanel opens the modal instead of a bespoke section

**Files:**
- Modify: `frontend/src/components/listing/ActivateListingPanel.tsx`
- Test: `frontend/src/components/listing/ActivateListingPanel.test.tsx`

Context: today the panel has a top-precedence `if (!wallet.termsAccepted)` branch rendering a bespoke "Accept wallet terms first" section (current lines ~92–120), and an `onError` branch that lumps `WALLET_TERMS_NOT_ACCEPTED` in with balance/penalty codes to silently invalidate the wallet query. After this task: the bespoke section is gone; the normal "Activate this listing" branch handles a not-accepted-but-funded seller by opening the modal on click; the error branch opens the modal too (covers a terms-lapse race).

- [ ] **Step 1: Rewrite the failing test**

In `frontend/src/components/listing/ActivateListingPanel.test.tsx`, replace the test block titled `"shows the wallet-terms gate with an inline Accept button when terms are not accepted"` (current lines 79–98) with these two tests:

```tsx
  it("opens the WalletTermsModal from the Activate button when terms are not accepted", async () => {
    mockHooks({
      fee: 100,
      wallet: walletView({
        termsAccepted: false,
        termsAcceptedAt: null,
        balance: 1000,
        available: 1000,
      }),
    });
    renderWithProviders(<ActivateListingPanel auctionPublicId="00000000-0000-0000-0000-00000000002a" />);

    // The normal Activate affordance is shown (no bespoke gate section).
    expect(
      screen.queryByRole("heading", { name: /Accept wallet terms first/i }),
    ).not.toBeInTheDocument();

    await userEvent.click(
      screen.getByRole("button", { name: /Activate Listing/i }),
    );

    // Clicking Activate opens the modal in-place rather than paying.
    expect(
      screen.getByRole("heading", { name: /SLParcels Wallet Terms of Use/i }),
    ).toBeInTheDocument();
  });

  it("pays the listing fee after accepting terms in the modal", async () => {
    let posted = false;
    server.use(
      http.post("*/api/v1/me/wallet/accept-terms", () =>
        new HttpResponse(null, { status: 204 }),
      ),
      http.post("*/api/v1/me/auctions/00000000-0000-0000-0000-00000000002a/pay-listing-fee", async () => {
        posted = true;
        return HttpResponse.json({
          newBalance: 900,
          newAvailable: 900,
          auctionStatus: "DRAFT_PAID",
        });
      }),
    );
    mockHooks({
      fee: 100,
      wallet: walletView({
        termsAccepted: false,
        termsAcceptedAt: null,
        balance: 1000,
        available: 1000,
      }),
    });
    renderWithProviders(<ActivateListingPanel auctionPublicId="00000000-0000-0000-0000-00000000002a" />);

    await userEvent.click(
      screen.getByRole("button", { name: /Activate Listing/i }),
    );
    await userEvent.click(
      screen.getByRole("button", { name: /^I Accept$/i }),
    );
    await waitFor(() => expect(posted).toBe(true));
  });
```

- [ ] **Step 2: Run the test to verify the first one fails**

Run: `cd frontend && npx vitest run src/components/listing/ActivateListingPanel.test.tsx -t "opens the WalletTermsModal from the Activate button"`
Expected: FAIL — today `termsAccepted: false` renders the bespoke "Accept wallet terms first" section and no "Activate Listing" button, so `getByRole("button", { name: /Activate Listing/i })` throws.

- [ ] **Step 3: Remove the bespoke terms section**

In `frontend/src/components/listing/ActivateListingPanel.tsx`, delete the entire block (current lines ~92–120):

```tsx
  if (!wallet.termsAccepted) {
    return (
      <>
        <section
          aria-labelledby="activate-fee-heading"
          className="flex flex-col gap-3 rounded-lg bg-bg-subtle p-6"
        >
          <h2 id="activate-fee-heading" className="text-base font-bold tracking-tight text-fg">
            Accept wallet terms first
          </h2>
          <p className="text-sm text-fg">
            Listing fees are paid from your SLParcels wallet. Accept the wallet
            terms of use to continue.
          </p>
          <Button
            variant="secondary"
            className="self-start"
            onClick={() => setTermsOpen(true)}
          >
            Accept wallet terms
          </Button>
        </section>
        <WalletTermsModal
          open={termsOpen}
          onClose={() => setTermsOpen(false)}
        />
      </>
    );
  }
```

Leave the `const [termsOpen, setTermsOpen] = useState(false);` declaration in place — it is reused below.

- [ ] **Step 4: Open the modal from the error branch**

In the `onError` handler, find this block:

```tsx
        if (
          code === "WALLET_TERMS_NOT_ACCEPTED" ||
          code === "INSUFFICIENT_AVAILABLE_BALANCE" ||
          code === "PENALTY_OUTSTANDING"
        ) {
          qc.invalidateQueries({ queryKey: walletQueryKey });
          return;
        }
```

Replace it with:

```tsx
        if (code === "WALLET_TERMS_NOT_ACCEPTED") {
          // Terms lapsed between render and click (admin cleared them, or a
          // terms-version bump). Open the modal rather than silently
          // refetching — the user needs an actionable path.
          setTermsOpen(true);
          return;
        }
        if (
          code === "INSUFFICIENT_AVAILABLE_BALANCE" ||
          code === "PENALTY_OUTSTANDING"
        ) {
          qc.invalidateQueries({ queryKey: walletQueryKey });
          return;
        }
```

- [ ] **Step 5: Gate the Activate click on terms and mount the modal**

Replace the final `return ( ... )` block (the "Activate this listing" / ready branch, current lines ~173–195) with:

```tsx
  return (
    <>
      <section
        aria-labelledby="activate-fee-heading"
        className="flex flex-col gap-3 rounded-lg bg-bg-subtle p-6"
      >
        <h2 id="activate-fee-heading" className="text-base font-bold tracking-tight text-fg">
          Activate this listing
        </h2>
        <p className="text-sm text-fg">
          Listing fee is <strong>{formatLindens(fee)}</strong>, debited from
          your SLParcels wallet. Available balance:{" "}
          <strong>{formatLindens(wallet.available)}</strong>.
        </p>
        <FormError message={error ?? undefined} />
        <Button
          onClick={() => {
            if (!wallet.termsAccepted) {
              setTermsOpen(true);
              return;
            }
            mutation.mutate();
          }}
          loading={mutation.isPending}
          disabled={mutation.isPending}
        >
          Activate Listing
        </Button>
      </section>
      <WalletTermsModal
        open={termsOpen}
        onClose={() => setTermsOpen(false)}
        onAccepted={() => mutation.mutate()}
      />
    </>
  );
```

- [ ] **Step 6: Run the full ActivateListingPanel suite**

Run: `cd frontend && npx vitest run src/components/listing/ActivateListingPanel.test.tsx`
Expected: PASS. All branch tests (loading, penalty, top-up, ready, generic 500, coded-error-no-inline) plus the two new terms tests pass.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/components/listing/ActivateListingPanel.tsx frontend/src/components/listing/ActivateListingPanel.test.tsx
git commit -m "feat(wallet): activate-listing opens terms modal instead of bespoke gate"
```

---

## Task 5: README sweep + full local verification + PR

**Files:**
- Modify: `README.md` (only if a wallet-terms surface description is now stale)

- [ ] **Step 1: README staleness sweep**

Open `README.md` and search for any description of how wallet-terms acceptance is surfaced (e.g. the activate-listing flow, wallet page). If the prose describes the old bespoke "Accept wallet terms first" section or implies per-surface warnings, update it to describe the site-wide banner + modal-on-blocked-action behavior. If `README.md` says nothing about wallet-terms surfacing, make no change (do not invent a section).

- [ ] **Step 2: Run the frontend guard + test + lint + build chain**

Run (CI is main-only, so these must pass locally before pushing — see project memory `project_ci_main_only_temporarily`):

```bash
cd frontend && npm run lint && npm test && npm run verify && npm run build
```

Expected: lint clean; full Vitest suite green (new banner + helper tests, rewritten ActivateListingPanel tests, untouched AppShell test); verify guards (no-dark-variants, no-hex-colors, no-inline-styles, coverage) pass; production build succeeds.

If `npm run build` surfaces an SSR/prerender issue on a page that renders `AppShell`, confirm `WalletTermsBanner` returns `null` during SSR (it does: `useWallet` is disabled until `verified`, and the dismissal read is in a post-mount `useEffect`). Do not add `force-dynamic` — the banner is already SSR-null.

- [ ] **Step 3: Commit any README change**

Only if Step 1 changed `README.md`:

```bash
git add README.md
git commit -m "docs(readme): describe site-wide wallet-terms banner"
```

- [ ] **Step 4: Push and open the PR into dev**

```bash
git push -u origin feat/wallet-terms-banner
gh pr create --base dev --head feat/wallet-terms-banner \
  --title "Site-wide wallet-terms banner + modal on blocked actions" \
  --body "Implements docs/superpowers/specs/2026-05-18-wallet-terms-modal-everywhere-design.md.

- New site-wide dismissible WalletTermsBanner under the header (verified + not-accepted + not-dismissed).
- Versioned per-browser localStorage dismissal helper (SSR-safe).
- ActivateListingPanel opens the existing WalletTermsModal on the blocked Activate click and on the WALLET_TERMS_NOT_ACCEPTED error, replacing the bespoke gate section.
- Withdraw/pay-penalty unchanged; Realty-Groups leader-terms surfaces out of scope; no backend changes."
```

- [ ] **Step 5: Merge the PR into dev**

```bash
gh pr merge --merge --delete-branch
```

(Per project convention Claude merges into `dev`; the `dev` → `main` PR is the user's.)

---

## Self-Review (completed by plan author)

**Spec coverage:**
- Site-wide banner in AppShell → Tasks 2, 3.
- Versioned localStorage dismissal, SSR-safe → Task 1.
- Banner visibility rules (verified / not-accepted / loading-null / dismissed) → Task 2 tests.
- "Don't show again" + "Accept Wallet Terms" actions → Task 2.
- Cancelling the modal leaves the banner (derived state) → covered implicitly: banner visibility derives from `wallet.termsAccepted` + `dismissed`; `onClose` toggles only `modalOpen`. Task 2's "opens modal" test plus the visibility tests exercise this; no extra task needed.
- Hard gate opens modal; error-branch opens modal → Task 4.
- "Don't show again" does not suppress the hard gate → structural: the banner's `dismissed` state is local to the banner and never read by ActivateListingPanel, which gates on `wallet.termsAccepted` only. No shared flag exists, so the property holds by construction.
- Informational (not warning-red) styling, icon from icons.ts, no emoji, a11y region/buttons → Task 2 implementation.
- Out of scope (leader-terms, withdraw/pay-penalty, backend) → no tasks touch those files; explicitly noted.
- README sweep → Task 5.

**Placeholder scan:** No TBD/TODO/"handle edge cases"/"similar to Task N". Every code step contains complete code. Commands have expected output.

**Type consistency:** `isTermsBannerDismissed(version: string)` / `dismissTermsBanner(version: string)` / `termsBannerDismissalKey(version: string)` signatures are identical across Task 1 (definition), Task 1 tests, and Task 2 (callers pass `WALLET_TERMS_VERSION`). `useAuth()` destructured as `{ status, user }` matches the real `AuthSession` shape. `useWallet(verified)` `.data` typed `WalletView | undefined`; `WalletView.termsAccepted` used consistently. `WalletTermsModal` props (`open`, `onClose`, `onAccepted`) match the existing component. `WALLET_TERMS_VERSION` imported from `@/components/wallet/WalletTermsModal` in both the banner and its test.
