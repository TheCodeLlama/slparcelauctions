# AI Language Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove em-dashes (`—`) and en-dashes (`–`) from user/admin-visible copy across frontend, backend, and LSL surfaces, plus produce a local-only AI-wording audit doc in `.ignored-docs/`.

**Architecture:** Pure copy sweep across three source trees. Two commits land on `dev`: (1) the `.gitignore` line that scopes `.ignored-docs/` out of the repo, (2) the bundled em-dash + en-dash replacements. The audit doc itself stays local — it's the worker's notes for follow-up triage, never reaches GitHub.

**Tech Stack:** TypeScript / React (frontend), Java / Spring Boot 4 (backend), LSL (Second Life scripting), `grep` / `git` / `gh`.

**Spec:** `docs/superpowers/specs/2026-05-04-ai-language-cleanup-design.md`

**Branch:** new feature branch off `dev`, named `task/language-cleanup`. PR target is `dev` per CLAUDE.md branch policy. No worktree (mechanical, low conflict).

**Two commits in the final MR:**

1. `chore: gitignore .ignored-docs/`
2. `chore(language): remove em-dashes from user/admin-visible copy`

During implementation, accumulate changes locally without intermediate commits to the sweep so the final commit is one logical change. Run lint/tests after each rule to catch fallout early; only commit when every sub-pass is green.

---

## File Structure

**Created (local-only, gitignored):**
- `.ignored-docs/ai-wording-audit-2026-05-04.md` — manual audit findings; never committed

**Modified (committed):**
- `.gitignore` — line already present in working copy, needs to be staged + committed
- `frontend/src/**/*.tsx` and `frontend/src/**/*.ts` — UI copy, admin tables/cards, modals, toasts, status copy, marketing/auth/listing/auction surfaces
- `frontend/src/**/*.test.tsx` and `frontend/src/**/*.test.ts` — only assertion lines that reference a changed string
- `backend/src/main/java/**/*.java` — `log.*` strings, exception detail strings, notification IM/email body literals
- `backend/src/test/java/**/*.java` — only assertion lines that reference a changed string
- `lsl-scripts/**/*.lsl` — `llSay`, `llRegionSayTo`, `llDialog`, `llInstantMessage`, `llOwnerSay` strings

**Explicitly out of scope (per spec):**
- "Curator" terminology — retained
- Code comments / JSDoc / Javadoc / LSL `//` comments
- `lsl-scripts/**/README.md`
- `docs/stitch_generated-design/**`
- AI-flagged copy rewrites (deferred to user triage of the audit doc)

---

## Task 0: Create the feature branch off `dev`

**Files:** none (branch operation only)

The user's in-progress `dev` working copy contains uncommitted edits to several admin-table files (`?? "—"` → `?? "-"`) and an uncommitted `.gitignore` change. Those edits ARE part of this MR's scope — they get carried onto the new feature branch with `git switch -c`, which preserves working-tree state.

- [ ] **Step 1: Confirm starting state**

```bash
cd /c/Users/heath/Repos/Personal/slpa
git rev-parse --abbrev-ref HEAD
git status --short
```

Expected: current branch is `dev`. `git status` shows the user's uncommitted edits (the `.gitignore` modification plus a set of admin-table `.tsx` files).

- [ ] **Step 2: Pull origin/dev so the new branch starts from the latest**

```bash
git pull --ff-only origin dev
```

If this fails with "not a fast-forward", stop and ask — `dev` has diverged in a way that needs human judgment.

- [ ] **Step 3: Create and switch to the feature branch (carries uncommitted edits)**

```bash
git switch -c task/language-cleanup
git status --short
```

Expected: now on `task/language-cleanup`. `git status` still shows the same uncommitted edits — `git switch -c` preserves working-tree state.

---

## Task 1: Read the spec and run pre-flight inventory

**Files:**
- Read: `docs/superpowers/specs/2026-05-04-ai-language-cleanup-design.md`
- Read: `frontend/CLAUDE.md` (Next.js 16 caveats)
- Read: `frontend/AGENTS.md`

- [ ] **Step 1: Read the spec end-to-end**

The spec defines three replacement rules and the AI-wording audit format. The plan reproduces these rules inline in subsequent tasks, but the spec is authoritative if anything is ambiguous.

- [ ] **Step 2: Confirm clean working tree (modulo expected uncommitted edits)**

```bash
cd /c/Users/heath/Repos/Personal/slpa
git status
```

Expected: Modified files include `.gitignore` (the `.ignored-docs/` addition the user already made) and a set of admin-table files where `?? "—"` was already swapped to `?? "-"`. No untracked files except possibly the user's local `.ignored-docs/` directory.

If unexpected uncommitted changes exist beyond the user's in-progress sweep, stop and ask before proceeding.

- [ ] **Step 3: Generate the em-dash inventory**

```bash
echo "=== Frontend em-dash hits (likely candidates) ==="
grep -rn --include="*.tsx" --include="*.ts" -- "—" frontend/src

echo "=== Frontend en-dash hits ==="
grep -rn --include="*.tsx" --include="*.ts" -- "–" frontend/src

echo "=== Backend em-dash hits (Java main) ==="
grep -rn --include="*.java" -- "—" backend/src/main

echo "=== Backend en-dash hits (Java main) ==="
grep -rn --include="*.java" -- "–" backend/src/main

echo "=== LSL em-dash hits ==="
grep -rn --include="*.lsl" -- "—" lsl-scripts

echo "=== LSL en-dash hits ==="
grep -rn --include="*.lsl" -- "–" lsl-scripts

echo "=== Test files asserting on em-dashed strings ==="
grep -rn --include="*.test.tsx" --include="*.test.ts" -- "—" frontend/src
grep -rn --include="*Test.java" -- "—" backend/src/test
```

Capture the output; it's the working list for Tasks 4–7. Each hit is later triaged into one of three buckets:
1. **In a comment / JSDoc / Javadoc / LSL `//`** — leave alone.
2. **In user/admin-visible copy** — fix per Rules 1, 2, or 3 (Tasks 4–7).
3. **In a test assertion** — update in lockstep with the matching production string change.

A line like `* <li>{@code WINNING} — tertiary-container</li>` is bucket 1 (Javadoc inside a Java file or JSDoc in a `.tsx` file). Skip it.

A line like `<td>{value ?? "—"}</td>` is bucket 2, Rule 1.

A line like `expect(screen.getByText("—")).toBeInTheDocument()` is bucket 3.

---

## Task 2: Generate the AI-wording audit doc (local-only)

**Files:**
- Create: `.ignored-docs/ai-wording-audit-2026-05-04.md` — gitignored

This task produces local-only notes. Per the spec, the doc covers AI-tell *non-em-dash* patterns only. Em-dashes are handled in-place in Tasks 4–7, not enumerated in the audit doc.

- [ ] **Step 1: Confirm `.ignored-docs/` is gitignored**

```bash
git check-ignore -v .ignored-docs/test.md
```

Expected: prints a line ending with `.ignored-docs` confirming the rule matches. If nothing prints, the gitignore line is missing — stop and inspect `.gitignore`.

- [ ] **Step 2: Create the doc with the spec-mandated header**

```bash
cat > .ignored-docs/ai-wording-audit-2026-05-04.md <<'EOF'
# AI-Wording Audit — 2026-05-04

Bar: "standard" per brainstorm — flags well-known LLM lexical tells in
customer/admin-visible copy. Em-dash hits are NOT in this doc; they ship
in the same MR as in-place fixes. This doc is a triage list, not a
delivered change.

Patterns flagged:
- "delve", "leverage" (v.), "robust", "seamless", "comprehensive"
- "ensure" (when overused), "navigate to", "facilitate", "harness"
- "empower", "elevate", "cutting-edge", "state-of-the-art"
- "Furthermore,"/"Moreover,"/"It's worth noting that..."
- Tricolon abuse in marketing prose ("X, Y, and Z")
- Parallel "Whether you're X or Y..." constructions

---

## Frontend

(entries here)

## Backend

(entries here)

## LSL

(entries here)
EOF
```

- [ ] **Step 3: Run the AI-tell sweep across in-scope surfaces**

For each pattern, run the grep, manually triage each hit:
- Is it in a comment / JSDoc / Javadoc? → skip
- Is it in code (variable name, type name)? → skip
- Is it in a customer/admin-visible string? → write an entry

```bash
PATTERNS=(
  "delve"
  "leverage"
  "robust"
  "seamless"
  "comprehensive"
  "navigate to"
  "facilitate"
  "harness"
  "empower"
  "elevate"
  "cutting-edge"
  "state-of-the-art"
  "Furthermore,"
  "Moreover,"
  "It's worth noting"
  "It's important to note"
  "Whether you're"
)

for p in "${PATTERNS[@]}"; do
  echo "=== Frontend: $p ==="
  grep -rn -i --include="*.tsx" --include="*.ts" -- "$p" frontend/src 2>/dev/null \
    | grep -v "\.test\." | head -30
  echo "=== Backend: $p ==="
  grep -rn -i --include="*.java" -- "$p" backend/src/main 2>/dev/null | head -30
  echo "=== LSL: $p ==="
  grep -rn -i --include="*.lsl" -- "$p" lsl-scripts 2>/dev/null | head -30
done
```

Note: "ensure" is intentionally omitted from the script above because grepping for "ensure" returns hundreds of false positives (variable names like `ensureXxx`, JSDoc, etc.). Triage "ensure" by reading the user-visible copy lists from Step 4, not by grep.

- [ ] **Step 4: For each user/admin-visible hit, append an entry to the audit doc**

Per-entry format (exact fields, copy this template):

```markdown
### `<file>:<line>`

**Current:**
> <verbatim text from the source>

**Suggested non-AI wording:**
> <proposed rewrite, preserving meaning>

**Why flagged:** <one-line reason naming the pattern(s)>
```

Group entries by surface (Frontend / Backend / LSL section headings already in the doc), within each surface order entries by file path then line number.

Example entry (from the spec):

```markdown
### `frontend/src/components/marketing/Hero.tsx:24`

**Current:**
> Discover and acquire premium parcels in vibrant Second Life regions.

**Suggested non-AI wording:**
> Buy and sell parcels in Second Life.

**Why flagged:** "Discover", "acquire", "premium", "vibrant" — four marketing-copy tells in one sentence. Direct verbs ("buy and sell") and concrete subject ("parcels") read as human-written.
```

If a hit is *not* in user/admin-visible copy (e.g. it's in a JSDoc or a variable name), do not write an entry — just move on.

- [ ] **Step 5: Verify the audit doc is gitignored and not staged**

```bash
git status --short .ignored-docs/
git status --short
```

Expected: `git status --short .ignored-docs/` returns nothing (gitignored). The main `git status` output should NOT list `.ignored-docs/ai-wording-audit-2026-05-04.md`.

If the file appears in `git status`, the gitignore rule isn't catching it — stop and inspect `.gitignore`.

---

## Task 3: Commit the `.gitignore` change

**Files:**
- Modify: `.gitignore` (already modified in working copy)

- [ ] **Step 1: Confirm the diff is exactly the `.ignored-docs/` line**

```bash
git diff -- .gitignore
```

Expected output (current head of file may differ; what matters is the addition):

```
+
+.ignored-docs
```

If the diff has any other changes, separate them out before staging.

- [ ] **Step 2: Stage just `.gitignore` and commit**

```bash
git add .gitignore
git commit -m "chore: gitignore .ignored-docs/

Local scratch space for audit notes that should not reach the repo
(e.g. the AI-wording audit produced as part of the language cleanup
pass)."
```

- [ ] **Step 3: Verify commit landed alone**

```bash
git log -1 --stat
```

Expected: one file changed (`.gitignore`), 2 insertions, 0 deletions (or thereabouts — depends on whether the original file ended with a newline).

---

## Task 4: Frontend Rule 1 sweep — data-placeholder glyphs

**Files:** every frontend file with `?? "—"` or `? "—"` patterns. Identified by:

```bash
grep -rn --include="*.tsx" --include="*.ts" -- '?? "—"' frontend/src
grep -rn --include="*.tsx" --include="*.ts" -- ': "—"' frontend/src
```

Known stragglers from the user's in-progress sweep (per the spec):
- `frontend/src/app/admin/infrastructure/TerminalsSection.tsx:51`
- `frontend/src/app/admin/infrastructure/TerminalsSection.tsx:55`
- `frontend/src/app/admin/infrastructure/WithdrawalsHistorySection.tsx:30`

- [ ] **Step 1: Apply Rule 1 to every hit**

For each `"—"` used as a data placeholder, replace with `"-"` (ASCII hyphen). One-character mechanical swap.

Example fix (TerminalsSection.tsx:51):

```tsx
// Before
<td className="py-2">{t.regionName ?? "—"}</td>

// After
<td className="py-2">{t.regionName ?? "-"}</td>
```

Example fix (TerminalsSection.tsx:55, has a prefix):

```tsx
// Before
<td className="py-2">v{t.currentSecretVersion ?? "—"}</td>

// After
<td className="py-2">v{t.currentSecretVersion ?? "-"}</td>
```

- [ ] **Step 2: Re-run the grep to confirm zero remaining Rule-1 hits**

```bash
grep -rn --include="*.tsx" --include="*.ts" -- '?? "—"' frontend/src
grep -rn --include="*.tsx" --include="*.ts" -- ': "—"' frontend/src
```

Expected: empty output.

- [ ] **Step 3: Run frontend type-check + lint**

```bash
cd frontend
npx tsc --noEmit
npm run lint
cd ..
```

Expected: both pass. Rule-1 changes are pure-string and shouldn't affect either, but run early to catch surprise.

---

## Task 5: Frontend Rule 2 sweep — loading-state glyph

**Files:**
- Modify: `frontend/src/components/curator/CuratorTrayTrigger.tsx:31`
- Possibly modify: `frontend/src/components/curator/CuratorTrayTrigger.test.tsx` (if it asserts on `"—"`)

- [ ] **Step 1: Update the loading label**

Edit `frontend/src/components/curator/CuratorTrayTrigger.tsx`, around line 31:

```tsx
// Before
const label = isLoading ? "—" : count >= 100 ? "99+" : String(count);

// After
const label = isLoading ? "-" : count >= 100 ? "99+" : String(count);
```

Also update the JSDoc above the function so it doesn't reference `"—"` as the loading glyph anymore. The JSDoc itself is dev-facing (out of scope for the sweep), but if it explicitly documents the glyph, the doc would mislead future readers if the comment still says `"—"`. Check whether the JSDoc mentions the glyph and update if so:

```tsx
// Before (around lines 16-19)
 * <p>Count rendering:
 *   - {@code —} while the initial {@code useSavedIds} fetch is in flight.
 *   - Literal {@code 1}..{@code 99} for small sets.
 *   - {@code 99+} once the set has >= 100 entries.

// After
 * <p>Count rendering:
 *   - {@code -} while the initial {@code useSavedIds} fetch is in flight.
 *   - Literal {@code 1}..{@code 99} for small sets.
 *   - {@code 99+} once the set has >= 100 entries.
```

- [ ] **Step 2: Update the test if it asserts on `"—"`**

```bash
grep -n -- "—" frontend/src/components/curator/CuratorTrayTrigger.test.tsx
```

If the test references `"—"` (e.g. an assertion that loading state shows the glyph), update it to `"-"`. If no hits, skip.

Likely callsite (only update if grep found it):

```tsx
// Before
expect(screen.getByTestId("curator-tray-count")).toHaveTextContent("—");

// After
expect(screen.getByTestId("curator-tray-count")).toHaveTextContent("-");
```

- [ ] **Step 3: Run the curator-tray test in isolation**

```bash
cd frontend
npm test -- src/components/curator/CuratorTrayTrigger.test.tsx
cd ..
```

Expected: pass. If a test fails because it asserted on something else that hasn't changed, read the failure and triage — do not silence the test by deleting assertions.

---

## Task 6: Frontend Rule 3 sweep — sentence connectors

**Files:** every frontend file from the Task 1 inventory whose em-dash hits are NOT data placeholders (Rule 1) or loading glyphs (Rule 2). These are sentence-connector em-dashes in user-visible copy.

- [ ] **Step 1: Build the per-file work list**

```bash
grep -rn --include="*.tsx" --include="*.ts" -- "—" frontend/src \
  | grep -v "\.test\." \
  | grep -v -E "\?\? \"—\"" \
  | grep -v -E ": \"—\"" \
  | grep -v -E "^\s*\*"
```

Each remaining line is a candidate. Some will still be JSDoc that the regex didn't catch (e.g. multi-line JSDoc continuation lines without the leading `*`). Read each match in context (`Read` tool with offset to ±5 lines around the hit) before editing. Skip JSDoc and comment-block hits.

- [ ] **Step 2: For each user-visible hit, apply one of the three Rule-3 transforms**

The three transforms (from the spec):

**3a. Em-dash before a clarifying clause** → period + new sentence

```tsx
// Before
<h2>New secret — save it now</h2>

// After
<h2>New secret. Save it now.</h2>
```

**3b. Em-dash framing a parenthetical aside** → commas or parens

```tsx
// Before
<span>verify ok — userId={userId}</span>

// After
<span>verify ok (userId={userId})</span>
```

**3c. Em-dash for emphasis pause** → comma, semicolon, or restructure

```tsx
// Before
<p>Your payout is delayed — we're investigating. No action needed.</p>

// After
<p>Your payout is delayed. We're investigating; no action needed from you.</p>
```

The choice of transform is per-sentence judgment. If a sentence flows poorly after a mechanical substitution, restructure it. The goal is human-written prose, not a token-level regex.

Concrete examples that are likely in the inventory (from the brainstorm exploration):

```tsx
// frontend/src/app/admin/infrastructure/RotateSecretModal.tsx:13
// Before
<h2 className="text-sm font-semibold mb-2">New secret — save it now</h2>
// After
<h2 className="text-sm font-semibold mb-2">New secret. Save it now.</h2>

// frontend/src/app/admin/infrastructure/RotateSecretModal.tsx:30
// Before
{r.errorMessage && <span className="opacity-70"> — {r.errorMessage}</span>}
// After
{r.errorMessage && <span className="opacity-70">: {r.errorMessage}</span>}

// frontend/src/components/auction/AuctionEndedRow.tsx — wherever the user-visible
// "Auction ended — L$N,NNN" / "Ended — no winner" strings live (search for them)
// Before
return `Auction ended — L$${formatBid(bid)}`;
return `Ended — no winner`;
// After
return `Auction ended. L$${formatBid(bid)}`;
return `Ended (no winner)`;
```

Read the actual source for `AuctionEndedRow.tsx` before editing — the JSDoc references those strings, but the literals may be in a different format. Match the actual code.

- [ ] **Step 3: Update test assertions in lockstep**

For every production file edited in Step 2, check whether a sibling test file asserts on the changed string:

```bash
# Example: check test for RotateSecretModal
grep -n "New secret" frontend/src/app/admin/infrastructure/*.test.tsx 2>/dev/null
grep -n "New secret" frontend/src/components/admin/**/*.test.tsx 2>/dev/null
```

Update any assertion that no longer matches. Production change and test change land in the same commit (the sweep commit at Task 11).

- [ ] **Step 4: Re-run the grep — only comment-line hits should remain**

```bash
grep -rn --include="*.tsx" --include="*.ts" -- "—" frontend/src \
  | grep -v "\.test\." \
  | grep -v -E "^\s*\*" \
  | grep -v -E "^[^:]+:[0-9]+:\s*//"
```

Expected: empty output. Any remaining hit needs to be visually inspected — if it's a JSDoc continuation line that the regex missed, leave it; otherwise, fix.

- [ ] **Step 5: Run frontend type-check + lint + tests + verify**

```bash
cd frontend
npx tsc --noEmit
npm run lint
npm test
npm run verify
cd ..
```

Expected: all pass. If a test fails because an assertion still references a now-removed em-dash, update it (Step 3 should have caught this; if it didn't, fix and re-run).

---

## Task 7: Backend sweep — log strings, exception messages, notification bodies

**Files:** every backend file from the Task 1 inventory. Specific known callsites (from brainstorm exploration):

- `backend/src/main/java/com/slparcelauctions/backend/wallet/sl/WalletSlExceptionHandler.java:34`
- `backend/src/main/java/com/slparcelauctions/backend/admin/infrastructure/reconciliation/ReconciliationService.java:61`
- `backend/src/main/java/com/slparcelauctions/backend/admin/infrastructure/reconciliation/ReconciliationService.java:80`
- `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisherImpl.java:221`
- `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisherImpl.java:386`
- `backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowService.java:178`
- `backend/src/main/java/com/slparcelauctions/backend/auction/exception/NotVerifiedException.java:11` *(check whether this is Javadoc — if so, skip per scope)*

- [ ] **Step 1: Build the per-file work list**

```bash
grep -rn --include="*.java" -- "—" backend/src/main \
  | grep -v -E "^[^:]+:[0-9]+:\s*\*" \
  | grep -v -E "^[^:]+:[0-9]+:\s*//"
```

Each remaining line is a candidate string in `log.*`, exception ctor, or notification body.

- [ ] **Step 2: Apply Rule 3 transforms**

Java string examples:

```java
// backend/src/main/java/com/slparcelauctions/backend/wallet/sl/WalletSlExceptionHandler.java:34
// Before
log.warn("Wallet SL endpoint rejected: bad headers — {}", e.getMessage());
// After
log.warn("Wallet SL endpoint rejected: bad headers. {}", e.getMessage());

// backend/src/main/java/com/slparcelauctions/backend/admin/infrastructure/reconciliation/ReconciliationService.java:61
// Before
log.error("Reconciliation aborted: DENORM_DRIFT — "
        + "locked sum drifted from previous run");
// After
log.error("Reconciliation aborted: DENORM_DRIFT. "
        + "Locked sum drifted from previous run.");

// backend/src/main/java/com/slparcelauctions/backend/admin/infrastructure/reconciliation/ReconciliationService.java:80
// Before
"Balance data stale — terminal may be offline"
// After
"Balance data stale. Terminal may be offline."

// backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisherImpl.java:221
// Before
String body = "Your payout is delayed — we're investigating. No action needed from you.";
// After
String body = "Your payout is delayed. We're investigating; no action needed from you.";

// backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisherImpl.java:386
// Before
+ ". Escrow remains funded — please complete payment at the terminal."
// After
+ ". Escrow remains funded; please complete payment at the terminal."

// backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowService.java:178
// Before
log.error("BID-RESERVATION-AMOUNT-MISMATCH for auction {}: reservation=L${} != finalBid=L${} — freezing escrow",
        auctionId, reservationCents, finalBidCents);
// After
log.error("BID-RESERVATION-AMOUNT-MISMATCH for auction {}: reservation=L${} != finalBid=L${}. Freezing escrow.",
        auctionId, reservationCents, finalBidCents);
```

Read each callsite first; the line numbers in the spec / brainstorm may have shifted. If a hit turns out to be a Javadoc line (starts with ` * `), leave it — Javadoc is out of scope.

- [ ] **Step 3: Update backend test assertions in lockstep**

```bash
grep -rn --include="*Test.java" -- "—" backend/src/test
```

For every production string edited in Step 2, check whether a test asserts on it:

```bash
# Example: check tests that may match the WalletSlExceptionHandler log message
grep -rn "bad headers" backend/src/test
```

Update any matching assertion. Most backend tests don't assert on log message exact content (logs are observed via `LogbackTestAppender` if at all), so this list is usually short.

- [ ] **Step 4: Re-run the grep — only Javadoc/comment hits should remain**

```bash
grep -rn --include="*.java" -- "—" backend/src/main \
  | grep -v -E "^[^:]+:[0-9]+:\s*\*" \
  | grep -v -E "^[^:]+:[0-9]+:\s*//"
```

Expected: empty output. Any remaining hit gets visually inspected.

- [ ] **Step 5: Run the backend test suite**

```bash
cd backend
./mvnw test
cd ..
```

Expected: pass. If a test fails on a string-comparison assertion, fix the assertion in the same commit.

---

## Task 8: LSL sweep — chat output strings

**Files:** all `.lsl` files in `lsl-scripts/`. Known callsites (from brainstorm exploration):

- `lsl-scripts/verification-terminal/verification-terminal.lsl:172`
- `lsl-scripts/verification-terminal/verification-terminal.lsl:192`
- `lsl-scripts/sl-im-dispatcher/dispatcher.lsl:121`

- [ ] **Step 1: Build the per-file work list**

```bash
grep -rn --include="*.lsl" -- "—" lsl-scripts \
  | grep -v -E "^[^:]+:[0-9]+:\s*//"
```

LSL has no docstrings; comments are `//`. Filter out comment lines, treat the rest as candidates. Each hit is a `llSay` / `llRegionSayTo` / `llDialog` / `llInstantMessage` / `llOwnerSay` argument.

- [ ] **Step 2: Apply Rule 3 transforms**

Examples:

```lsl
// lsl-scripts/verification-terminal/verification-terminal.lsl:172
// Before
llOwnerSay("SLPA Verification Terminal: wrong grid — this script is mainland-only.");
// After
llOwnerSay("SLPA Verification Terminal: wrong grid. This script is mainland-only.");

// lsl-scripts/verification-terminal/verification-terminal.lsl:192
// Before
llOwnerSay("SLPA Verification Terminal: incomplete config — VERIFY_URL required");
// After
llOwnerSay("SLPA Verification Terminal: incomplete config. VERIFY_URL required.");

// lsl-scripts/sl-im-dispatcher/dispatcher.lsl:121
// Before
llOwnerSay("SL IM dispatcher: incomplete config — POLL_URL / CONFIRM_URL_BASE / SHARED_SECRET required");
// After
llOwnerSay("SL IM dispatcher: incomplete config. POLL_URL, CONFIRM_URL_BASE, and SHARED_SECRET are required.");
```

LSL has no test runner — visual diff review is the verification.

- [ ] **Step 3: Re-run the grep**

```bash
grep -rn --include="*.lsl" -- "—" lsl-scripts \
  | grep -v -E "^[^:]+:[0-9]+:\s*//"
```

Expected: empty output. The remaining `lsl-scripts/**/README.md` em-dash hits are out of scope per the spec — verify the grep is `--include="*.lsl"`, not Markdown, before fretting.

---

## Task 9: En-dash sweep across all surfaces

**Files:** any frontend / backend / LSL file containing `–` (en-dash). The brainstorm grep found candidates, but most en-dashes (if present) live in similar contexts as em-dashes — sentence connectors and date ranges.

- [ ] **Step 1: Run the en-dash grep across all in-scope surfaces**

```bash
echo "=== Frontend en-dash ==="
grep -rn --include="*.tsx" --include="*.ts" -- "–" frontend/src \
  | grep -v "\.test\." \
  | grep -v -E "^\s*\*" \
  | grep -v -E "^[^:]+:[0-9]+:\s*//"

echo "=== Backend en-dash ==="
grep -rn --include="*.java" -- "–" backend/src/main \
  | grep -v -E "^[^:]+:[0-9]+:\s*\*" \
  | grep -v -E "^[^:]+:[0-9]+:\s*//"

echo "=== LSL en-dash ==="
grep -rn --include="*.lsl" -- "–" lsl-scripts \
  | grep -v -E "^[^:]+:[0-9]+:\s*//"
```

- [ ] **Step 2: Triage and apply rules**

For each hit:
- If it's a date/range like `Mon–Fri` or `9am–5pm` — read the spec policy: "flagged for case-by-case review". If it's customer-facing copy, replace with `to` (e.g. `Mon to Fri`). If it's only displayed once or twice and the reading flow is fine with the hyphen-equivalent, swap to `-`. Don't spend judgment cycles agonizing.
- If it's a sentence connector — apply Rule 3 (period / parens / restructure).

- [ ] **Step 3: Update test assertions in lockstep**

```bash
grep -rn --include="*.test.tsx" --include="*.test.ts" -- "–" frontend/src
grep -rn --include="*Test.java" -- "–" backend/src/test
```

Update any assertion that asserts on a now-changed en-dash string.

- [ ] **Step 4: Re-run greps; expect empty in non-comment lines**

```bash
grep -rn --include="*.tsx" --include="*.ts" -- "–" frontend/src \
  | grep -v -E "^\s*\*" \
  | grep -v -E "^[^:]+:[0-9]+:\s*//"
grep -rn --include="*.java" -- "–" backend/src/main \
  | grep -v -E "^[^:]+:[0-9]+:\s*\*" \
  | grep -v -E "^[^:]+:[0-9]+:\s*//"
grep -rn --include="*.lsl" -- "–" lsl-scripts \
  | grep -v -E "^[^:]+:[0-9]+:\s*//"
```

---

## Task 10: Final verification

- [ ] **Step 1: Final grep sweep — em-dash + en-dash, all surfaces**

```bash
echo "=== Em-dash final check ==="
grep -rn --include="*.tsx" --include="*.ts" -- "—" frontend/src \
  | grep -v "\.test\." \
  | grep -v -E "^\s*\*" \
  | grep -v -E "^[^:]+:[0-9]+:\s*//"
grep -rn --include="*.java" -- "—" backend/src/main \
  | grep -v -E "^[^:]+:[0-9]+:\s*\*" \
  | grep -v -E "^[^:]+:[0-9]+:\s*//"
grep -rn --include="*.lsl" -- "—" lsl-scripts \
  | grep -v -E "^[^:]+:[0-9]+:\s*//"

echo "=== En-dash final check ==="
grep -rn --include="*.tsx" --include="*.ts" -- "–" frontend/src \
  | grep -v "\.test\." \
  | grep -v -E "^\s*\*" \
  | grep -v -E "^[^:]+:[0-9]+:\s*//"
grep -rn --include="*.java" -- "–" backend/src/main \
  | grep -v -E "^[^:]+:[0-9]+:\s*\*" \
  | grep -v -E "^[^:]+:[0-9]+:\s*//"
grep -rn --include="*.lsl" -- "–" lsl-scripts \
  | grep -v -E "^[^:]+:[0-9]+:\s*//"
```

Expected: every block returns empty output. If anything remains, visually inspect — comments/JSDoc/Javadoc that the regex missed are fine; user-visible strings are not, fix and re-run.

- [ ] **Step 2: Frontend full check**

```bash
cd frontend
npx tsc --noEmit
npm run lint
npm test
npm run verify
cd ..
```

Expected: all pass.

- [ ] **Step 3: Backend full check**

```bash
cd backend
./mvnw test
cd ..
```

Expected: pass.

- [ ] **Step 4: Confirm audit doc still untracked**

```bash
git status --short
```

Expected: working copy modifications across the swept files, but `.ignored-docs/` does NOT appear in the output (gitignored).

- [ ] **Step 5: Manual smoke (optional but recommended)**

If `docker compose up --build` is already running, browse to:
- `http://localhost:3000/admin/infrastructure` — confirm tables render with `"-"` placeholders, no layout shift
- `http://localhost:3000/auction/<any-active-public-id>` — confirm `AuctionEndedRow` text reads naturally

If the dev stack isn't running, skip this step and rely on test coverage. Backend log strings are not smokeable locally without exercising the relevant code paths; LSL cannot be smoked without an in-world deploy (out of MR scope).

---

## Task 11: Stage all sweep changes and commit

- [ ] **Step 1: Review the staged set with `git status` + `git diff`**

```bash
git status
git diff --stat
```

Expected: changes across many frontend `.tsx`/`.ts` files, several backend `.java` files, several `.lsl` files, and possibly a few test files. The `.gitignore` change should NOT appear (already committed in Task 3). The `.ignored-docs/` directory should NOT appear (gitignored).

- [ ] **Step 2: Stage every modified file individually (avoid `git add -A`)**

Per the project rules in CLAUDE.md, prefer adding files by name rather than `git add -A` to avoid accidentally staging untracked files. List the files explicitly:

```bash
# Use git status to enumerate the modified files, then stage them in
# logical chunks. Example shape (real list comes from git status):
git add frontend/src/app/admin/audit-log/AdminAuditLogTable.tsx
git add frontend/src/app/admin/disputes/AdminDisputesTable.tsx
git add frontend/src/app/admin/infrastructure/BotPoolSection.tsx
git add frontend/src/app/admin/infrastructure/ReconciliationSection.tsx
git add frontend/src/app/admin/infrastructure/RotateSecretModal.tsx
git add frontend/src/app/admin/infrastructure/TerminalsSection.tsx
git add frontend/src/app/admin/infrastructure/WithdrawalsHistorySection.tsx
git add frontend/src/components/curator/CuratorTrayTrigger.tsx
git add frontend/src/components/curator/CuratorTrayTrigger.test.tsx  # if changed
# ... continue for every actually modified file
```

A safer shortcut, given the audit doc is gitignored and won't be picked up:

```bash
# Stage all modifications to tracked files only (this is the meaning
# of `git add -u`, which excludes untracked files and respects gitignore)
git add -u
git status
```

Verify the resulting staged set in `git status` matches expectations before committing. The `.ignored-docs/` directory should still not appear.

- [ ] **Step 3: Commit with the spec-mandated message**

```bash
git commit -m "$(cat <<'EOF'
chore(language): remove em-dashes from user/admin-visible copy

Sweep replaces em-dashes (—) and en-dashes (–) with proper grammar
across customer- and admin-visible copy on three surfaces:

- Frontend (.tsx / .ts): JSX text content, string literals used as
  UI labels, aria-labels, toast/error/modal/heading/status copy.
  Test assertions referencing changed strings updated in lockstep.
- Backend (.java): log.* messages, exception detail strings,
  notification IM/email body literals.
- LSL: llSay / llRegionSayTo / llDialog / llInstantMessage /
  llOwnerSay arguments.

Three replacement rules (per spec):
  1. Data-placeholder glyphs `?? "—"` → `?? "-"`.
  2. Loading-state glyph in CuratorTrayTrigger → "-".
  3. Sentence connectors rewritten case-by-case (period / parens /
     restructure).

Out of scope: comments, JSDoc, Javadoc, LSL READMEs, stitch mockups,
"curator" terminology, AI-flagged copy rewrites (those live in a
local-only audit doc under .ignored-docs/).
EOF
)"
```

- [ ] **Step 4: Confirm both commits are on `task/language-cleanup`**

```bash
git log --oneline -3
git rev-parse --abbrev-ref HEAD
```

Expected:

```
<sha>  chore(language): remove em-dashes from user/admin-visible copy
<sha>  chore: gitignore .ignored-docs/
975974b docs(spec): tighten language-cleanup spec after self-review
```

Current branch: `task/language-cleanup`.

---

## Task 12: Push and open PR

- [ ] **Step 1: Push the feature branch to origin**

```bash
git push -u origin task/language-cleanup
```

- [ ] **Step 2: Open PR against `dev`**

```bash
gh pr create --base dev --head task/language-cleanup --title "chore(language): remove em-dashes from user/admin-visible copy" --body "$(cat <<'EOF'
## Summary
- Replace em-dashes (`—`) and en-dashes (`–`) with proper grammar across frontend, backend, and LSL user/admin-visible copy
- Empty-cell admin-table glyphs `?? "—"` → `?? "-"`; loading-state glyph in `CuratorTrayTrigger` → `"-"`
- Sentence-connector em-dashes rewritten case-by-case (period / parens / restructure)
- Adds `.ignored-docs/` to `.gitignore` (scratch space for local-only audit notes)

Curator terminology retained per brainstorm.

## Test plan
- [ ] `frontend/`: `npm run lint`, `npm test`, `npx tsc --noEmit`, `npm run verify`
- [ ] `backend/`: `./mvnw test`
- [ ] grep verification: no em-dashes or en-dashes remain in non-comment lines of `frontend/src`, `backend/src/main`, `lsl-scripts/**/*.lsl`
- [ ] Manual smoke: `/admin/infrastructure` renders tables with `"-"` placeholders; `/auction/{id}` `AuctionEndedRow` renders correctly

## Out of scope
- "Curator" terminology
- Code comments / JSDoc / Javadoc / LSL `//`
- LSL READMEs
- AI-flagged copy rewrites (lives in local-only `.ignored-docs/ai-wording-audit-2026-05-04.md` for follow-up triage)
EOF
)"
```

Capture the PR URL printed by `gh pr create` and report it back to the user.

- [ ] **Step 3: Verify the audit doc is local-only on the remote**

```bash
git ls-files | grep ignored-docs
```

Expected: empty output. The audit doc never gets tracked.

- [ ] **Step 4: CI status (if any runs on the feature branch)**

```bash
gh pr checks
```

Backend deploy workflow only triggers on `main` per CLAUDE.md, so this is typically a no-op on a `task/*` branch. If checks are configured to run on PRs targeting `dev`, wait for them.

---

## Out-of-band note for the human reviewer

After this plan executes:

1. Two commits land on `origin/dev`.
2. The `.ignored-docs/ai-wording-audit-2026-05-04.md` exists on the implementer's machine, gitignored.
3. The implementer should hand the audit doc to the user for triage. The user reviews the entries, decides which rewrites to ship, and either edits the production code directly or files follow-up tasks. None of this is in the current MR.

End of plan.
