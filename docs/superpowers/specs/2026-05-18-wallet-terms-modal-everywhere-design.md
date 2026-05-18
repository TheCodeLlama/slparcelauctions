# Wallet Terms: site-wide nudge banner + modal on blocked actions

Date: 2026-05-18
Status: Approved (brainstorming)

## Problem

Wallet Terms of Use acceptance is currently surfaced inconsistently. Some
places do the right thing (the `/wallet` page's "How to Deposit" button pops
`WalletTermsModal` when terms are unaccepted). Others present a passive
warning that terms "must be accepted" with no direct path to accept:

- `ActivateListingPanel` renders a bespoke "Accept wallet terms first"
  section with a button (a CTA, but a one-off surface, not the modal-first
  flow used elsewhere).
- `GroupWithdrawModal` shows a plain inline error string on
  `LEADER_TERMS_NOT_ACCEPTED`.
- `LeaderTermsBlockBanner` is a passive warning with no action.

The desired behaviour: any place that requires the *current user's own*
wallet terms should lead straight to the acceptance modal, and there should
be a single, site-wide, dismissible prompt rather than scattered per-surface
warnings.

## Backend reality (unchanged)

Wallet-terms acceptance is a **UX gate, not a transactional one**
(`User.java` comment; spec `2026-04-30-wallet-model-design.md` §"deposit
endpoint itself doesn't gate"). The only endpoint that throws
`WALLET_TERMS_NOT_ACCEPTED` is `POST /me/auctions/{id}/pay-listing-fee`.
`withdraw` and `pay-penalty` do **not** gate on terms server-side. No
backend changes are made by this work.

## Decisions

1. **Trigger model: both.** A persistent, site-wide, dismissible banner
   *and* the modal auto-opening when an action genuinely cannot proceed
   without accepted terms (the `pay-listing-fee` hard gate).
2. **Banner is the persistent CTA**, not per-surface inline sections. It
   lives once, globally, under the nav.
3. **Dismissal persists in `localStorage`** (per-browser). No backend
   change. The real state (terms accepted) still lives server-side; the
   localStorage flag only hides the passive nudge.
4. **Withdraw / pay-penalty are NOT gated.** Withdrawing one's own funds
   must not be trapped behind terms, and a user has no pathway to accrue a
   penalty without first having accepted terms (the only terms-gated
   action, `pay-listing-fee`, requires acceptance and is upstream of any
   penalty).
5. **Realty-Groups leader-terms surfaces are out of scope.**
   `LeaderTermsBlockBanner` and the `GroupWithdrawModal`
   `LEADER_TERMS_NOT_ACCEPTED` error are left untouched. They concern a
   *different* condition (the *leader's* terms, which a non-leader member
   cannot fix from a modal). A leader who has not accepted will see the new
   global banner site-wide and can accept in one click, which also unblocks
   their group's withdrawals.

## Architecture (Approach A)

The reusable `WalletTermsModal` already exists and is unchanged. Two new
units, plus one call-site change. No new context/provider — there is exactly
one genuine hard-gate consumer today; a provider would be premature.

### New: `frontend/src/components/wallet/WalletTermsBanner.tsx` (`"use client"`)

A non-sticky strip rendered in `AppShell` directly after `<Header />` and
before `<main>`:

```
<Header />              ← sticky, z-50, unchanged
<WalletTermsBanner />   ← new, non-sticky
<main>{children}</main>
<Footer />
```

`AppShell` stays a server component; the banner is a client component
(needs `useWallet`, `useAuth`, localStorage). Inserting a client component
inside a server component is fine.

Visibility — render the banner only when **all** hold:

- `useAuth` status is `authenticated` and `user.verified === true`
  (guests/unverified have no wallet).
- `useWallet(verified).data?.termsAccepted === false`.
- not dismissed in localStorage.

While `wallet` is `undefined` (loading) the banner renders nothing — no
hydration flash, no layout jump. Once `termsAccepted` flips `true` (after
acceptance) the banner unmounts regardless of the localStorage flag.

Actions:

- **"Accept Wallet Terms"** → opens `WalletTermsModal`. On accept the
  wallet query invalidates (the modal already does this), `termsAccepted`
  flips `true`, the banner unmounts.
- **"Don't show again"** → writes the localStorage key; the banner hides
  immediately.

Cancelling the modal (without accepting) changes nothing: banner visibility
is derived state, so the banner simply remains until terms are accepted or
"Don't show again" is used.

### New: `frontend/src/lib/wallet/terms-banner-dismissed.ts`

SSR-safe get/set helpers around a versioned localStorage key:

```
slpa.walletTermsBannerDismissed.v<WALLET_TERMS_VERSION>
```

`WALLET_TERMS_VERSION` is the existing constant exported from
`WalletTermsModal.tsx` (`"1.0"`). Versioning the key means a future terms
bump naturally re-nudges previously dismissed users for free, with no server
change. All reads/writes guarded by `typeof window !== "undefined"` so SSR
and the Amplify build never touch `localStorage`.

### Changed: `frontend/src/components/listing/ActivateListingPanel.tsx`

Remove the bespoke "Accept wallet terms first" section (current lines
~92–119). Instead:

- Render the normal "Activate this listing" panel.
- When `!wallet.termsAccepted`, the Activate button's click opens
  `WalletTermsModal` instead of firing the pay mutation.
  `WalletTermsModal.onAccepted` invalidates the wallet and runs the pay
  mutation.
- Change the existing `onError` branch for `WALLET_TERMS_NOT_ACCEPTED` to
  **open the modal** rather than silently invalidating the wallet query.
  This defensively covers a race where terms lapse between render and
  click (e.g. an admin clears terms, or a terms-version bump).

The other render branches (loading, penalty outstanding, insufficient
balance, ready) are unchanged.

### Changed: `frontend/src/components/layout/AppShell.tsx`

Insert `<WalletTermsBanner />` between `<Header />` and `<main>`.

## Behaviour rules

- **"Don't show again" suppresses only the passive banner.** The hard gate
  still pops the modal when the user clicks Activate, because that action
  genuinely cannot proceed server-side without accepted terms. This is
  logically necessary, not configurable.
- The banner is informational, not an error. It uses neutral / brand
  styling (e.g. `bg-bg-subtle` with a brand accent), **not** the red
  `bg-warning-bg` reserved for penalties — not having accepted terms yet is
  not an error state.

## Styling / accessibility / SSR

- Icon from `@/components/ui/icons` (lucide-react). No emoji (project rule).
- No em-dashes or connector en-dashes in user-facing copy (project rule).
- `role="region"` with `aria-label="Wallet terms"`. Both actions are real
  `<button>` elements with discernible accessible names.
- Banner is non-sticky so it never competes with the sticky header's
  `z-50` stacking.
- All `localStorage` access is client-only guarded; the component returns
  `null` during load so server render and first client render agree.

## Testing (Vitest)

- `WalletTermsBanner.test.tsx`:
  - shows for verified + `termsAccepted === false` + not dismissed
  - hidden for guest / unverified
  - hidden when `termsAccepted === true`
  - hidden when the localStorage key is set
  - renders nothing while the wallet query is loading (`undefined` data)
  - "Accept Wallet Terms" opens the modal; banner clears after acceptance
  - "Don't show again" sets the localStorage key and hides the banner
- `terms-banner-dismissed.test.ts`: get/set round-trip; returns `false`
  / no-throw when `window` is undefined (SSR-safe).
- `ActivateListingPanel.test.tsx`: update the existing "shows the
  wallet-terms gate with an inline Accept button" test — the Activate
  click now opens `WalletTermsModal`; on accept, the pay mutation fires.
  Other branch tests unchanged.

## Out of scope

- Realty-Groups leader-terms surfaces (`LeaderTermsBlockBanner`,
  `GroupWithdrawModal` `LEADER_TERMS_NOT_ACCEPTED` error).
- Any withdraw / pay-penalty terms gate.
- Backend changes of any kind.
- Server-side version-aware acceptance re-prompt (only the localStorage
  dismissal key is versioned; the backend `termsAccepted` boolean remains
  `walletTermsAcceptedAt != null`).

## Files

New:

- `frontend/src/components/wallet/WalletTermsBanner.tsx`
- `frontend/src/components/wallet/WalletTermsBanner.test.tsx`
- `frontend/src/lib/wallet/terms-banner-dismissed.ts`
- `frontend/src/lib/wallet/terms-banner-dismissed.test.ts`

Modified:

- `frontend/src/components/layout/AppShell.tsx`
- `frontend/src/components/listing/ActivateListingPanel.tsx`
- `frontend/src/components/listing/ActivateListingPanel.test.tsx`
- Root `README.md` sweep at task end (project convention).
