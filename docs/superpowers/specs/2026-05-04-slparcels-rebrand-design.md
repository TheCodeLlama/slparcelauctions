# SLParcels Rebrand (Copy + Live Docs Sweep)

**Date:** 2026-05-04
**Status:** Design approved, awaiting implementation plan
**Scope:** Copy + live documentation only. No code identifier, infrastructure, or SL avatar renames.

## Goal

Sweep every human-readable occurrence of `SLPA`, `SL Parcel Auctions`, `Second Life Parcel Auctions`, and `SLPA (Second Life Parcel Auctions)` in *active live documentation* and *frontend user-facing copy* and replace them with `SLParcels`. After this task, no live doc or rendered page that a user or engineer reads in normal day-to-day work shows the old brand, with one explicit exception: literal technical identifiers stay as written.

## Non-Goals

This task does not rename any of:

- Java package `com.slparcelauctions.backend`
- Spring config prefix `slpa.*` or env var prefix `SLPA_*`
- AWS/infra resources (`slpa-prod` cluster, `slpa-prod-postgres`, `/slpa/prod/*` SSM paths, `slpa-prod` AWS CLI profile, ECR repos, IAM roles, S3 buckets, Terraform state)
- Real Second Life avatar names (`SLPABot1`–`SLPABot5`, `SLPAEscrow Resident`)
- Postman workspace name `SLPA` or environment `SLPA Dev`
- Repo folder paths (`lsl-scripts/slpa-terminal/`, `lsl-scripts/slpa-verifier-giver/`, the local `Repos/Personal/slpa` clone path)
- Historical specs/plans under `docs/superpowers/{specs,plans}/2026-*`
- Auto-generated stitch HTML mockups under `docs/stitch_generated-design/`
- Aspirational/initial-design artifacts (`docs/initial-design/DESIGN.md`, `docs/initial-design/LINDEN_LAB_PARTNERSHIP.md`, `docs/implementation/epic-00/00-stitch-design-system.md`)
- The user's auto-memory files under `~/.claude/projects/.../memory/`

These remain `SLPA`/`slpa`/`SLPABot…` exactly as today. Each is captured as an explicit follow-up in the Deferred Work section.

## Mapping Rules

### Brand-reference forms (swept)

| Old form | Replacement |
|---|---|
| `SLPA` (used as a product/brand name) | `SLParcels` |
| `SL Parcel Auctions` | `SLParcels` |
| `Second Life Parcel Auctions` (used as the product full name) | `SLParcels` |
| `SLPA (Second Life Parcel Auctions)` | `SLParcels` |
| `SLPA — Second Life Parcel Auctions` (README h1 form) | `SLParcels` |

The descriptor "Second Life Parcel Auctions" is dropped entirely. CLAUDE.md's opening line `SLPA (Second Life Parcel Auctions) is a player-to-player land auction platform for Second Life…` becomes `SLParcels is a player-to-player land auction platform for Second Life…` — the surrounding phrase carries the meaning.

### Literal-identifier forms (kept verbatim)

A token stays as-is when it is a *name a system actually answers to*, not a brand reference. The test: would a developer search-and-find for it, or would the system break / become unfindable if you swapped the string? If yes to either, it is a literal — leave it.

| Stays as | Why |
|---|---|
| `SLPABot1`, `SLPABot2`, …, `SLPABot5` | Real SL avatar account names |
| `SLPABot*` (when used as the pattern referring to the bot pool) | Refers to the literal avatar pattern |
| `SLPAEscrow Resident` | Real SL avatar name |
| `slpa-prod`, `slpa-prod-backend`, `slpa-prod-postgres`, `slpa-prod-bot-1`, etc. | AWS resource names |
| `/slpa/prod/...` | SSM Parameter Store paths |
| `slpa-prod` (AWS CLI profile, in `aws --profile slpa-prod ...`) | Profile identifier |
| `com.slparcelauctions.backend` | Java package |
| `slpa.*` config keys (`slpa.bot.*`, `slpa.escrow.*`, etc.) | Spring `@ConfigurationProperties` prefix |
| `SLPA_PRIMARY_ESCROW_UUID`, `SLPA_BOT_SHARED_SECRET`, all `SLPA_*` env vars | Env var names |
| `SLPA` (Postman workspace name) and `SLPA Dev` (Postman env name) | Wayfinding — engineer types this to find it |
| `lsl-scripts/slpa-terminal/`, `lsl-scripts/slpa-verifier-giver/` (folder paths) | Filesystem paths |
| `slpa-dev-key` / `slpa-dev-secret` (MinIO dev creds) | Literal credentials |
| The `slpa` repo folder name | Filesystem path |

### Disambiguation rule for mixed contexts

A single line can contain both forms.

`├── bot/  C#/.NET 8 SL worker (one container per SL account — SLPABot1..5)` — `SLPABot1..5` is literal (avatar names) → stays. Nothing in this line gets edited.

`**Bot worker**: .NET 8 / LibreMetaverse worker that logs in as ``SLPABot*`` accounts and services backend tasks…` — `SLPABot*` is the literal avatar pattern → stays. The line has no brand reference, so it is untouched.

`**If you already have an AWS account you'll use for SLPA:** skip to Step 2. Make sure a payment method is on file (the launch-lite SLPA bill will be ~$130-180/mo).` — both `SLPA`s are brand references ("the project") → both swap to `SLParcels`.

### `SLPA` ambiguity test

When you encounter a bare `SLPA` and aren't sure if it's brand or identifier, read the surrounding sentence: if you could substitute "the project" / "the product" without losing meaning, it's brand → swap. If you'd substitute "the AWS resource named `SLPA`" / "the avatar named `SLPA`-something", it's identifier → keep.

## File Scope

### Bucket 1 — Frontend user-facing copy (~30 files, all swept)

Files where a string is rendered to the browser as part of the page UI. Representative set (the implementation plan enumerates fully):

- `frontend/src/app/layout.tsx` — site `<title>` and meta description
- `frontend/src/components/layout/Footer.tsx` — footer copyright + brand wordmark
- `frontend/src/components/marketing/Hero.tsx` — landing-page hero copy
- `frontend/src/components/auth/AuthCard.tsx` + `AuthCard.test.tsx`
- `frontend/src/app/login/page.tsx`, `frontend/src/app/about/page.tsx`, `frontend/src/app/contact/page.tsx`
- `frontend/src/app/wallet/page.tsx`, `frontend/src/app/saved/page.tsx`, `frontend/src/app/browse/page.tsx`
- `frontend/src/components/wallet/WalletPanel.tsx`, `WalletTermsModal.tsx`, `WalletTermsModal.test.tsx`
- listing/escrow/dashboard cards that mention the brand in user copy
- `frontend/src/components/layout/Footer.test.tsx`, `frontend/src/components/layout/AppShell.test.tsx` — paired test fixtures that assert the brand string

Where a test pins the brand string in an assertion or snapshot, the test gets updated alongside the component in the same edit.

### Bucket 2 — Backend user-visible strings (small, swept)

Backend code that emits brand text into a path users see — primarily notification copy and email subjects:

- `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisherImpl.java`
- `backend/src/main/java/com/slparcelauctions/backend/notification/slim/SlImMessageBuilder.java`
- A handful of exception handlers that produce a user-facing error message (`SlExceptionHandler.java`, `WalletSlExceptionHandler.java`, etc.)

Backend log messages (logger.info/warn/error strings) are operator-visible and count as "live docs an engineer reads in normal day-to-day work" — swept.

Backend `@ConfigurationProperties` classes, env-var references (`@Value("${SLPA_…}")`), and Java identifiers stay literal.

### Bucket 3 — Live documentation (swept)

The active docs an engineer reads to onboard, operate, or contribute:

- Top-level: `README.md`, `CLAUDE.md`, `PREP.md`, `FULL_TESTING_PROCEDURES.md`
- Backend: `backend/src/test/java/com/slparcelauctions/backend/auth/test/README.md`
- Bot: `bot/README.md`, `bot/.env.example` (comments only — env-var names stay literal)
- LSL scripts: `lsl-scripts/README.md`, `lsl-scripts/{slpa-terminal,slpa-verifier-giver,verification-terminal,parcel-verifier,sl-im-dispatcher}/README.md`, and the `config.notecard.example` files (comment text only)
- Implementation guides: `docs/implementation/CONVENTIONS.md`, `DEFERRED_WORK.md`, `FOOTGUNS.md`, `PHASES.md`, `CLEANED_FROM_DEFERRED.md`
- Operational docs: `docs/postman-publicid-migration-checklist.md`, `docs/testing/EPIC_6_TESTING.md`
- Final-design references that are still live: `docs/final-design/slparcels-website/README.md` plus the `.jsx` reference pages (`page-static.jsx`, `page-dashboard.jsx`, `page-listing-flow.jsx`, `admin-shell.jsx`)
- Top-level `docker-compose.yml` and `.env.example` — comments only
- `backend/Dockerfile` — any comments / labels that reference the brand
- LSL `.lsl` source files — only comments and `llSay`/`llDialog`/`llRegionSay`/`llOwnerSay`/`llInstantMessage`/`llRegionSayTo` user-visible strings

### Bucket 4 — Explicitly skipped

Listed for completeness so the implementation plan filters them out:

- `docs/superpowers/specs/2026-*` (all)
- `docs/superpowers/plans/2026-*` (all)
- `docs/stitch_generated-design/{dark_mode,light_mode}/**`
- `docs/initial-design/DESIGN.md`
- `docs/initial-design/LINDEN_LAB_PARTNERSHIP.md`
- `docs/implementation/epic-00/00-stitch-design-system.md`
- `~/.claude/projects/.../memory/**`

### `infra/` Terraform

The `infra/` Terraform files reference `slpa-prod`, `slparcels.com`, and various AWS resource names. Those are AWS resource identifiers (keep-list) and are not swept. There is no brand-text in Terraform comments worth chasing.

## Edge-Case Handling

**Casing.** The new brand is always written `SLParcels` with internal capitalization. Never `Slparcels`, `slparcels` (in prose), or `SLPARCELS`. The `slparcels` lowercase form survives only as the production domain (`slparcels.com`) and as the folder name `docs/final-design/slparcels-website/` — both pre-existing identifiers, untouched.

**Possessive form.** `SLPA's foo` → `SLParcels' foo` (apostrophe-only after the trailing `s`).

**Em-dash artifacts in the README h1.** Current `README.md` line 1 is `# SLPA — Second Life Parcel Auctions`. With the descriptor dropped, this collapses to `# SLParcels`. The recent commit `6ad8203 chore(language): remove em-dashes from user/admin-visible copy` already swept user-visible em-dashes; the README is doc rather than user-visible copy, so its em-dash may still be present. Implementer should check rather than assume.

**Image alt text and `aria-label`.** Any `alt="SL Parcel Auctions logo"` or similar accessibility text is brand → swap. Logo SVG/PNG asset audit: implementer should grep `frontend/public/` and `frontend/src/assets/` for `SL Parcel Auctions` and report any hits. If a logo file embeds the old wordmark as glyphs, asset replacement is flagged as a follow-up rather than blocking this task.

**Backend test assertions and snapshot fixtures.** Where a backend test asserts an exact email subject or notification body containing the old brand, the test gets updated in the same commit as the source. Frontend snapshot tests: same. `.snap` files get regenerated rather than hand-edited.

**Domain references in live docs.** `slparcels.com` is the current production domain. Any live doc still saying `slparcelauctions.com` is *factually wrong* — swap to `slparcels.com`. `LINDEN_LAB_PARTNERSHIP.md` is the only allowed exception (skipped per scope).

**Markdown link text vs URL.** Link text is brand → swap. Link URLs stay literal even if they contain `slpa`.

**Line wrapping.** Don't rewrap lines for cosmetic reasons. Diff hygiene > prettiness.

**LSL `.lsl` user-visible strings.** Inside a `.lsl` script, `llSay`/`llDialog`/`llRegionSay`/`llOwnerSay`/`llInstantMessage`/`llRegionSayTo` arguments and notecard prompts are user-visible (the avatar reading them sees them) and get swept. Variable names, function names, link-message constants, and JSON keys in HTTP bodies are identifiers and stay literal.

**Comments in `application.yml` / `application-prod.yml`.** Comment text gets swept; YAML keys (`slpa.bot.shared-secret`, etc.) stay literal.

**Transitional regex assertions.** If any test was using a regex like `/SL Parcel Auctions|SLParcels/` (anticipating the rename), the implementer normalizes it to a single literal `SLParcels` after the sweep.

## Verification

### V1 — No brand-form strings remain in swept files

This command must return zero matches:

```bash
git ls-files -z \
  ':(exclude)docs/superpowers/specs/2026-*' \
  ':(exclude)docs/superpowers/plans/2026-*' \
  ':(exclude)docs/stitch_generated-design/**' \
  ':(exclude)docs/initial-design/DESIGN.md' \
  ':(exclude)docs/initial-design/LINDEN_LAB_PARTNERSHIP.md' \
  ':(exclude)docs/implementation/epic-00/00-stitch-design-system.md' \
  | xargs -0 grep -nE 'SL Parcel Auctions|Second Life Parcel Auctions'
```

The pattern only matches the multi-word brand forms — `SLPA` alone is excluded because it appears legitimately in identifier contexts (`SLPABot1`, `SLPAEscrow`, `SLPA_BOT_SHARED_SECRET`, etc.).

### V2 — Bare `SLPA` audit (human review step)

```bash
git ls-files -z \
  ':(exclude)docs/superpowers/specs/2026-*' \
  ':(exclude)docs/superpowers/plans/2026-*' \
  ':(exclude)docs/stitch_generated-design/**' \
  ':(exclude)docs/initial-design/DESIGN.md' \
  ':(exclude)docs/initial-design/LINDEN_LAB_PARTNERSHIP.md' \
  ':(exclude)docs/implementation/epic-00/00-stitch-design-system.md' \
  | xargs -0 grep -nE '\bSLPA\b'
```

Each remaining hit must be classified as either:

- **Identifier** (allowed) — `SLPABot*`, `SLPA_*` env var, `slpa.*` config key visible in a comment, the literal `SLPA` Postman workspace name, etc.
- **Brand** (bug, fix immediately) — any prose use of `SLPA` as a product/brand reference that was missed.

The implementation plan produces a short "remaining `SLPA` matches" report explaining each surviving match. If the report contains any item not on the keep-literal list, the sweep is incomplete.

### V2.5 — Negative grep (no incorrectly-introduced renames)

This command must return zero matches:

```bash
git ls-files -z \
  ':(exclude)docs/superpowers/specs/2026-*' \
  ':(exclude)docs/superpowers/plans/2026-*' \
  ':(exclude)docs/stitch_generated-design/**' \
  ':(exclude)docs/initial-design/DESIGN.md' \
  ':(exclude)docs/initial-design/LINDEN_LAB_PARTNERSHIP.md' \
  ':(exclude)docs/implementation/epic-00/00-stitch-design-system.md' \
  | xargs -0 grep -nE 'SLParcelsBot|SLParcelsEscrow|slparcels-prod[-/]'
```

Catches the highest-likelihood failure mode: implementer autopilot-swapping a literal SL avatar or AWS resource name.

The deferred-work row appended to `docs/implementation/DEFERRED_WORK.md` must be a cross-reference to this spec, not an inline enumeration of follow-up items, so V2.5 stays clean. (`DEFERRED_WORK.md` is in the swept set under Bucket 3.)

### V3 — Build + test gates

```bash
# Frontend
cd frontend && npm run lint && npm test -- --run && npm run verify

# Backend
cd backend && ./mvnw test
```

Frontend test fixtures and snapshots assert brand strings in places, and backend tests pin notification-template substrings. Updates land in the same PR as the source.

## Risks

**R1: Implementer swaps an identifier mid-edit.** Highest-likelihood failure mode. Someone reads `SLPABot1 Resident` in a sentence, autopilot-swaps it to `SLParcelsBot1 Resident`, and now the doc tells the next engineer to look for an avatar that doesn't exist on the SL grid. Mitigation: V2 manual classification + V2.5 negative grep.

**R2: Postman wayfinding breaks readability.** Sentences like *"the SLPA Postman collection is the canonical manual-test surface"* become awkward when surrounding prose says SLParcels. Each may need light rephrasing (e.g., *"the `SLPA` Postman collection — workspace name unchanged — is the canonical manual-test surface for SLParcels"*). Wording quality, not correctness. Implementer re-reads CLAUDE.md and `FULL_TESTING_PROCEDURES.md` rather than relying on find/replace.

**R3: Snapshot test churn.** Frontend `.snap` files probably contain the old brand. Refreshing them with `npm test -- -u` produces a large diff that the reviewer must skim for any non-brand change. The PR description should call this out.

**R4: Logo / image assets with embedded brand glyphs.** A SVG/PNG with the wordmark drawn into pixels won't be caught by any text grep. Mitigation: implementer greps asset filenames; if a logo file does need updating, that's flagged as a follow-up rather than blocking this task. Manual visual check of landing page, login page, footer, and email templates is part of the PR test plan.

**R5: Cross-link anachronism with skipped docs.** Live `README.md` links to `docs/initial-design/DESIGN.md`. Live doc says SLParcels; linked-to doc says SLPA. Reader experiences a mild context shift. Acceptable trade per scope choice — flagged in the PR description, not chased.

**R6: Future memory entries written before the sweep lands.** Any memory entry the user (or assistant) writes between now and the merge will use whichever brand the assistant happens to read at write time. Low-impact; memory entries are short-lived and rewritten organically.

## Deferred Work (captured for follow-up)

The implementation plan appends a single dated row to `docs/implementation/DEFERRED_WORK.md` covering all 14 items below, cross-referencing this spec.

| # | Deferred work | Origin |
|---|---|---|
| 1 | Java package rename `com.slparcelauctions.backend` → `com.slparcels.backend` | Code-identifier sweep |
| 2 | Spring config prefix rename `slpa.*` → `slparcels.*` | Code-identifier sweep |
| 3 | Env var prefix rename `SLPA_*` → `SLPARCELS_*` | Code-identifier sweep |
| 4 | AWS resource recreation `slpa-prod-*` → `slparcels-prod-*` (ECS, RDS, ECR, S3, IAM, ALB, Route53) | Infra rebrand |
| 5 | SSM path migration `/slpa/prod/*` → `/slparcels/prod/*` | Infra rebrand |
| 6 | AWS CLI profile rename `slpa-prod` → `slparcels-prod` (and `~/.aws/config` update) | Infra rebrand |
| 7 | Real SL avatar provisioning: new `SLParcelsBot1–5` + `SLParcelsEscrow Resident` accounts, with rotation of the existing pool | Infra rebrand — multi-week external dependency on SL account creation/premium |
| 8 | Postman workspace + collection + environment renames (`SLPA` → `SLParcels`, `SLPA Dev` → `SLParcels Dev`) | Independent, low-cost, can be done any time |
| 9 | Repo folder renames `lsl-scripts/slpa-terminal/` → `slparcels-terminal/`, `slpa-verifier-giver/` → `slparcels-verifier-giver/` | Code-identifier sweep |
| 10 | Sweep of historical specs/plans under `docs/superpowers/{specs,plans}/2026-*` | Documentation aggression |
| 11 | Sweep or deletion of `docs/stitch_generated-design/` mockups | Documentation aggression — auto-generated, deletion also fair |
| 12 | Initial-design / LINDEN_LAB_PARTNERSHIP / epic-00 stitch-design-system rewrites | Documentation aggression — likely permanent skip |
| 13 | Auto-memory file sweep under `~/.claude/projects/.../memory/` | Outside the repo; user opt-in |
| 14 | Local working-directory rename `Repos/Personal/slpa` → `Repos/Personal/slparcels` | User-side filesystem op |
