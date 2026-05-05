# SLParcels Rebrand Sweep Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Sweep every human-readable occurrence of `SLPA`, `SL Parcel Auctions`, `Second Life Parcel Auctions`, and `SLPA (Second Life Parcel Auctions)` in active live documentation and frontend user-facing copy and replace each with `SLParcels`. Literal technical identifiers (`SLPABot*`, `SLPA_*` env vars, `slpa.*` config keys, `slpa-prod` resources, `com.slparcelauctions.backend` package) stay verbatim.

**Architecture:** Pure text sweep across ~80 swept files (frontend, backend user-visible strings, top-level docs, LSL docs, implementation guides, infra Terraform comments). No identifier renames, no schema changes, no infra resource changes. Each phase commits independently and runs scoped grep verification before moving on.

**Tech Stack:** Edits via the editing tools. Verification via `git ls-files | xargs grep`, `npm test`, `npm run lint`, `npm run verify`, `./mvnw test`.

**Spec:** `docs/superpowers/specs/2026-05-04-slparcels-rebrand-design.md`

---

## Spec deviations documented in this plan

Two judgment calls were raised during plan-writing where the spec was either ambiguous or empirically wrong. The user resolved each:

1. **`docs/implementation/epic-*/task-*.md` files (~17 files containing bare `SLPA`)** — The spec's Bucket 3 enumerates 5 specific files under `docs/implementation/` (CONVENTIONS.md, DEFERRED_WORK.md, FOOTGUNS.md, PHASES.md, CLEANED_FROM_DEFERRED.md) and explicitly skips only `docs/implementation/epic-00/00-stitch-design-system.md`. Other `epic-NN/task-*.md` files are neither enumerated as swept nor as skipped. **User decision: SKIP. These files are treated like historical specs/plans and are excluded from the sweep.** No task touches `docs/implementation/epic-*/**`.

2. **`infra/` Terraform comments contain brand references.** The spec's File Scope section says "There is no brand-text in Terraform comments worth chasing." Empirical grep proved otherwise — `infra/main.tf` line 2 is `# SLPA — Terraform root module`. **User decision: sweep comments ONLY. Per explicit user direction, only `#` comment lines in `infra/` get swept. Terraform `description = "..."` attributes, `error_message = "..."` strings, variable defaults, and any other non-comment string content stay literal even when they contain `SLPA`.** Task 14 handles this with the comments-only restriction.

---

## File Structure (which files get touched)

Inventory grouped by phase. The exact file list per task is established by running a scoped grep at the start of that task; files listed below are the expected set based on baseline grep at plan-write time.

### Phase 2 — Frontend (~40 files)

**Layout / marketing / auth:**
- `frontend/src/app/layout.tsx`
- `frontend/src/components/layout/Footer.tsx`, `Footer.test.tsx`
- `frontend/src/components/layout/AppShell.test.tsx`
- `frontend/src/components/marketing/Hero.tsx`
- `frontend/src/components/auth/AuthCard.tsx`, `AuthCard.test.tsx`

**Wallet:**
- `frontend/src/components/wallet/WalletPanel.tsx`
- `frontend/src/components/wallet/WalletTermsModal.tsx`, `WalletTermsModal.test.tsx`
- `frontend/src/components/wallet/LedgerTable.tsx`

**Listing:**
- `frontend/src/components/listing/ActivateListingPanel.tsx`, `ActivateListingPanel.test.tsx`
- `frontend/src/components/listing/CancelListingModal.tsx`
- `frontend/src/components/listing/MyListingsTab.tsx`
- `frontend/src/components/listing/SuspensionErrorModal.tsx`, `SuspensionErrorModal.test.tsx`
- `frontend/src/components/listing/VerificationMethodPicker.tsx`
- `frontend/src/components/listing/VerificationMethodRezzable.tsx`

**Escrow / dashboard / admin / user:**
- `frontend/src/components/escrow/state/PendingStateCard.tsx`
- `frontend/src/components/escrow/state/TransferPendingStateCard.tsx`
- `frontend/src/components/escrow/state/FrozenStateCard.tsx`
- `frontend/src/components/escrow/state/DisputedStateCard.tsx`
- `frontend/src/components/escrow/escrowBannerCopy.ts`
- `frontend/src/components/dashboard/SuspensionBanner.tsx`
- `frontend/src/components/admin/fraud-flags/FraudFlagEvidence.tsx`
- `frontend/src/components/user/VerificationCodeDisplay.tsx`
- `frontend/src/components/user/UnverifiedVerifyFlow.tsx`

**Pages + lib:**
- `frontend/src/app/wallet/page.tsx`
- `frontend/src/app/login/page.tsx`
- `frontend/src/app/saved/page.tsx`
- `frontend/src/app/browse/page.tsx`
- `frontend/src/app/contact/page.tsx`
- `frontend/src/app/about/page.tsx`
- `frontend/src/app/auction/[publicId]/page.tsx`
- `frontend/src/app/auction/[publicId]/page.integration.test.tsx`
- `frontend/src/app/auction/[publicId]/escrow/dispute/DisputeFormClient.tsx`
- `frontend/src/app/admin/infrastructure/ReconciliationSection.tsx`
- `frontend/src/app/users/[publicId]/page.tsx`
- `frontend/src/app/users/[publicId]/page.test.tsx`
- `frontend/src/app/users/[publicId]/listings/page.tsx`
- `frontend/src/lib/user/api.ts`
- `frontend/src/lib/api.ts`

### Phase 3 — Backend (~30 files)

**User-visible message templates:**
- `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisherImpl.java`
- `backend/src/main/java/com/slparcelauctions/backend/notification/slim/SlImMessageBuilder.java`
- `backend/src/main/java/com/slparcelauctions/backend/sl/SlExceptionHandler.java`
- `backend/src/main/java/com/slparcelauctions/backend/sl/exception/AvatarAlreadyLinkedException.java`
- `backend/src/main/java/com/slparcelauctions/backend/auction/exception/AuctionExceptionHandler.java`
- `backend/src/main/java/com/slparcelauctions/backend/wallet/exception/UserNotLinkedException.java`
- `backend/src/main/java/com/slparcelauctions/backend/wallet/sl/WalletSlExceptionHandler.java`

**Other backend source (log messages, comments, JavaDoc):**
- `backend/src/main/java/com/slparcelauctions/backend/admin/infrastructure/reconciliation/ReconciliationService.java`
- `backend/src/main/java/com/slparcelauctions/backend/wallet/WalletService.java`
- `backend/src/main/java/com/slparcelauctions/backend/user/UserRepository.java`
- `backend/src/main/java/com/slparcelauctions/backend/user/User.java`
- `backend/src/main/java/com/slparcelauctions/backend/escrow/command/TerminalCommandService.java`
- `backend/src/main/java/com/slparcelauctions/backend/escrow/scheduler/ListingFeeRefundProcessorJob.java`
- `backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowTransactionType.java`
- `backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskService.java`
- `backend/src/main/java/com/slparcelauctions/backend/bot/BotStartupValidator.java`
- `backend/src/main/java/com/slparcelauctions/backend/auction/monitoring/SuspensionService.java`
- `backend/src/main/java/com/slparcelauctions/backend/admin/infrastructure/terminals/AdminTerminalRotationService.java`
- `backend/src/main/java/com/slparcelauctions/backend/wallet/sl/SlWalletController.java`
- `backend/src/main/java/com/slparcelauctions/backend/wallet/sl/SlWalletWithdrawRequest.java`
- `backend/src/main/java/com/slparcelauctions/backend/wallet/sl/SlWalletDepositRequest.java`
- `backend/src/main/java/com/slparcelauctions/backend/wallet/UserLedgerEntryType.java`
- `backend/src/main/java/com/slparcelauctions/backend/sl/SlWorldApiClientConfig.java`
- `backend/src/main/java/com/slparcelauctions/backend/sl/PenaltyTerminalService.java`
- `backend/src/main/java/com/slparcelauctions/backend/region/dto/RegionPageData.java`
- `backend/src/main/java/com/slparcelauctions/backend/parcel/MaturityRating.java`

**Backend test assertions:**
- `backend/src/test/java/com/slparcelauctions/backend/notification/slim/integration/OutbidImIntegrationTest.java`
- `backend/src/test/java/com/slparcelauctions/backend/notification/slim/integration/CancellationFanoutImIntegrationTest.java`
- `backend/src/test/java/com/slparcelauctions/backend/notification/slim/integration/SystemBypassImIntegrationTest.java`
- `backend/src/test/java/com/slparcelauctions/backend/notification/slim/internal/SlImInternalControllerTest.java`
- `backend/src/test/java/com/slparcelauctions/backend/notification/slim/SlImMessageBuilderTest.java`
- `backend/src/test/java/com/slparcelauctions/backend/notification/slim/SlImMessageDaoTest.java`
- `backend/src/test/java/com/slparcelauctions/backend/notification/slim/SlImMessageRepositoryTest.java`
- `backend/src/test/java/com/slparcelauctions/backend/notification/slim/SlImChannelDispatcherTest.java`
- `backend/src/test/java/com/slparcelauctions/backend/notification/slim/SlImCleanupJobTest.java`
- `backend/src/test/java/com/slparcelauctions/backend/auction/exception/SellerSuspendedExceptionHandlerTest.java`
- `backend/src/test/java/com/slparcelauctions/backend/sl/PenaltyTerminalControllerSliceTest.java`

**SQL migration:**
- `backend/src/main/resources/db/migration/V5__reconciliation_extension.sql` (comment text only)

### Phase 4 — Documentation (~30 files)

**Top-level live docs:**
- `README.md`
- `CLAUDE.md`
- `PREP.md`
- `FULL_TESTING_PROCEDURES.md`

**Bot + LSL READMEs and notecards:**
- `bot/README.md`, `bot/.env.example`
- `lsl-scripts/README.md`
- `lsl-scripts/slpa-terminal/README.md`, `slpa-terminal/config.notecard.example`
- `lsl-scripts/slpa-verifier-giver/README.md`, `slpa-verifier-giver/config.notecard.example`
- `lsl-scripts/verification-terminal/README.md`, `verification-terminal/config.notecard.example`
- `lsl-scripts/parcel-verifier/README.md`, `parcel-verifier/config.notecard.example`
- `lsl-scripts/sl-im-dispatcher/README.md`

**LSL .lsl source files (user-visible strings + comments only):**
- `lsl-scripts/verification-terminal/verification-terminal.lsl`
- `lsl-scripts/parcel-verifier/parcel-verifier.lsl`

**Implementation guides + ops + final-design + backend test docs:**
- `docs/implementation/DEFERRED_WORK.md`
- `docs/implementation/FOOTGUNS.md`
- `docs/implementation/PHASES.md`
- `docs/implementation/CLEANED_FROM_DEFERRED.md`
- `docs/implementation/CONVENTIONS.md` (if it has SLPA references — verify at task start)
- `docs/postman-publicid-migration-checklist.md`
- `docs/testing/EPIC_6_TESTING.md`
- `backend/src/test/java/com/slparcelauctions/backend/auth/test/README.md`
- `docs/final-design/slparcels-website/README.md`
- `docs/final-design/slparcels-website/project/page-static.jsx`
- `docs/final-design/slparcels-website/project/page-dashboard.jsx`
- `docs/final-design/slparcels-website/project/page-listing-flow.jsx`
- `docs/final-design/slparcels-website/project/admin-shell.jsx`

**Top-level config (comments only):**
- `docker-compose.yml`
- `.env.example`
- `backend/Dockerfile`
- `backend/src/main/resources/application.yml`
- `backend/src/main/resources/application-prod.yml`

### Phase 4.5 — Infra Terraform comments (per spec deviation #2)

- `infra/main.tf`
- `infra/outputs.tf`
- `infra/variables.tf`
- `infra/terraform.tfvars.example`
- `infra/dns/route53.tf`
- `infra/data/elasticache.tf`
- `infra/data/s3.tf`
- `infra/networking/security_groups.tf`
- `infra/compute/alb.tf`
- `infra/compute/ecs_backend.tf`
- `infra/cicd/main.tf`
- `infra/observability/main.tf`

### Skipped (per spec)

- `docs/superpowers/specs/2026-*` (all)
- `docs/superpowers/plans/2026-*` (all)
- `docs/stitch_generated-design/{dark_mode,light_mode}/**`
- `docs/initial-design/DESIGN.md`
- `docs/initial-design/INITIAL_DESIGN.md`
- `docs/initial-design/LINDEN_LAB_PARTNERSHIP.md`
- `docs/initial-design/CREATOR_PARTNERSHIP_PROGRAM.md`
- `docs/implementation/epic-00/00-stitch-design-system.md`
- `~/.claude/projects/.../memory/**`

### Skipped (per user decision on plan deviation #1)

- `docs/implementation/epic-*/**` (the entire `epic-NN/` tree, including all `task-*.md` files and the `epic-NN/NN-*.md` overviews)

---

## Tasks

Each task starts with a **scoped grep** to locate exactly which lines in the file group still match brand-form patterns, then applies edits per the spec's mapping rules and disambiguation rule, then verifies, then commits. The implementer must keep the spec's "disambiguation rule for mixed contexts" in mind on every line: a token like `SLPABot1`, `SLPA_BOT_SHARED_SECRET`, `slpa-prod`, or `com.slparcelauctions.backend` is a literal identifier and **stays verbatim**.

### Task 1: Preflight asset audit + baseline grep

**Files:**
- No file changes in this task

- [ ] **Step 1: Confirm working tree is clean and on `dev`**

```bash
git status --short
git rev-parse --abbrev-ref HEAD
```

Expected: working tree clean, on `dev`. If not, stop and resolve.

- [ ] **Step 2: Capture baseline V1 grep (multi-word brand)**

```bash
git ls-files -z \
  ':(exclude)docs/superpowers/specs/2026-*' \
  ':(exclude)docs/superpowers/plans/2026-*' \
  ':(exclude)docs/stitch_generated-design/**' \
  ':(exclude)docs/initial-design/DESIGN.md' \
  ':(exclude)docs/initial-design/INITIAL_DESIGN.md' \
  ':(exclude)docs/initial-design/LINDEN_LAB_PARTNERSHIP.md' \
  ':(exclude)docs/initial-design/CREATOR_PARTNERSHIP_PROGRAM.md' \
  ':(exclude)docs/implementation/epic-00/00-stitch-design-system.md' \
  | xargs -0 grep -nE 'SL Parcel Auctions|Second Life Parcel Auctions' \
  > /tmp/slparcels-v1-baseline.txt 2>&1
wc -l /tmp/slparcels-v1-baseline.txt
```

Expected: ~3-5 lines (mostly README.md, CLAUDE.md, frontend/src/app/layout.tsx).

- [ ] **Step 3: Capture baseline V2 grep (bare SLPA)**

```bash
git ls-files -z \
  ':(exclude)docs/superpowers/specs/2026-*' \
  ':(exclude)docs/superpowers/plans/2026-*' \
  ':(exclude)docs/stitch_generated-design/**' \
  ':(exclude)docs/initial-design/DESIGN.md' \
  ':(exclude)docs/initial-design/INITIAL_DESIGN.md' \
  ':(exclude)docs/initial-design/LINDEN_LAB_PARTNERSHIP.md' \
  ':(exclude)docs/initial-design/CREATOR_PARTNERSHIP_PROGRAM.md' \
  ':(exclude)docs/implementation/epic-00/00-stitch-design-system.md' \
  | xargs -0 grep -nE '\bSLPA\b' \
  > /tmp/slparcels-v2-baseline.txt 2>&1
wc -l /tmp/slparcels-v2-baseline.txt
```

Expected: hundreds of lines. This is the working set for Tasks 2-14.

- [ ] **Step 4: Asset audit — confirm no brand-text in image assets**

```bash
git ls-files frontend/public/ frontend/src/assets/ 2>/dev/null \
  | xargs grep -lE 'SL Parcel Auctions|SLPA' 2>/dev/null
```

Expected: no output (already verified empty at plan-write time).

If the audit finds a hit, file group is documented in PR description as a follow-up rather than blocked.

- [ ] **Step 5: No commit — this task only captures baselines**

---

### Task 2: Frontend layout + marketing + auth

**Files:**
- Modify: `frontend/src/app/layout.tsx` (line 23: `default: "SLPA: Second Life Parcel Auctions"` → `default: "SLParcels"`)
- Modify: `frontend/src/components/layout/Footer.tsx`
- Modify: `frontend/src/components/layout/Footer.test.tsx`
- Modify: `frontend/src/components/layout/AppShell.test.tsx`
- Modify: `frontend/src/components/marketing/Hero.tsx`
- Modify: `frontend/src/components/auth/AuthCard.tsx`
- Modify: `frontend/src/components/auth/AuthCard.test.tsx`

- [ ] **Step 1: Scoped grep on the file group**

```bash
git grep -nE 'SL Parcel Auctions|Second Life Parcel Auctions|\bSLPA\b' -- \
  frontend/src/app/layout.tsx \
  frontend/src/components/layout/Footer.tsx \
  frontend/src/components/layout/Footer.test.tsx \
  frontend/src/components/layout/AppShell.test.tsx \
  frontend/src/components/marketing/Hero.tsx \
  frontend/src/components/auth/AuthCard.tsx \
  frontend/src/components/auth/AuthCard.test.tsx
```

Expected: each match is a brand reference (no identifier collisions in these files).

- [ ] **Step 2: Read each file and apply replacements per the spec mapping table**

For each match:
- `SLPA: Second Life Parcel Auctions` → `SLParcels`
- `SL Parcel Auctions` → `SLParcels`
- `Second Life Parcel Auctions` → `SLParcels`
- `SLPA` (bare, brand context) → `SLParcels`

**Example diff (`frontend/src/app/layout.tsx` line 23):**

```diff
-    default: "SLPA: Second Life Parcel Auctions",
+    default: "SLParcels",
```

**Example diff for a footer copyright line:**

```diff
-© 2026 SL Parcel Auctions. Powered by Second Life.
+© 2026 SLParcels. Powered by Second Life.
```

- [ ] **Step 3: Re-run scoped grep — must be empty**

```bash
git grep -nE 'SL Parcel Auctions|Second Life Parcel Auctions|\bSLPA\b' -- \
  frontend/src/app/layout.tsx \
  frontend/src/components/layout/Footer.tsx \
  frontend/src/components/layout/Footer.test.tsx \
  frontend/src/components/layout/AppShell.test.tsx \
  frontend/src/components/marketing/Hero.tsx \
  frontend/src/components/auth/AuthCard.tsx \
  frontend/src/components/auth/AuthCard.test.tsx
```

Expected: zero matches.

- [ ] **Step 4: Run targeted frontend tests**

```bash
cd frontend && npx vitest run \
  src/components/layout/Footer.test.tsx \
  src/components/layout/AppShell.test.tsx \
  src/components/auth/AuthCard.test.tsx
```

Expected: all pass. If snapshot tests fail with brand-string mismatch, run `npx vitest run -u` to update snapshots, then review the diff.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/layout.tsx \
  frontend/src/components/layout/Footer.tsx \
  frontend/src/components/layout/Footer.test.tsx \
  frontend/src/components/layout/AppShell.test.tsx \
  frontend/src/components/marketing/Hero.tsx \
  frontend/src/components/auth/AuthCard.tsx \
  frontend/src/components/auth/AuthCard.test.tsx
git commit -m "chore(rebrand): swap brand to SLParcels in frontend layout/marketing/auth"
```

---

### Task 3: Frontend wallet components

**Files:**
- Modify: `frontend/src/components/wallet/WalletPanel.tsx`
- Modify: `frontend/src/components/wallet/WalletTermsModal.tsx`
- Modify: `frontend/src/components/wallet/WalletTermsModal.test.tsx`
- Modify: `frontend/src/components/wallet/LedgerTable.tsx`

- [ ] **Step 1: Scoped grep**

```bash
git grep -nE 'SL Parcel Auctions|Second Life Parcel Auctions|\bSLPA\b' -- \
  'frontend/src/components/wallet/*'
```

- [ ] **Step 2: Apply replacements per mapping table; identifier check on each line**

Each `SLPA` token in these files is brand context (Wallet UI does not reference SL avatar names). All swap to `SLParcels`.

- [ ] **Step 3: Re-run scoped grep — must be empty**

```bash
git grep -nE 'SL Parcel Auctions|Second Life Parcel Auctions|\bSLPA\b' -- \
  'frontend/src/components/wallet/*'
```

- [ ] **Step 4: Run targeted tests**

```bash
cd frontend && npx vitest run src/components/wallet/
```

Expected: pass. Update snapshots if needed.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/wallet/
git commit -m "chore(rebrand): swap brand to SLParcels in wallet components"
```

---

### Task 4: Frontend listing components

**Files:**
- Modify: `frontend/src/components/listing/ActivateListingPanel.tsx`, `ActivateListingPanel.test.tsx`
- Modify: `frontend/src/components/listing/CancelListingModal.tsx`
- Modify: `frontend/src/components/listing/MyListingsTab.tsx`
- Modify: `frontend/src/components/listing/SuspensionErrorModal.tsx`, `SuspensionErrorModal.test.tsx`
- Modify: `frontend/src/components/listing/VerificationMethodPicker.tsx`
- Modify: `frontend/src/components/listing/VerificationMethodRezzable.tsx`

- [ ] **Step 1: Scoped grep**

```bash
git grep -nE 'SL Parcel Auctions|Second Life Parcel Auctions|\bSLPA\b' -- \
  'frontend/src/components/listing/*'
```

- [ ] **Step 2: Identifier check — `SLPABot*`, `SLPAEscrow` may appear in user-facing copy**

The listing components include verification-method pickers that reference Method C (sale-to-bot). If user-facing copy says "the SLPABot1 Resident worker" — that's a literal SL avatar name, **stays**. If it says "send your parcel to SLPA" (where SLPA is the brand) — swap to SLParcels.

**Test for each match:** could you replace `SLPA` with "the project name" without losing meaning? If yes → swap. If you'd replace it with "the avatar named SLPA-something" → keep.

`SaleToBot` test fixtures may contain literal avatar names — leave those.

- [ ] **Step 3: Apply replacements; re-run scoped grep**

```bash
git grep -nE '\bSLPA\b' -- 'frontend/src/components/listing/*'
```

Surviving matches must all be literal identifiers (`SLPABot*`, `SLPAEscrow`, `SLPA_*` env vars, etc.). If any surviving match is a brand reference, fix it before commit.

- [ ] **Step 4: Run targeted tests**

```bash
cd frontend && npx vitest run src/components/listing/
```

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/listing/
git commit -m "chore(rebrand): swap brand to SLParcels in listing components"
```

---

### Task 5: Frontend escrow + dashboard + admin + user components

**Files:**
- Modify: `frontend/src/components/escrow/state/PendingStateCard.tsx`
- Modify: `frontend/src/components/escrow/state/TransferPendingStateCard.tsx`
- Modify: `frontend/src/components/escrow/state/FrozenStateCard.tsx`
- Modify: `frontend/src/components/escrow/state/DisputedStateCard.tsx`
- Modify: `frontend/src/components/escrow/escrowBannerCopy.ts`
- Modify: `frontend/src/components/dashboard/SuspensionBanner.tsx`
- Modify: `frontend/src/components/admin/fraud-flags/FraudFlagEvidence.tsx`
- Modify: `frontend/src/components/user/VerificationCodeDisplay.tsx`
- Modify: `frontend/src/components/user/UnverifiedVerifyFlow.tsx`

- [ ] **Step 1: Scoped grep**

```bash
git grep -nE 'SL Parcel Auctions|Second Life Parcel Auctions|\bSLPA\b' -- \
  'frontend/src/components/escrow/' \
  'frontend/src/components/dashboard/' \
  'frontend/src/components/admin/' \
  'frontend/src/components/user/'
```

- [ ] **Step 2: Apply replacements with identifier check**

Watch for `SLPAEscrow Resident` references in escrow state cards (legitimate avatar reference — stays) and `SLPABot*` references in user verification flows (legitimate avatar reference — stays).

- [ ] **Step 3: Re-run scoped grep — surviving matches must all be literal identifiers**

- [ ] **Step 4: Run targeted tests**

```bash
cd frontend && npx vitest run \
  src/components/escrow/ \
  src/components/dashboard/ \
  src/components/admin/ \
  src/components/user/
```

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/escrow/ \
  frontend/src/components/dashboard/ \
  frontend/src/components/admin/ \
  frontend/src/components/user/
git commit -m "chore(rebrand): swap brand to SLParcels in escrow/dashboard/admin/user components"
```

---

### Task 6: Frontend pages + lib

**Files:**
- Modify: `frontend/src/app/wallet/page.tsx`
- Modify: `frontend/src/app/login/page.tsx`
- Modify: `frontend/src/app/saved/page.tsx`
- Modify: `frontend/src/app/browse/page.tsx`
- Modify: `frontend/src/app/contact/page.tsx`
- Modify: `frontend/src/app/about/page.tsx`
- Modify: `frontend/src/app/auction/[publicId]/page.tsx`, `page.integration.test.tsx`
- Modify: `frontend/src/app/auction/[publicId]/escrow/dispute/DisputeFormClient.tsx`
- Modify: `frontend/src/app/admin/infrastructure/ReconciliationSection.tsx`
- Modify: `frontend/src/app/users/[publicId]/page.tsx`, `page.test.tsx`
- Modify: `frontend/src/app/users/[publicId]/listings/page.tsx`
- Modify: `frontend/src/lib/user/api.ts`
- Modify: `frontend/src/lib/api.ts`

- [ ] **Step 1: Scoped grep**

```bash
git grep -nE 'SL Parcel Auctions|Second Life Parcel Auctions|\bSLPA\b' -- \
  'frontend/src/app/' 'frontend/src/lib/'
```

- [ ] **Step 2: Apply replacements**

`frontend/src/lib/api.ts` and `frontend/src/lib/user/api.ts` contain client-API helpers. Brand references in their JSDoc/comments swap. Identifier-style tokens (`SLPA_*` headers, etc.) stay.

- [ ] **Step 3: Re-run scoped grep — surviving matches must all be literal identifiers**

- [ ] **Step 4: Run targeted tests**

```bash
cd frontend && npx vitest run src/app/ src/lib/
```

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/ frontend/src/lib/
git commit -m "chore(rebrand): swap brand to SLParcels in pages and lib helpers"
```

---

### Task 7: Frontend snapshot refresh + lint + verify gate

**Files:**
- Possibly modify: any `__snapshots__/*.snap` files generated by `vitest -u`

- [ ] **Step 1: Run full frontend test suite (single pass, no update)**

```bash
cd frontend && npx vitest run
```

If all pass, skip to Step 3.

- [ ] **Step 2: If snapshot tests fail with brand-string-only diffs, refresh snapshots**

```bash
cd frontend && npx vitest run -u
```

Then review the snapshot diff:

```bash
git diff frontend/src/**/__snapshots__/
```

Each diff line must be a brand swap (`SL Parcel Auctions` / `SLPA` → `SLParcels`) and nothing else. If a snapshot diff contains an unrelated change, stop and investigate.

- [ ] **Step 3: Run frontend lint and verify guards**

```bash
cd frontend && npm run lint && npm run verify
```

Expected: both pass.

- [ ] **Step 4: Commit (only if snapshots changed)**

```bash
git add frontend/src/**/__snapshots__/
git commit -m "chore(rebrand): refresh frontend snapshots after brand swap"
```

If no snapshot changes, skip the commit.

---

### Task 8: Backend user-visible message templates

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisherImpl.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/notification/slim/SlImMessageBuilder.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/sl/SlExceptionHandler.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/sl/exception/AvatarAlreadyLinkedException.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/exception/AuctionExceptionHandler.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/wallet/exception/UserNotLinkedException.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/wallet/sl/WalletSlExceptionHandler.java`

- [ ] **Step 1: Scoped grep**

```bash
git grep -nE 'SL Parcel Auctions|Second Life Parcel Auctions|\bSLPA\b' -- \
  'backend/src/main/java/com/slparcelauctions/backend/notification/' \
  'backend/src/main/java/com/slparcelauctions/backend/sl/SlExceptionHandler.java' \
  'backend/src/main/java/com/slparcelauctions/backend/sl/exception/AvatarAlreadyLinkedException.java' \
  'backend/src/main/java/com/slparcelauctions/backend/auction/exception/AuctionExceptionHandler.java' \
  'backend/src/main/java/com/slparcelauctions/backend/wallet/exception/UserNotLinkedException.java' \
  'backend/src/main/java/com/slparcelauctions/backend/wallet/sl/WalletSlExceptionHandler.java'
```

- [ ] **Step 2: Apply replacements with strict identifier check**

User-visible message strings in these files end up in:
- IM messages sent to SL avatars
- Notification rows displayed in the dashboard
- HTTP error response bodies

Brand references swap. References to `SLPABot1 Resident` (or any literal SL avatar name) stay verbatim. Logger messages addressed at operators count as user-visible — swap brand text in those too.

**Critical:** the `com.slparcelauctions.backend` package import line at the top of every file is a literal identifier — leave the package declaration and all imports untouched.

- [ ] **Step 3: Re-run scoped grep — surviving matches must all be literal identifiers (mostly the package declaration + imports)**

- [ ] **Step 4: Skip targeted backend tests for now (Task 10 handles all backend tests together)**

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/notification/ \
  backend/src/main/java/com/slparcelauctions/backend/sl/SlExceptionHandler.java \
  backend/src/main/java/com/slparcelauctions/backend/sl/exception/AvatarAlreadyLinkedException.java \
  backend/src/main/java/com/slparcelauctions/backend/auction/exception/AuctionExceptionHandler.java \
  backend/src/main/java/com/slparcelauctions/backend/wallet/exception/UserNotLinkedException.java \
  backend/src/main/java/com/slparcelauctions/backend/wallet/sl/WalletSlExceptionHandler.java
git commit -m "chore(rebrand): swap brand to SLParcels in backend user-visible message templates"
```

---

### Task 9: Backend other source files (log messages, comments, JavaDoc)

**Files:** the ~17 backend Java main files listed under "Other backend source" in File Structure above.

- [ ] **Step 1: Scoped grep across the whole backend src/main tree, excluding files already swept in Task 8**

```bash
git grep -nE 'SL Parcel Auctions|Second Life Parcel Auctions|\bSLPA\b' -- \
  'backend/src/main/java/' \
  ':(exclude)backend/src/main/java/com/slparcelauctions/backend/notification/' \
  ':(exclude)backend/src/main/java/com/slparcelauctions/backend/sl/SlExceptionHandler.java' \
  ':(exclude)backend/src/main/java/com/slparcelauctions/backend/sl/exception/AvatarAlreadyLinkedException.java' \
  ':(exclude)backend/src/main/java/com/slparcelauctions/backend/auction/exception/AuctionExceptionHandler.java' \
  ':(exclude)backend/src/main/java/com/slparcelauctions/backend/wallet/exception/UserNotLinkedException.java' \
  ':(exclude)backend/src/main/java/com/slparcelauctions/backend/wallet/sl/WalletSlExceptionHandler.java'
```

- [ ] **Step 2: Per-file analysis before bulk edits**

Most matches in this set are:
- `package com.slparcelauctions.backend.*;` (literal — keep)
- `import com.slparcelauctions.backend.*;` (literal — keep)
- JavaDoc / inline comments referencing the brand (swap)
- Log message strings (swap brand text)
- Constants like `public static final String DEFAULT_FROM = "SLPA Notifications";` (swap brand text — that's a user-visible display name)

The `\bSLPA\b` grep result will include all the package/import declarations as noise. The implementer's job is to **only** swap the brand-context occurrences — comments, JavaDoc, log messages, user-visible string constants — and leave all `com.slparcelauctions.backend` package paths untouched.

- [ ] **Step 3: Apply replacements file-by-file with identifier discipline**

- [ ] **Step 4: Re-run scoped grep**

```bash
git grep -nE '\bSLPA\b' -- \
  'backend/src/main/java/' \
  ':(exclude)backend/src/main/java/com/slparcelauctions/backend/notification/' \
  # ... same exclusions as Step 1
```

Expected: every surviving match is one of:
- `package com.slparcelauctions.backend...;`
- `import com.slparcelauctions.backend...;`
- A `SLPA_*` env var reference inside `@Value("${SLPA_...}")` or similar

If any surviving match is a brand reference (comment, JavaDoc, log message, string constant), fix it before commit.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/
git commit -m "chore(rebrand): swap brand to SLParcels in backend source comments and log messages"
```

---

### Task 10: Backend test assertions + SQL migration comment + final backend test gate

**Files:**
- Modify: the 11 backend test files listed under "Backend test assertions" in File Structure
- Modify: `backend/src/main/resources/db/migration/V5__reconciliation_extension.sql` (comment text only)

- [ ] **Step 1: Scoped grep across backend test tree**

```bash
git grep -nE 'SL Parcel Auctions|Second Life Parcel Auctions|\bSLPA\b' -- \
  'backend/src/test/java/'
```

- [ ] **Step 2: Apply replacements to test assertion strings**

Tests assert exact substrings of message templates. If `SlImMessageBuilderTest` asserts `"Welcome to SLPA"` and the source was changed to `"Welcome to SLParcels"` in Task 8, the assertion must match. Update.

If the test asserts `SLPABot1 Resident` as part of a message about the bot avatar, that's a literal — stays.

Apply the same disambiguation rule from the spec.

- [ ] **Step 3: Sweep the SQL migration comment**

```bash
git grep -nE 'SL Parcel Auctions|Second Life Parcel Auctions|\bSLPA\b' -- \
  'backend/src/main/resources/db/migration/V5__reconciliation_extension.sql'
```

Apply replacements to comment lines (lines starting with `--`). DDL itself contains no brand strings.

- [ ] **Step 4: Run the full backend test suite**

```bash
cd backend && ./mvnw test
```

Expected: all tests pass. If a test fails on an assertion-string mismatch, the source/test pair is out of sync — fix and re-run.

- [ ] **Step 5: Re-run scoped grep across backend tree (main + test)**

```bash
git grep -nE 'SL Parcel Auctions|Second Life Parcel Auctions' -- backend/
```

Expected: zero matches.

```bash
git grep -nE '\bSLPA\b' -- backend/
```

Expected: surviving matches are only package/import declarations and `SLPA_*` env var references.

- [ ] **Step 6: Commit**

```bash
git add backend/src/test/java/ backend/src/main/resources/db/migration/V5__reconciliation_extension.sql
git commit -m "chore(rebrand): swap brand to SLParcels in backend tests and SQL migration comment"
```

---

### Task 11: Top-level live docs (README, CLAUDE, PREP, FULL_TESTING_PROCEDURES, .env.example)

**Files:**
- Modify: `README.md`
- Modify: `CLAUDE.md`
- Modify: `PREP.md`
- Modify: `FULL_TESTING_PROCEDURES.md`
- Modify: `.env.example`

- [ ] **Step 1: Scoped grep**

```bash
git grep -nE 'SL Parcel Auctions|Second Life Parcel Auctions|\bSLPA\b' -- \
  README.md CLAUDE.md PREP.md FULL_TESTING_PROCEDURES.md .env.example
```

- [ ] **Step 2: Apply replacements with strict identifier discipline**

Heavy identifier traffic in this set:

- README.md line 1: `# SLPA — Second Life Parcel Auctions` → `# SLParcels`
- CLAUDE.md line 7: `SLPA (Second Life Parcel Auctions) is a player-to-player...` → `SLParcels is a player-to-player...`
- CLAUDE.md line 23: `logs in as SLPABot* accounts` — `SLPABot*` is literal → stays
- CLAUDE.md mentions of `slpa-prod` cluster, `/slpa/prod/` SSM, `SLPABot*`, `SLPAEscrow Resident`, `slpa-dev-key`, the Postman `SLPA` workspace — all literal → stay
- CLAUDE.md "Postman collection" sentence: applies risk R2 from the spec. Re-read the surrounding sentence — may need light rephrasing so it doesn't sound contradictory.
- PREP.md mentions of "the launch-lite SLPA bill" → "the launch-lite SLParcels bill" (brand)
- PREP.md mentions of the `slpa-prod` AWS profile, `/slpa/prod/*` SSM, etc. → stay
- FULL_TESTING_PROCEDURES.md table of avatar names: `SLPABot1 Resident`, `SLPAEscrow Resident` — stay; surrounding prose about "the SLPA bot pool" → swap to "the SLParcels bot pool"
- `.env.example` comments: `# SLPA local development environment` → `# SLParcels local development environment`; `SLPA_*` variable names stay literal

**Em-dash check on README.md line 1:** the spec calls out that the recent em-dash sweep may not have covered this line. After the rebrand swap collapses it to just `# SLParcels`, the em-dash is gone naturally. Verify post-edit.

- [ ] **Step 3: Re-run scoped grep**

```bash
git grep -nE '\bSLPA\b' -- README.md CLAUDE.md PREP.md FULL_TESTING_PROCEDURES.md .env.example
```

Surviving matches: only `SLPABot*`, `SLPAEscrow*`, `SLPA_*` env vars, the `SLPA` Postman workspace name, and `slpa-*` AWS resource names. Each must be classifiable as identifier.

- [ ] **Step 4: Commit**

```bash
git add README.md CLAUDE.md PREP.md FULL_TESTING_PROCEDURES.md .env.example
git commit -m "chore(rebrand): swap brand to SLParcels in top-level live docs"
```

---

### Task 12: Bot, LSL READMEs, notecards, and LSL source files

**Files:**
- Modify: `bot/README.md`, `bot/.env.example`
- Modify: `lsl-scripts/README.md`
- Modify: `lsl-scripts/slpa-terminal/README.md`, `slpa-terminal/config.notecard.example`
- Modify: `lsl-scripts/slpa-verifier-giver/README.md`, `slpa-verifier-giver/config.notecard.example`
- Modify: `lsl-scripts/verification-terminal/README.md`, `verification-terminal/config.notecard.example`
- Modify: `lsl-scripts/parcel-verifier/README.md`, `parcel-verifier/config.notecard.example`
- Modify: `lsl-scripts/sl-im-dispatcher/README.md`
- Modify: `lsl-scripts/verification-terminal/verification-terminal.lsl`
- Modify: `lsl-scripts/parcel-verifier/parcel-verifier.lsl`

- [ ] **Step 1: Scoped grep**

```bash
git grep -nE 'SL Parcel Auctions|Second Life Parcel Auctions|\bSLPA\b' -- \
  bot/ lsl-scripts/
```

- [ ] **Step 2: Apply replacements with strict LSL discipline**

For `.lsl` files:
- Comments (`//`, `/* */`) → swap brand text
- `llSay`, `llDialog`, `llRegionSay`, `llOwnerSay`, `llInstantMessage`, `llRegionSayTo` argument strings → swap brand text
- Variable names, function names, constants like `LINK_SLPA_*`, JSON keys in HTTP bodies → **stay literal**

For READMEs and notecards:
- Brand references swap
- `SLPABot1`, `SLPAEscrow Resident`, folder paths like `slpa-terminal/`, env var names like `SLPA_BOT_SHARED_SECRET` → stay literal
- The `slpa-terminal/` and `slpa-verifier-giver/` folder names appearing in instructions stay (filesystem paths)

- [ ] **Step 3: Re-run scoped grep**

```bash
git grep -nE '\bSLPA\b' -- bot/ lsl-scripts/
```

Surviving matches must all be literals.

- [ ] **Step 4: Commit**

```bash
git add bot/ lsl-scripts/
git commit -m "chore(rebrand): swap brand to SLParcels in bot and LSL docs and scripts"
```

---

### Task 13: docs/implementation/ live files + ops + backend test README

**Files:** `docs/implementation/{DEFERRED_WORK,FOOTGUNS,PHASES,CLEANED_FROM_DEFERRED,CONVENTIONS}.md`, `docs/postman-publicid-migration-checklist.md`, `docs/testing/EPIC_6_TESTING.md`, and `backend/src/test/java/com/slparcelauctions/backend/auth/test/README.md`.

The entire `docs/implementation/epic-*/**` tree is **skipped** per user decision (plan deviation #1).

- [ ] **Step 1: Scoped grep**

```bash
git grep -nE 'SL Parcel Auctions|Second Life Parcel Auctions|\bSLPA\b' -- \
  'docs/implementation/DEFERRED_WORK.md' \
  'docs/implementation/FOOTGUNS.md' \
  'docs/implementation/PHASES.md' \
  'docs/implementation/CLEANED_FROM_DEFERRED.md' \
  'docs/implementation/CONVENTIONS.md' \
  'docs/postman-publicid-migration-checklist.md' \
  'docs/testing/EPIC_6_TESTING.md' \
  'backend/src/test/java/com/slparcelauctions/backend/auth/test/README.md'
```

- [ ] **Step 2: Apply replacements**

All of these are live documentation. Brand references swap. The usual suspects (`SLPABot*`, `slpa-prod`, `SLPA_*`, `com.slparcelauctions`) stay literal.

`DEFERRED_WORK.md` is in the swept set — Task 16 also appends a row to it. Both edits land in the same PR but as separate commits.

- [ ] **Step 3: Re-run scoped grep — surviving matches must all be literal identifiers**

```bash
git grep -nE '\bSLPA\b' -- \
  'docs/implementation/DEFERRED_WORK.md' \
  'docs/implementation/FOOTGUNS.md' \
  'docs/implementation/PHASES.md' \
  'docs/implementation/CLEANED_FROM_DEFERRED.md' \
  'docs/implementation/CONVENTIONS.md' \
  'docs/postman-publicid-migration-checklist.md' \
  'docs/testing/EPIC_6_TESTING.md' \
  'backend/src/test/java/com/slparcelauctions/backend/auth/test/README.md'
```

- [ ] **Step 4: Commit**

```bash
git add docs/implementation/DEFERRED_WORK.md docs/implementation/FOOTGUNS.md docs/implementation/PHASES.md docs/implementation/CLEANED_FROM_DEFERRED.md docs/implementation/CONVENTIONS.md docs/postman-publicid-migration-checklist.md docs/testing/EPIC_6_TESTING.md backend/src/test/java/com/slparcelauctions/backend/auth/test/README.md
git commit -m "chore(rebrand): swap brand to SLParcels in live implementation guides and ops docs"
```

---

### Task 14: docs/final-design/ + top-level config + infra Terraform comments (per spec deviation #2)

**Files:**
- Modify: `docs/final-design/slparcels-website/README.md`
- Modify: `docs/final-design/slparcels-website/project/page-static.jsx`
- Modify: `docs/final-design/slparcels-website/project/page-dashboard.jsx`
- Modify: `docs/final-design/slparcels-website/project/page-listing-flow.jsx`
- Modify: `docs/final-design/slparcels-website/project/admin-shell.jsx`
- Modify: `docker-compose.yml`
- Modify: `backend/Dockerfile`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/resources/application-prod.yml`
- Modify: `infra/main.tf`, `infra/outputs.tf`, `infra/variables.tf`, `infra/terraform.tfvars.example`
- Modify: `infra/dns/route53.tf`
- Modify: `infra/data/elasticache.tf`, `infra/data/s3.tf`
- Modify: `infra/networking/security_groups.tf`
- Modify: `infra/compute/alb.tf`, `infra/compute/ecs_backend.tf`
- Modify: `infra/cicd/main.tf`
- Modify: `infra/observability/main.tf`

- [ ] **Step 1: Scoped grep**

```bash
git grep -nE 'SL Parcel Auctions|Second Life Parcel Auctions|\bSLPA\b' -- \
  docs/final-design/ docker-compose.yml backend/Dockerfile \
  backend/src/main/resources/application.yml \
  backend/src/main/resources/application-prod.yml \
  infra/
```

- [ ] **Step 2: Apply replacements with strict per-file-type rules**

**docs/final-design/ (full sweep):** Brand references swap; identifier discipline as elsewhere.

**docker-compose.yml, backend/Dockerfile, application.yml, application-prod.yml (comments only):** Comment text gets swept. YAML keys (`slpa.bot.*`, etc.), env var literals (`SLPA_*`), and shell-interpolated identifiers stay literal.

**infra/ (COMMENTS ONLY — per user decision on plan deviation #2):** ONLY `#` comment lines in `.tf` and `.tfvars.example` files get swept. Terraform `description = "..."` attributes, `error_message = "..."` strings, variable defaults, locals, and any other non-comment string content stay literal **even when they contain `SLPA`**.

Example ALLOWED edit in `infra/main.tf`:

```diff
-# SLPA — Terraform root module
+# SLParcels — Terraform root module

-# Deploys the SLPA production stack on AWS. See:
+# Deploys the SLParcels production stack on AWS. See:
```

Example KEEP-AS-IS (description string is NOT a comment) in `infra/data/elasticache.tf`:

```
  description          = "SLPA ${var.environment} Redis - sessions, bid rate-limit counters, auction countdown timers, bot heartbeat state."
```

This line stays unchanged. Per user direction, we do not edit Terraform string attributes.

Example KEEP-AS-IS (error_message string is NOT a comment) in `infra/variables.tf`:

```
    error_message = "Bot pool is fixed at 5 named workers (SLPABot1-5). Set between 1 and 5."
```

This line stays unchanged for two reasons: it's a string attribute (per user direction), and the `SLPABot1-5` token is a literal avatar reference anyway.

Example KEEP-AS-IS in `infra/compute/ecs_backend.tf`:

```
# (JWT_SECRET, SLPA_BOT_SHARED_SECRET, SLPA_PRIMARY_ESCROW_UUID,
```

This IS a comment, but the `SLPA_BOT_SHARED_SECRET` and `SLPA_PRIMARY_ESCROW_UUID` tokens are env var identifiers → stay literal. So while the implementer is *allowed* to edit comment lines in `infra/`, on this specific line nothing actually changes (no brand references, only identifiers).

- [ ] **Step 3: Re-run scoped grep**

```bash
git grep -nE '\bSLPA\b' -- docs/final-design/ docker-compose.yml backend/Dockerfile backend/src/main/resources/application*.yml infra/
```

Surviving matches must all classify as one of:
- Literal env var (`SLPA_*`)
- Literal config key (`slpa.*`)
- Literal avatar name (`SLPABot*`, `SLPAEscrow*`)
- Literal AWS resource name (`slpa-prod*`)
- **In `infra/` only:** brand text inside a non-comment Terraform string attribute (allowed per user direction — these stay literal even when not identifier-shaped)

- [ ] **Step 4: No tests run for this task — config + Terraform syntax is verified by build/deploy pipelines, not unit tests**

Optional: validate Terraform formatting with `terraform fmt -check infra/` if you have terraform installed locally. If not, skip.

- [ ] **Step 5: Commit**

```bash
git add docs/final-design/ docker-compose.yml backend/Dockerfile \
  backend/src/main/resources/application.yml \
  backend/src/main/resources/application-prod.yml \
  infra/
git commit -m "chore(rebrand): swap brand to SLParcels in final-design references, top-level config, and infra comments"
```

---

### Task 15: Verification sweep (V1, V2, V2.5)

**Files:** none modified in this task; just verifies the work from Tasks 2-14.

- [ ] **Step 1: V1 — multi-word brand strings must be zero**

```bash
git ls-files -z \
  ':(exclude)docs/superpowers/specs/2026-*' \
  ':(exclude)docs/superpowers/plans/2026-*' \
  ':(exclude)docs/stitch_generated-design/**' \
  ':(exclude)docs/initial-design/DESIGN.md' \
  ':(exclude)docs/initial-design/INITIAL_DESIGN.md' \
  ':(exclude)docs/initial-design/LINDEN_LAB_PARTNERSHIP.md' \
  ':(exclude)docs/initial-design/CREATOR_PARTNERSHIP_PROGRAM.md' \
  ':(exclude)docs/implementation/epic-00/00-stitch-design-system.md' \
  | xargs -0 grep -nE 'SL Parcel Auctions|Second Life Parcel Auctions'
```

Expected: zero output. If any match, identify and fix.

- [ ] **Step 2: V2 — bare SLPA audit and classification**

```bash
git ls-files -z \
  ':(exclude)docs/superpowers/specs/2026-*' \
  ':(exclude)docs/superpowers/plans/2026-*' \
  ':(exclude)docs/stitch_generated-design/**' \
  ':(exclude)docs/initial-design/DESIGN.md' \
  ':(exclude)docs/initial-design/INITIAL_DESIGN.md' \
  ':(exclude)docs/initial-design/LINDEN_LAB_PARTNERSHIP.md' \
  ':(exclude)docs/initial-design/CREATOR_PARTNERSHIP_PROGRAM.md' \
  ':(exclude)docs/implementation/epic-00/00-stitch-design-system.md' \
  | xargs -0 grep -nE '\bSLPA\b' \
  > /tmp/slparcels-v2-final.txt 2>&1
wc -l /tmp/slparcels-v2-final.txt
```

Read every line of the output and classify:

- **Identifier (allowed):**
  - `SLPABot1`, `SLPABot2`, `SLPABot*`, `SLPABot1-5` (avatar names)
  - `SLPAEscrow Resident`, `SLPAEscrow ${var.environment}`, etc.
  - `SLPA_*` env vars (`SLPA_BOT_SHARED_SECRET`, `SLPA_PRIMARY_ESCROW_UUID`, etc.)
  - `SLPA` workspace name in Postman wayfinding sentences
  - `SLPA Dev` Postman environment name
  - `package com.slparcelauctions...;` and `import com.slparcelauctions...;` declarations
- **Allowed in `infra/` only (per plan deviation #2 user resolution):** brand text appearing inside a Terraform non-comment string attribute (`description = "SLPA ..."`, `error_message = "...SLPA..."`, etc.). Per user direction, these stay literal. They count as allowed.
- **Allowed in `docs/implementation/epic-*/` (per plan deviation #1 user resolution):** any `SLPA` matches in this tree are allowed because the entire tree is skipped. (V2's exclusion list does NOT exclude this tree by default — if matches surface here, they're expected and allowed.)
- **Brand (bug):** any prose use of `SLPA` as a product/brand reference outside the allowed zones above. Fix immediately.

If any match falls into the brand bucket, edit the file, re-run V2, until classification is clean.

- [ ] **Step 3: V2.5 — negative grep for incorrectly-introduced new-form identifiers**

```bash
git ls-files -z \
  ':(exclude)docs/superpowers/specs/2026-*' \
  ':(exclude)docs/superpowers/plans/2026-*' \
  ':(exclude)docs/stitch_generated-design/**' \
  ':(exclude)docs/initial-design/DESIGN.md' \
  ':(exclude)docs/initial-design/INITIAL_DESIGN.md' \
  ':(exclude)docs/initial-design/LINDEN_LAB_PARTNERSHIP.md' \
  ':(exclude)docs/initial-design/CREATOR_PARTNERSHIP_PROGRAM.md' \
  ':(exclude)docs/implementation/epic-00/00-stitch-design-system.md' \
  | xargs -0 grep -nE 'SLParcelsBot|SLParcelsEscrow|slparcels-prod[-/]'
```

Expected: zero matches. Catches autopilot identifier-rename mistakes.

- [ ] **Step 4: If any V1/V2/V2.5 fixes were needed, commit them**

```bash
git add <files>
git commit -m "chore(rebrand): fix stragglers found in verification sweep"
```

---

### Task 16: V3 build + test gates and DEFERRED_WORK row

**Files:**
- Modify: `docs/implementation/DEFERRED_WORK.md` (append a single row referencing the rebrand spec)

- [ ] **Step 1: Frontend full verification**

```bash
cd frontend && npm run lint && npm test -- --run && npm run verify
```

Expected: all pass.

- [ ] **Step 2: Backend full test**

```bash
cd backend && ./mvnw test
```

Expected: all pass.

- [ ] **Step 3: Append a deferred-work row to `DEFERRED_WORK.md`**

The row must be a cross-reference, not an inline enumeration of the 14 deferred items (per spec V2.5 rule).

Read `docs/implementation/DEFERRED_WORK.md` to find the existing format, then append:

```
| 2026-05-04 | SLParcels rebrand follow-ups | Code-identifier rename (Java pkg, config prefix, env var prefix), AWS resource recreation, SL avatar provisioning, Postman workspace rename, repo folder renames, historical/generated/aspirational doc sweep, memory file sweep, local working-dir rename. See spec `docs/superpowers/specs/2026-05-04-slparcels-rebrand-design.md` for full enumeration. | open |
```

(Match the existing column shape — date / summary / details / status. Verify the schema before writing.)

- [ ] **Step 4: Re-run V2.5 to confirm the new row didn't introduce forbidden identifiers**

```bash
git ls-files -z \
  ':(exclude)docs/superpowers/specs/2026-*' \
  ':(exclude)docs/superpowers/plans/2026-*' \
  ':(exclude)docs/stitch_generated-design/**' \
  ':(exclude)docs/initial-design/DESIGN.md' \
  ':(exclude)docs/initial-design/INITIAL_DESIGN.md' \
  ':(exclude)docs/initial-design/LINDEN_LAB_PARTNERSHIP.md' \
  ':(exclude)docs/initial-design/CREATOR_PARTNERSHIP_PROGRAM.md' \
  ':(exclude)docs/implementation/epic-00/00-stitch-design-system.md' \
  | xargs -0 grep -nE 'SLParcelsBot|SLParcelsEscrow|slparcels-prod[-/]'
```

Expected: still zero.

- [ ] **Step 5: Commit**

```bash
git add docs/implementation/DEFERRED_WORK.md
git commit -m "docs(deferred): record SLParcels rebrand follow-up items"
```

---

### Task 17: Push and open PR

**Files:** none modified.

- [ ] **Step 1: Push to origin/dev**

```bash
git push origin dev
```

- [ ] **Step 2: Open the PR (target = dev's PR-into-main? No — feature branch PRs go INTO dev. We're already on dev, so this work merges via a PR from `dev` into `main` only when the user reviews + authorizes. No PR for this commit-on-dev sequence.)**

Per the user's `feedback_no_merge_to_main` memory: Claude does not merge into `main` — the user reviews and merges `dev` → `main` themselves. So this task ends with the dev branch pushed, and the user takes it from there.

If the user later asks for a `dev` → `main` PR, use `gh pr create --base main --head dev` with a body summarizing the rebrand sweep.

---

## Self-Review

Plan vs. spec coverage:

- ✅ Spec Goal (no live doc/page shows old brand) — covered by Tasks 2-14 + verification 15
- ✅ Spec Non-Goals (no Java pkg, no AWS, no SL avatars, no Postman) — explicit in identifier-discipline language throughout
- ✅ Spec mapping rules (brand-form table + literal-identifier list + disambiguation rule) — applied per task
- ✅ Spec File Scope Buckets 1-3 — Tasks 2-13 cover frontend, backend, and live docs
- ✅ Spec Bucket 4 (skipped) — exclusions in every grep command
- ✅ Spec Edge cases (casing, possessive, em-dash, alt text, snapshot tests, domain refs, line wrapping, LSL discipline, transitional regex) — covered in per-task instructions
- ✅ Spec Verification V1/V2/V2.5/V3 — Task 15 + Task 16
- ✅ Spec Deferred Work row — Task 16 step 3
- ✅ Spec Risks R1-R6 — addressed by V2.5, R2 wording note in Task 11, R3 snapshot review in Task 7, R4 asset audit in Task 1, R5 acknowledged in PR description, R6 no action needed
- ⚠️ Spec deviation #1 (epic-NN files) — user resolved: SKIP. Entire `docs/implementation/epic-*/**` tree excluded from sweep.
- ⚠️ Spec deviation #2 (infra Terraform) — user resolved: comments only. Only `#` comment lines in `infra/` swept; non-comment string attributes (`description`, `error_message`, etc.) stay literal even when they contain brand text.

Placeholder scan: no "TBD", no "implement later", no "similar to Task N" without code, no vague "handle edge cases".

Type consistency: every command, exclusion list, and grep pattern is identical across V1/V2/V2.5 invocations.

---

## Execution

Plan complete and saved to `docs/superpowers/plans/2026-05-04-slparcels-rebrand.md`.

Per the user's "Implement" directive in the brainstorming session and given the per-task identifier-discipline judgment this plan requires (R1 is the highest-risk failure mode), inline execution with checkpoints between phases is the safer mode. Subagent dispatch would lose the disambiguation context. Default: **inline execution via superpowers:executing-plans**, with a review checkpoint after each phase (Frontend / Backend / Docs / Verification).
