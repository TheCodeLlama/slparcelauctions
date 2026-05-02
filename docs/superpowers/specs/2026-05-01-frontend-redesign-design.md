# Frontend Redesign — Design Spec

**Status:** approved (brainstorm 2026-05-01)
**Branch:** `heath/update-frontend` (single long-lived feature branch; no per-cluster sub-branches)
**Sources of truth:**
- Visual: `docs/final-design/slparcels-website/` (README, `chats/chat1.md`, every file in `project/`)
- Behavioral: existing `frontend/src/` — every page, hook, component, API client, middleware preserved as-is below the visual layer
- Backend completeness: every feature the design depicts must ship end-to-end (no deferred work, no UI stubs, no mock fixtures in app code)

## Goal

Replace the current Material 3 "Digital Curator" UI with the new SLParcels marketing-grade visual system: SLParcels orange (`#E3631E`) brand, warm-neutral palette, Inter + JetBrains Mono typography, light-primary with dark mode toggle, polished marketplace density. End state: the deployed site is visually indistinguishable from the design bundle. No hybrid M3/new-design surfaces remain.

Authentication, JWT/session handling, role gating, protected-route redirects, TanStack Query data fetching, WebSocket/STOMP subscriptions, form validation, server-error handling, suspension/penalty gating, real notification/wallet/escrow/dispute/bid wire protocols, and everything else currently wired to the live backend are preserved exactly. The redesign replaces the visual layer; nothing else.

## Non-goals

- New visual regression / e2e test infrastructure
- A component-library publish (these primitives stay co-located with the app)
- A design-tokens-as-package extraction
- Replacing TanStack Query, `next-themes`, Spring Boot, or any other framework
- Production "tweaks panel" — designer dev tool only, dropped
- Header style variants (`default`/`minimal`/`bold`), accent-hue picker, density toggle — single canonical value in production

## Strategic decisions

| # | Decision | Choice |
|---|---|---|
| 1 | Sequencing | Single feature branch, commit-by-commit, no per-cluster PRs. Structured as shell-first then page-by-page (Section 4 slicing). |
| 2 | Token vocabulary | Add new token vocabulary alongside M3 in `globals.css`. M3 stays during transition so unmigrated pages still compile. Final cleanup commit deletes M3 block. |
| 3 | Component library | Skin existing primitives in place (preserve file names + prop APIs + tests); add new files only for genuinely new primitives the design introduces. |
| 4 | Backend completeness | For every feature/data point the design introduces that lacks backend support, build the backend end-to-end in the same cluster. No `DEFERRED_WORK.md` entries for design-implied features. No UI stubs. No mock fixtures in app code. |

## Section 1 — Token system & theme attribute

**`globals.css` rewrite (commit 1).** Add a second `@theme` block with the new vocabulary, all prefixed `--color-*` so Tailwind v4 generates utilities:

- Brand: `--color-brand` (#E3631E), `--color-brand-hover`, `--color-brand-soft`, `--color-brand-border`
- Backgrounds: `--color-bg`, `--color-bg-subtle`, `--color-bg-muted`, `--color-bg-hover`, `--color-surface`, `--color-surface-raised`
- Foregrounds: `--color-fg`, `--color-fg-muted`, `--color-fg-subtle`, `--color-fg-faint`
- Borders: `--color-border`, `--color-border-strong`, `--color-border-subtle`
- Semantic: `--color-success`, `--color-success-bg`, `--color-warning`, `--color-warning-bg`, `--color-danger`, `--color-danger-bg`, `--color-info`, `--color-info-bg`

Non-color tokens added to `@theme`:
- `--radius-xs/sm/md/lg/xl/pill`
- `--shadow-xs/sm/md/lg` (subtle, low-intensity)
- `--ring` (focus ring color/size)
- `--container: 1320px`
- `--header-h: 60px`
- `--font-sans` (Inter), `--font-mono` (JetBrains Mono); `--font-display` and `--font-body` aliased to `--font-sans` so existing utilities don't break

**Theme attribute switch.** `providers.tsx` flips `attribute="class"` to `attribute="data-theme"`. The existing `.dark { ... }` block in `globals.css` is renamed to `[data-theme="dark"] { ... }` (M3 dark overrides preserved during transition). New tokens get their dark overrides in the same `[data-theme="dark"]` block. `next-themes` localStorage key (`theme`) is unchanged so persisted user preference survives the swap. `defaultTheme` stays `dark` in commit 1 (no visible flip during the chrome change); flips to `light` in the same commit when shell lands so first-paint default matches the light-primary design.

**Font swap.** `layout.tsx` swaps Manrope → `Inter` (variable `--font-inter`) + `JetBrains_Mono` (variable `--font-jetbrains-mono`) via `next/font/google`, both with `display: 'swap'`.

**Verify-script compatibility:**
- `verify-no-dark-variants.sh` keeps passing (forbids `dark:` Tailwind variants, not the CSS selector pattern)
- `verify-no-hex-colors.sh` keeps passing (hex values live in `globals.css`, which the script doesn't scan)
- `verify-no-inline-styles.sh` is the binding constraint during page work — every `style={...}` in the design bundle is translated to Tailwind utility classes referencing the new tokens

**Cleanup commit (15)** deletes M3 vars (`--color-primary`/`--color-on-primary`/`--color-surface-container-*`/`--color-on-surface-variant`/etc.), removes Manrope, removes any orphaned legacy components, scrubs any inline-style escape hatches that turned out to be unnecessary.

## Section 2 — Shell & chrome (commit 1)

**`Header.tsx` (skin in place).** 60px sticky, `bg-bg`, `border-b border-border`. Logo: 28px `bg-brand` rounded mark + "SLPA" text in 700 weight `text-fg`. Inline nav links (`Browse`, `Sell` → `/listings/new`, `How it works` → `/about`) — links driven by current routes, not the design's mock pages. Flex spacer. Right cluster: search icon button, theme toggle, notifications icon button (with brand badge for unread count, wired to existing `useNotificationStream`), `WalletPill` (new primitive — avatar + balance + dropdown), profile dropdown. Mobile (≤920px) collapses to logo + theme toggle + hamburger; hamburger opens the right-side mobile drawer.

Header style variants (`default`/`minimal`/`bold`) from the design's tweaks panel are dropped — single canonical style in production.

**`Footer.tsx` (skin in place).** Slim single row at desktop. Brand + copyright on left; links cluster on right (`About`, `Contact`, `Partners`, `Terms`). `bg-bg-subtle`, `border-t border-border`.

**`MobileMenu.tsx` (skin in place).** Right-side slide-in drawer (300px), `bg-bg`, `border-l border-border`, full-height. Sections in order: account header (avatar + display name + verify status), nav links list, expanded wallet card, theme toggle row, footer links. Backdrop click + Escape close. Reuses existing `Drawer` primitive with `side="right"`.

**`ThemeToggle.tsx` (skin in place).** Sun/moon icon button via `next-themes` `useTheme()`. Already exists; visuals only.

**Admin shell — new `frontend/src/app/admin/layout.tsx`.** Currently admin routes inherit the global `AppShell`. The new admin layout overrides this with a slim admin top bar (logo mark + "Admin" pill + breadcrumbs + back-to-site link + admin profile menu) plus a left sidebar (`AdminSidebar.tsx`, new file) with admin nav (Dashboard, Users, Disputes, Reports, Audit Log, Bans, Infrastructure). Mobile collapses sidebar; admin hamburger opens the left-side drawer (`AdminMobileMenu.tsx`, new file). Site footer is hidden in admin.

**`AppShell.tsx` (light skin).** Stays the layout container; updates background tokens and removes any M3-specific structural styles.

## Section 3 — Component library treatment

**Skinned in place** (file names + prop APIs + tests preserved; internals rewritten with new tokens):

| Component | New variants / changes |
|---|---|
| `Button` | `variant="primary"` swaps to brand orange; add `variant="dark"` (inverted bg-fg / text-bg); add `size="xl"` |
| `Card` | Add `interactive` prop (hover lift + shadow), `tone="raised"` modifier |
| `Modal` | Header (title + X icon-button) / body / optional footer; backdrop `bg-fg/50` + `backdrop-blur-sm` |
| `Drawer` | `side="left" \| "right"` |
| `Tabs` | Underline-on-active style |
| `Input` / `Checkbox` / `Dropdown` | Token-driven; focus ring uses new `--ring` |
| `IconButton` | 36×36, `border-radius: var(--radius-sm)` |
| `Avatar` | Sizes; brand-colored ring for verified |
| `StatusBadge` | `tone="brand" \| "success" \| "warning" \| "danger" \| "info" \| "neutral" \| "outline"` |
| `StatusChip` / `ActiveFilterBadge` | Rounded-full, neutral border, `active` state inverts to fg/bg, X-close affordance |
| `Stepper` | Timeline-style (escrow + listing-flow) |
| `Toast`, `EmptyState`, `Lightbox`, `LoadingSpinner`, `RangeSlider`, `Pagination`, `BottomSheet`, `CountdownTimer`, `CodeDisplay`, `DropZone`, `FormError`, `PasswordStrengthIndicator`, `PageHeader`, `NavLink` | Light skin only |

**New files added** (genuinely new primitives + admin shell pieces):

- `components/ui/WalletPill.tsx` — header pill (avatar + balance + dropdown); composed in `Header.tsx`
- `components/ui/ParcelImage.tsx` — placeholder tile with grid overlay + mono coords badge + tier badge + save button; replaces ad-hoc patterns in browse / auction / wallet / dashboard cards
- `components/ui/Eyebrow.tsx` — orange uppercase tracking-wide label (used above section heads on home / marketing); ~8 lines
- `components/ui/SectionHeading.tsx` — `<h2>` + optional sub + optional right-slot; one source of truth for section-head spacing/typography
- `components/layout/AdminSidebar.tsx` — admin nav sidebar (Dashboard, Users, Disputes, Reports, Audit Log, Bans, Infrastructure); consumed by `app/admin/layout.tsx` (commit 14)
- `components/layout/AdminMobileMenu.tsx` — left-side admin drawer (mobile breakpoint of `AdminSidebar`); commit 14
- Five modal files under `components/` (cluster placement per Section 5): `ConfirmBidDialog.tsx`, `SuspensionErrorModal.tsx`, `ReportListingModal.tsx`, `FlagReviewModal.tsx`, `ReinstateListingModal.tsx`

**Icons.** Existing `components/ui/icons.ts` extended per cluster as needed (Menu, Plus, Minus, X, ArrowUpRight, ArrowDownLeft, History, MapPin, Camera, Copy, Refresh — verified at the cluster commit that first uses them).

**Component variants** are folded into the cluster commit that first needs them — no standalone "primitive refresh" commit. No lazy debt: every commit ships every primitive it consumes, fully skinned.

## Section 4 — Slicing order (commit cadence)

Commits land directly on `heath/update-frontend`. Each frontend commit message starts `redesign(<cluster>):`; backend gap commits (per Section 5) start `feat(<area>):`. Push to remote after each commit so progress is visible.

| # | Commit | Scope |
|---|---|---|
| 1 | `redesign(shell): tokens + chrome` | New `@theme` tokens; `[data-theme="dark"]` swap; Inter + JetBrains Mono; `Header`, `Footer`, `MobileMenu`, `ThemeToggle`, `AppShell`; new `WalletPill` |
| 2 | `redesign(home): landing` | `app/page.tsx` + supporting marketing components |
| 3 | `redesign(browse): listings & filters` | `app/browse/`; filter sidebar, sortable grid/list/map views, active-filter chips, `ActiveFilterBadge`, `RangeSlider` skin, `ParcelImage` rollout |
| 4 | `redesign(auction): detail + bid flow` | `app/auction/[id]/`; gallery, tabs (Details/Bids/Seller/Region), sticky bid panel, `Countdown`, bid modal flow, `ConfirmBidDialog`, `ReportListingModal`, `FlagReviewModal` |
| 5 | `redesign(escrow): timeline + dispute` | `app/auction/[id]/escrow/` (or wherever escrow lives); stepper, activity log, settlement summary, dispute modal |
| 6 | `redesign(wallet): balance + activity` | `app/wallet/`; balance triple-card, deposit modal, ledger table, active reservations sidebar, deposit terminals card |
| 7 | `redesign(listings): create + edit + activate + dispute` | `app/listings/new/`, `app/listings/[id]/edit/`, activate/dispute sub-routes; `SuspensionErrorModal` |
| 8 | `redesign(dashboard): overview + bids + listings tabs` | `app/dashboard/`, `app/dashboard/bids/`, `app/dashboard/listings/`; tabbed shell, suspension banner, pending reviews, profile editor, cancel-listing modal |
| 9 | `redesign(verify): avatar verification` | `app/settings/verify/` (or wherever verify lives); 5-step instructions + 6-digit code panel + live polling indicator |
| 10 | `redesign(settings): account preferences` | `app/settings/`; notification prefs, account fields, danger-zone |
| 11 | `redesign(auth): login + register + forgot + goodbye` | `app/login/`, `app/register/`, `app/forgot-password/`, `app/goodbye/`; auth pages share a centered single-column layout |
| 12 | `redesign(static): about + contact + partners + terms` | `app/about/`, `app/contact/`, `app/partners/`, `app/terms/` |
| 13 | `redesign(utility): notifications + saved + user profile` | `app/notifications/`, `app/saved/`, `app/users/[id]/`, `app/users/[id]/listings/` |
| 14 | `redesign(admin): all admin routes` | `app/admin/layout.tsx` (new admin shell + sidebar + admin mobile drawer) + every `app/admin/*` page; `ReinstateListingModal` |
| 15 | `redesign(cleanup): drop M3 tokens` | Delete M3 vars from `globals.css`, remove Manrope, remove any orphaned legacy components, scrub remaining inline-style escapes; verify scripts pass clean |

**Mid-migration appearance:** chrome looks redesigned from commit 1; clusters become "fully redesigned" in their commit; pages further down the list keep current M3 page bodies inside new chrome until their cluster lands. End state matches the design exactly — no hybrid lingers past commit 15.

**Order rationale:** front-load high-traffic / high-judgment pages (home → browse → auction) so the redesign's character is visible early; bid/escrow/wallet flows next because they're the product's core; admin last because it's internal-facing and bulk-volume.

**Per-page mental model (clusters 2–14).** Each page's re-skin is "drop in the design's JSX → wire to existing hooks/mutations → translate inline styles," not "tweak the existing M3 markup." Concretely: open the design's `page-<x>.jsx`, copy its component structure into the matching Next.js route file, replace the design's mock data references (`window.SLP_DATA`, `D.AUCTIONS[i]`, etc.) with the existing TanStack Query hooks and mutation handlers from the current `frontend/src/`, and translate every inline `style={...}` to Tailwind utilities per Section 6. The route shell, providers, middleware, hooks, API client, and test scaffolding stay; the page body is replaced wholesale. End-state visual fidelity comes from copying the design's JSX, not from re-styling the M3 markup.

## Section 5 — Modals, overlays, and the backend-completeness rule

**Hard rule:** every cluster ships the frontend re-skin **plus the backend for any feature the design introduces** that doesn't yet exist. No `DEFERRED_WORK.md` entries for design-implied features. No mock fixtures in app code. No `// TODO: wire` UI stubs.

**Per-cluster work order:**
1. **Audit:** before re-skinning a cluster, grep backend controllers + DB schema for every data point and action the cluster's pages depict. Produce a gap list.
2. **Build the backend gaps end-to-end:** Flyway migration → JPA entity → repository → service → controller → Bean Validation → JWT/role guards → JUnit tests → Postman collection update. Lands as one or more commits prefixed `feat(<area>):` *before* the cluster's `redesign(<cluster>):` commit.
3. **Wire the frontend client:** add types, API client functions, and TanStack Query hooks under `frontend/src/lib/api/`.
4. **Cluster commit:** re-skin pages, wire forms/modals to the real mutations, translate inline styles to Tailwind, run lint + verify + tests.

**Likely backend gaps** (exact list verified at cluster start by reading actual controllers + DB):

| Cluster | New backend work expected |
|---|---|
| 4 auction | Listing-report endpoint + entity; review system (entity, post-auction trigger, list-by-seller) if not already present; review-flag endpoint |
| 5 escrow | Dispute filing endpoint if not present; settlement-summary read |
| 7 listings | Suspension state on `users` (with reason); suspension-aware listing-submit returning 403 + reason; activate-listing endpoint if missing |
| 8 dashboard | Pending-reviews queue; cancellation-history read; profile editor (display name + bio + avatar upload via existing MinIO presigned-URL pattern) |
| 13 utility | Saved-listings (watchlist) CRUD |
| 14 admin | Reports queue; review-flags queue; reinstate-listing; audit log; bans; fraud flags; infrastructure status panel |

**Five new modal components** — all fully wired against real endpoints by the time their cluster lands:

| Modal | Trigger | Wiring |
|---|---|---|
| `ConfirmBidDialog` | Auction page bid panel, when bid amount exceeds current top by ≥50% (threshold tunable) | Wraps existing place-bid mutation |
| `SuspensionErrorModal` | Listing create/edit submit, when server returns 403 with `reason="suspended"` | Surfaces the server's payload built in cluster 7 |
| `ReportListingModal` | Auction page, "Report listing" link | Listing-report endpoint built in cluster 4 |
| `FlagReviewModal` | Seller-reviews list on auction page (Seller tab) | Review-flag endpoint built in cluster 4 |
| `ReinstateListingModal` | Admin user-detail Cancellations tab | Admin-reinstate endpoint built in cluster 14 |

**Mobile drawers** (`MobileMenu`, `AdminMobileMenu`) and existing modals (`Modal.tsx` base, listing-cancel, etc.) keep their wiring; visuals reskin per Sections 2–3.

**No new overlay infrastructure** — existing `Modal` and `Drawer` primitives plus React state in the parent are sufficient. No portal manager, no command-palette, no toast-stack rework.

**End state:** every page renders against real data; every form posts to a real endpoint; `data.jsx` and any other design mock fixtures are never imported into `frontend/src/`. Cleanup commit (15) verifies zero references to mock fixtures.

## Section 6 — Verify policy, testing, quality bar per commit

**Inline-style → Tailwind translation rules** (every page commit):

- Layout: `style={{ display: 'grid', gridTemplateColumns: '1fr 320px', gap: 24 }}` → `grid grid-cols-[1fr_320px] gap-6`
- Spacing: `style={{ padding: '24px 24px 80px' }}` → `pt-6 px-6 pb-20`
- Typography: `style={{ fontSize: 13.5 }}` → closest token utility (`text-sm`, `text-base`); odd half-pixel sizes round to nearest token
- Colors: every color reference resolves through new tokens (`bg-bg`, `bg-bg-subtle`, `bg-bg-muted`, `text-fg`, `text-fg-muted`, `border-border`, `bg-brand`, `text-brand`, `bg-success-bg text-success`, etc.) — never hex
- Tabular numerics: `tabular-nums` utility replaces `font-variant-numeric: tabular-nums`
- Mono font: `font-mono` utility (resolves to JetBrains Mono via `--font-mono`)
- Dynamic values (e.g., percentage progress bar width): set a CSS variable on the element via `style={{ '--progress': pct }}` and reference it from a Tailwind arbitrary value `w-[var(--progress)]`. **One narrowly-scoped exception** added to `verify-no-inline-styles.sh` allowlist with a comment citing this spec — only when truly needed, not as a blanket escape hatch
- Backdrop-blur, gradients (e.g., `ParcelImage`): handled in component CSS or Tailwind utilities (`backdrop-blur-md`, `bg-linear-*`); no inline styles in pages

**Verify scripts pass cleanly after every commit:** `npm run verify` (= `verify-no-dark-variants` + `verify-no-hex-colors` + `verify-no-inline-styles` + coverage) is run before every commit. Any commit that breaks verify gets fixed in-place, not pushed forward.

**Testing strategy:**
- Existing component tests stay green — prop APIs preserved, so re-skin doesn't break behavior assertions. Snapshot tests, if any, are updated in the same commit (acceptable: visual change is the intent).
- Page-level integration tests (`page.integration.test.tsx`, etc.) stay green; if a test asserts on M3 class names directly, replace those assertions with token-based selectors or semantic queries (preferred).
- New primitives (`WalletPill`, `ParcelImage`, `Eyebrow`, `SectionHeading`) get co-located `.test.tsx` files following existing patterns.
- New modal components each get a `.test.tsx` covering open/close, submit-fires-mutation, error-rendering.
- Backend tests follow existing JUnit conventions per CLAUDE.md.
- No new visual-regression / e2e infrastructure introduced. YAGNI for this redesign.

**Per-commit quality bar:**
1. `npm run lint` (frontend) — clean
2. `npm run verify` — clean
3. `npm test` — green
4. `./mvnw test` (backend) — green if commit touches Java
5. Postman collection updated for any new/changed endpoint
6. CLAUDE.md / README / DEFERRED_WORK references stay accurate

**Branch hygiene:**
- Every commit lands directly on `heath/update-frontend`. No per-cluster branches.
- No squash-rebases mid-stream — the linear commit history *is* the changelog.
- `git push origin heath/update-frontend` after each commit so progress is visible remotely.

## Risk callouts

- Theme attribute switch (`class` → `data-theme`): `next-themes` localStorage key (`theme`) stays the same, so persisted user preference survives the change. Verified in commit 1 by toggling and reloading.
- Manrope → Inter font swap: brief flash possible on first page load post-deploy; `display: 'swap'` already used by `next/font` mitigates.
- `defaultTheme` flips to `light` in commit 1: users with no persisted preference see light on next visit; users with `theme=dark` persisted keep dark.
- Mid-migration appearance: clusters 2–14 land progressively; chrome is redesigned from commit 1 but page bodies retain M3 styling until each cluster lands. Acceptable per agreed strategy. End state must match the design exactly — verified by commit 15 plus a final eyeball pass.
- Backend-completeness rule expands scope materially: each cluster row may produce 2–N commits (one or more `feat(backend):` per gap-fill, then the `redesign(<cluster>):` for the frontend). Cluster ordering unchanged.

## Open items resolved during brainstorming

- Tweaks panel: dropped — designer-only dev tool, not for production.
- Header style variants (`default`/`minimal`/`bold`): dropped — single canonical style.
- Density toggle / accent-hue picker / radius variants: dropped — single canonical values.
- Maps button in header / API and Status in footer: already removed by the designer per chat transcript.
- Theme attribute: `next-themes` switches to `attribute="data-theme"` to align with the new design's CSS selector pattern.
- Dark-mode default: flips from `dark` to `light` in commit 1 (light-primary design).
