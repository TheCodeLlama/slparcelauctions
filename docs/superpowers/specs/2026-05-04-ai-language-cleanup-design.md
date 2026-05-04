# AI Language Cleanup — Design

**Date:** 2026-05-04
**Branch:** `dev`
**MR target:** `dev`

## Goal

Remove em-dashes (`—`) and en-dashes (`–`) from user/admin-visible copy
across the frontend, backend, and LSL surfaces, and produce a local-only
audit doc cataloguing other AI-tell wording (e.g. "delve", "leverage",
"robust", "seamless") for separate triage. Em-dashes ship as in-place
fixes in the same MR; the audit doc lives in `.ignored-docs/` (gitignored)
and never reaches the repo.

The "curator" terminology is **out of scope** — explicitly retained.

## Background

Em-dashes are a well-known LLM-writing tell. Many readers now treat any
em-dash as evidence the text was AI-generated, regardless of grammatical
role. This applies equally to sentence connectors ("payout delayed —
investigating"), parenthetical asides, and standalone empty-cell
placeholders (`{value ?? "—"}`). The policy across this codebase is:
em-dashes do not appear in any string a customer or admin reads. The same
logic applies to en-dashes used as connectors.

Other AI-tell wording — lexical patterns like "delve", "leverage" (as a
verb), "robust", "comprehensive", "Furthermore," — is documented in a
separate audit doc rather than swept in this MR. That audit feeds future
triage; not every flagged item must be rewritten.

## Scope

### In scope

| Surface | Files | What's swept |
|---|---|---|
| Frontend | `frontend/src/**/*.{tsx,ts}` | JSX text content, string literals used as UI labels, aria-labels, toast/error/modal/heading/status-description copy. Test assertions referencing changed strings updated in lockstep. |
| Backend Java | `backend/src/main/**/*.java` | `log.{info,warn,error}` message strings (admin reads in CloudWatch), exception detail strings (`new XException("...")`), notification IM/email body literals (e.g. inside `NotificationPublisherImpl.java`). |
| LSL | `lsl-scripts/**/*.lsl` | `llSay`, `llRegionSayTo`, `llDialog`, `llInstantMessage` (customer-facing in-world chat) **and** `llOwnerSay` (operator-facing). |

### Out of scope

- **"Curator" terminology** — retained throughout (component names,
  copy, identifiers).
- **Code comments / JSDoc / Javadoc** — dev-only surface.
- **LSL `README.md` files** — operator setup docs, dev/ops surface.
- **`docs/stitch_generated-design/**`** — generated mockup HTML, not
  live code.
- **AI-flagged copy rewrites** — the audit doc is the deliverable; any
  rewrites are deferred to user triage in follow-up MRs.
- **In-world LSL deploy** — LSL changes are source-only in this MR; no
  prim re-rezzing or notecard distribution is part of the change.

## Em-dash replacement rules

### Rule 1 — Empty-cell glyph

Pattern: `{value ?? "—"}` or `{value ? value : "—"}` in admin tables.

Replacement: `"—"` → `"-"` (ASCII hyphen). Mechanical one-character swap.
Approximately 12 callsites; ~9 are already swept in the
in-progress diff against `dev` (pre-existing user edits absorbed by this
MR).

Known stragglers in the user's in-progress diff that this sweep catches:

- `frontend/src/app/admin/infrastructure/TerminalsSection.tsx:51`
- `frontend/src/app/admin/infrastructure/TerminalsSection.tsx:55`
- `frontend/src/app/admin/infrastructure/WithdrawalsHistorySection.tsx:30`

### Rule 2 — Loading-state glyph

Pattern: a placeholder rendered while async data loads, e.g.
`frontend/src/components/curator/CuratorTrayTrigger.tsx:31`:

```tsx
const label = isLoading ? "—" : count >= 100 ? "99+" : String(count);
```

Replacement: `"—"` → `"-"`. Same character as the empty-cell rule;
loading and empty render identically, which is acceptable (both convey
"no value yet"). Rejected alternatives: `"…"` (ellipsis is also a
Unicode tell), `"..."` (visually busy in a count badge), `""` (may
collapse layout).

### Rule 3 — Sentence connector

The AI-prose case. Case-by-case rewrite, not a regex substitution.
Three common patterns and their transforms:

**3a. Em-dash before a clarifying clause** → period + new sentence
- `"New secret — save it now"` → `"New secret. Save it now."`
- `"wrong grid — this script is mainland-only"` → `"wrong grid. This script is mainland-only."`

**3b. Em-dash framing a parenthetical aside** → commas or parens
- `"verify ok — userId=abc"` → `"verify ok (userId=abc)"`
- `"Reconciliation aborted: DENORM_DRIFT — locked sum drifted"` → `"Reconciliation aborted: DENORM_DRIFT. Locked sum drifted."`

**3c. Em-dash for emphasis pause** → comma, semicolon, or restructure
- `"Your payout is delayed — we're investigating. No action needed."` → `"Your payout is delayed. We're investigating; no action needed from you."`

Rewrites preserve meaning and tone. If a sentence flows poorly without
the em-dash, restructure it rather than mechanically substitute.

### En-dash treatment

The same logic applies to en-dash (`–`) used as a sentence connector or
empty-cell glyph. The sweep catches en-dashes in the same surfaces.
Date/range en-dashes (e.g. `Mon–Fri`) would be flagged for case-by-case
review if they exist; a grep during implementation will confirm.

## AI-wording audit doc

### Location

`.ignored-docs/ai-wording-audit-2026-05-04.md` — single file, gitignored,
never reaches the MR.

### Patterns flagged (Bar B — "standard")

Lexical AI-tells in customer/admin-visible copy:

- "delve", "leverage" (as a verb), "robust", "seamless", "comprehensive"
- "ensure" (when overused), "navigate to", "facilitate", "harness"
- "empower", "elevate", "cutting-edge", "state-of-the-art"
- "Furthermore,", "Moreover,", "It's worth noting that...",
  "It's important to note that..."
- Tricolon abuse in marketing prose ("X, Y, and Z" patterns)
- Parallel "Whether you're X or Y..." constructions

### Format

Header (verbatim):

```markdown
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
```

Per-entry format (exact fields, no extras):

```markdown
### `<file>:<line>`

**Current:**
> <verbatim text>

**Suggested non-AI wording:**
> <proposed rewrite>

**Why flagged:** <one-line reason naming the pattern(s)>
```

Organized by surface (frontend → backend → LSL), within each section
grouped by file path, ordered by file:line.

No severity ratings, no priority flags. The user triages; not every
entry must ship.

## MR composition

**Commits (proposed: 2):**

1. `chore: gitignore .ignored-docs/`
   - Just the `.gitignore` line already added (currently uncommitted on
     `dev`).
   - Standalone so the "what enabled the audit-doc workflow" stays
     recoverable from history.

2. `chore(language): remove em-dashes from user/admin-visible copy`
   - All sweep changes across frontend, backend, LSL in one logical
     commit.
   - Test assertion updates folded in (when a test asserts on a string
     the sweep changed).
   - Commit body lists the surfaces touched and the replacement rules
     applied.

**Files in the MR:**

- `.gitignore` (commit 1)
- `frontend/src/**/*.tsx`, `frontend/src/**/*.ts`
- `frontend/src/**/*.test.tsx`, `frontend/src/**/*.test.ts` (only
  assertion lines that reference a changed string)
- `backend/src/main/java/**/*.java`
- `backend/src/test/java/**/*.java` (only assertion lines that reference
  a changed string)
- `lsl-scripts/**/*.lsl`

**Files NOT in the MR:**

- `.ignored-docs/ai-wording-audit-2026-05-04.md` — gitignored
- `docs/stitch_generated-design/**` — generated mockups
- `lsl-scripts/**/README.md` — out of scope per design
- All comments / JSDoc / Javadoc — out of scope

**PR title:** `chore(language): remove em-dashes from user/admin-visible copy`

**PR body:**

```markdown
## Summary
- Replace em-dashes (`—`) with proper grammar across frontend, backend,
  and LSL user/admin-visible copy
- Empty-cell admin-table glyphs `?? "—"` → `?? "-"`; loading-state glyph
  in `CuratorTrayTrigger` → `"-"`
- Sentence-connector em-dashes rewritten case-by-case (period / parens /
  restructure)
- Adds `.ignored-docs/` to `.gitignore` (scratch space for local-only
  audit notes)

## Test plan
- [ ] `frontend/`: `npm run lint`, `npm test`, `npx tsc --noEmit`,
      `npm run verify`
- [ ] `backend/`: `./mvnw test`
- [ ] grep verification: no em-dashes or en-dashes remain in non-comment
      lines of `frontend/src`, `backend/src/main`, `lsl-scripts/**/*.lsl`
- [ ] Manual smoke: `/admin/infrastructure` renders tables with `"-"`
      placeholders; `/auction/{id}` `AuctionEndedRow` renders correctly
```

## Testing approach

### Pre-sweep step — find test-assertion fallout

```bash
grep -rn --include="*.test.tsx" --include="*.test.ts" -- "—" frontend/src
grep -rn --include="*Test.java" -- "—" backend/src/test
```

Test files that assert on changed strings get updated in the same
commit as the production change.

### Test commands

| Surface | Command | Expected |
|---|---|---|
| Frontend lint | `cd frontend && npm run lint` | Pass |
| Frontend types | `cd frontend && npx tsc --noEmit` | Pass |
| Frontend tests | `cd frontend && npm test` | Pass |
| Frontend verify | `cd frontend && npm run verify` | Pass |
| Backend tests | `cd backend && ./mvnw test` | Pass |
| LSL | None | Visual diff review only |

### Post-sweep grep verification

```bash
# Should return only comment/JSDoc/Javadoc lines after the sweep
grep -rn --include="*.tsx" --include="*.ts" -- "—" frontend/src
grep -rn --include="*.java" -- "—" backend/src/main
grep -rn --include="*.lsl" -- "—" lsl-scripts

# Same for en-dash
grep -rn --include="*.tsx" --include="*.ts" --include="*.java" --include="*.lsl" -- "–" frontend/src backend/src/main lsl-scripts
```

Each remaining hit is inspected by hand to confirm it's a comment /
JSDoc / Javadoc and not a missed user-visible string. Any non-comment
hit is fixed before the PR opens.

### Manual smoke

- `/admin/infrastructure` — tables render with `"-"` placeholders,
  no layout shift
- `/auction/{any-active-id}` — `AuctionEndedRow` renders correctly
  post-rewrite
- Backend log strings: not smoked (CloudWatch only, low risk)
- LSL: not smoked in-world (source-only changes, no deploy)

### Rollback risk

Very low. Pure copy changes — no data, schema, or behavior impact.
Worst case: a rewrite reads awkwardly post-merge and gets a follow-up
PR to refine.

## Audit doc generation

Independent of the em-dash sweep — no ordering dependency. The audit
doc covers AI-tell patterns that don't include em-dashes (em-dashes are
fixed in-place in the MR), so the two passes are orthogonal.

Generation happens locally during PR work; the file is written to
`.ignored-docs/ai-wording-audit-2026-05-04.md` and is never staged.
