# Groups Namespace Migration + Public Directory — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Concurrent subagent dispatch is permitted when two tasks touch disjoint file sets (see `feedback_parallel_subagents_disjoint_files.md`).

**Goal:** Consolidate every realty-group surface under `/groups/*` (and admin under `/admin/groups/*`), ship a public Browse Groups directory page at `/groups`, and wire every navigation surface so no realty-group route requires URL typing.

**Architecture:** Frontend route migration (clean break, no redirects) backed by a new public `GET /api/v1/realty-groups` browse endpoint. The directory page renders the imported claude.ai/design `<GroupsPage cardLayout="cover" sidebar="left" />` template with its internal search/sort state externalized to props so backend pagination drives results. Every existing `/dashboard/groups`, `/realty/groups`, `/group`, `/admin/realty-groups` route is deleted; their behavior migrates verbatim to the corresponding `/groups/*` or `/admin/groups/*` location.

**Tech Stack:** Spring Boot 4 / Java 26 / JPA / native SQL / JUnit 5 / Mockito / AssertJ. Next.js 16.2.3 / React 19 / TypeScript 5 / Tailwind CSS 4 / TanStack Query / Vitest / MSW.

**Branch:** `feat/groups-namespace-migration` (already created off `dev`; spec committed at `df8483bd`; pushed).

---

## Plan structure

This plan splits into four part files for context manageability:

- [`-part1.md`](2026-05-13-groups-nav-and-browse-plan-part1.md) — Tasks 1–7: backend foundation (DTO + repo query + service + controller + reserved-slug validator + notification `linkUrl`).
- [`-part2.md`](2026-05-13-groups-nav-and-browse-plan-part2.md) — Tasks 8–13: frontend browse page (template migration + hook + URL-state wrapper + `/groups` page.tsx + tests).
- [`-part3.md`](2026-05-13-groups-nav-and-browse-plan-part3.md) — Tasks 14–22: `/groups/[slug]/*` route tree (layout + sub-nav + nine sub-pages migrated from old locations).
- [`-part4.md`](2026-05-13-groups-nav-and-browse-plan-part4.md) — Tasks 23–34: other migrated routes (`/groups/me`, `/groups/new`, `/groups/invitations/me`, `/admin/groups/*`), nav surface wiring, internal-reference sweep, old-route deletion, SL IM body update, Postman, final PR.

## Dependency ordering

```
Part 1 — Tasks 1-7 — backend complete first so the frontend has a real endpoint to hit.
       Tasks 1-2 chain (DTO before repo query).
       Task 3 (service) depends on 1-2.
       Task 4 (controller) depends on 3.
       Tasks 5, 6, 7 parallel-safe with 1-4 and with each other.

Part 2 — Tasks 8-13 — depend on Task 4 (endpoint exists).
       Task 8 (template move) parallel-safe with backend.
       Task 9 (fork template) depends on Task 8.
       Task 10 (hook) parallel-safe with Tasks 8-9.
       Tasks 11-12 (wrapper + page) depend on 9 + 10.
       Task 13 (tests) depends on 11-12.

Part 3 — Tasks 14-22 — depend on Part 1 (backend) only.
       Task 14 (layout + sub-nav) lands first.
       Tasks 15-22 (per-page migrations) are pairwise file-disjoint and parallel-safe.

Part 4 — Tasks 23-34 — depend on Parts 1-3.
       Tasks 23-27 (other routes + nav components) parallel-safe.
       Task 28 (admin dashboard card) parallel-safe.
       Task 29 (internal reference sweep) after all migrations.
       Task 30 (old-route deletion) after Task 29.
       Tasks 31-33 (SL IM, Postman, docs) parallel-safe with 29-30.
       Task 34 (final PR) is the terminus.
```

## Concurrent dispatch guidance

Within each part, tasks marked **[parallel-safe]** in their preamble can be dispatched in the same Agent batch. Tasks without that marker depend on prior tasks within the part and must run after them. The implementer subagent should be told via the prompt whether it's running in a concurrent batch and which files are off-limits.

## Working agreements

- **TDD:** every backend service / controller task starts with a failing test, then implementation. Frontend tasks pair Vitest + MSW.
- **After each task:** implementer subagent commits + self-reviews; controller dispatches spec-compliance review; then code-quality review. Loop until both reviews approve.
- **Memory durable conventions** to load alongside this plan:
  - `feedback_no_emojis.md` — no emojis in source or commits.
  - `feedback_no_claude_code_attribution.md` — no AI/Claude/Anthropic attribution in commits or PRs.
  - `feedback_push_before_review.md` — push commits before any review request.
  - `feedback_no_redundant_rewrites.md` — never re-Write a file from scratch; use Edit.
  - `feedback_parallel_subagents_disjoint_files.md` — parallel dispatch is OK when files don't overlap.
  - `feedback_no_merge_to_main.md` — PRs target `dev`; user reviews + merges to `main` themselves.
- **No emojis** in any source, test, or commit. Brainstorm mockups use text labels only.
- **No AI attribution** in commits or PRs.
- **Push commits before any review request.**
- **Commit prefixes:** `feat(groups):` / `refactor(groups):` / `fix(groups):` / `test(groups):` / `docs(groups):`.
- **After all tasks:** open PR into `dev` (NOT `main`). The dev → main PR requires explicit user authorization.

## Spec coverage map

| Spec section | Plan task(s) |
|---|---|
| §3 URL map | Tasks 7-8, 14-27 (every route's migration) |
| §4 Slug everywhere | Tasks 14-22 (slug→publicId resolution in `/groups/[slug]/*` layout); Tasks 24-25 (admin uses slug) |
| §5.1 Header "Groups" | Task 25 |
| §5.2 UserMenuDropdown | Task 26 |
| §5.3 MobileMenu | Task 27 |
| §5.4 Group sub-nav | Task 14 |
| §5.5 EditGroupAffordance | Task 29 (sweep) |
| §5.6 Dashboard "Your groups" | Task 28 |
| §5.7 Admin dashboard card | Task 28 |
| §5.8 Notification linkUrl | Task 6 |
| §5.9 SL IM body | Task 31 |
| §6.1 Browse endpoint | Tasks 1-4 |
| §6.2 Recipient invitations endpoint | Task 23 (verify) |
| §6.3 Reserved-slug validator | Task 5 |
| §6.4 Notification + SL IM | Tasks 6, 31 |
| §7 Frontend integration | Tasks 8-12, 14-22 |
| §8 Migration sequence | Plan task order |
| §9 Testing | Embedded in every task (TDD) + Task 13 + Task 33 |
| §10 Acceptance | Task 34 PR body checklist |

## Task inventory

### Part 1 — Backend foundation
- Task 1: `RealtyGroupCardDto` record + `GroupsSortKey` enum
- Task 2: Native-SQL projection + `browseCards` repo method
- Task 3: `RealtyGroupBrowseService` (projection→DTO mapping + tagline truncation)
- Task 4: `GET /api/v1/realty-groups` controller endpoint
- Task 5: Reserved-slug validator (rejects `new`, `me`, `invitations`)
- Task 6: Invitation notification `linkUrl` population
- Task 7: V31 Flyway migration if `realty_groups.member_count` denorm column is missing (verify; conditional)

### Part 2 — Frontend browse page
- Task 8: Move template files from `docs/realty-groups/` to `frontend/src/components/realty/browse/`
- Task 9: Fork `GroupsPage` to externalize search/sort state to props
- Task 10: New `useBrowseGroups` TanStack Query hook
- Task 11: New `GroupsBrowseClient` URL-state wrapper component
- Task 12: New `frontend/src/app/groups/page.tsx` server-component page
- Task 13: Vitest integration test for the browse flow

### Part 3 — `/groups/[slug]/*` route tree
- Task 14: `frontend/src/app/groups/[slug]/layout.tsx` with persistent sub-nav + non-member redirect
- Task 15: `/groups/[slug]/page.tsx` (public profile; replaces `/group/[slug]`)
- Task 16: `/groups/[slug]/profile/page.tsx` (former Profile tab)
- Task 17: `/groups/[slug]/members/page.tsx` (former Members tab)
- Task 18: `/groups/[slug]/invitations/page.tsx` (former Invitations tab, group-scoped sender view)
- Task 19: `/groups/[slug]/settings/page.tsx` (former Settings tab)
- Task 20: `/groups/[slug]/wallet/page.tsx` (from `/realty/groups/[publicId]/wallet`)
- Task 21: `/groups/[slug]/sl-groups/page.tsx` + `/groups/[slug]/analytics/commissions/page.tsx` (moved)
- Task 22: `/groups/[slug]/reviews/page.tsx` (moved from `/realty/groups/[publicId]/reviews`)

### Part 4 — Other routes + nav + sweeps + PR
- Task 23: `/groups/me/page.tsx` + `/groups/new/page.tsx` + `/groups/invitations/me/page.tsx`
- Task 24: `/admin/groups/*` route tree (4 pages)
- Task 25: Header "Groups" link
- Task 26: UserMenuDropdown items ("My groups", "Invitations" + badge)
- Task 27: MobileMenu items
- Task 28: Dashboard overview "Your groups" section + admin dashboard "Groups" card
- Task 29: Internal reference sweep (every hardcoded link target)
- Task 30: Delete old route directories
- Task 31: SL IM dispatcher template update for invitation notification
- Task 32: Postman docs update (`docs/postman/realty-groups-g-additions.md`)
- Task 33: `next build` + full backend/frontend test pass + grep audit
- Task 34: Final PR into `dev` per spec §10

Total: 34 tasks. Expected wall-clock with concurrent dispatch: bounded by the Part 1 backend chain (Tasks 1→2→3→4) and the Part 3 sub-page dependencies on the layout (Task 14).

## Branch hygiene

- Branch: `feat/groups-namespace-migration` already exists and is pushed.
- After Part 1 lands (backend), push so reviews can see endpoint commits separately from frontend churn.
- Final PR opens via Task 34 with the body checklist from spec §10.
