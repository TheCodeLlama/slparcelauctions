# Realty Groups: F — Admin Moderation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Concurrent subagent dispatch is permitted when two tasks touch disjoint file sets (see `feedback_parallel_subagents_disjoint_files.md`).

**Goal:** Deliver the admin moderation toolkit for realty groups + close E-deferred SL-group-admin items + fix the parser bug + remove the unused About-text verification path. See spec: `docs/superpowers/specs/2026-05-12-realty-groups-admin-moderation-design.md`.

**Architecture:** Five vertical slices (group moderation entity, bulk listing suspend with 48 h auto-cancel, group fraud flag extension, group reports, SL-group admin & E cleanup), plus an audit filter extension and a reputation/rating display.

**Tech Stack:** Spring Boot 4 / Java 26 / Lombok / JPA / Flyway / PostgreSQL / JUnit 5 / Mockito / AssertJ. Next.js 16.2.3 / React 19 / TypeScript 5 / Tailwind CSS 4 / Vitest / MSW / TanStack Query.

**Branch:** `feat/realty-groups-admin-moderation` (already created off `dev`; spec committed at `3ca8954c`).

---

## Plan structure

This plan splits into three part files for context manageability:

- [`-part1.md`](2026-05-12-realty-groups-admin-moderation-part1.md) — Tasks 1–14: foundation (V28 migration, entities, repos, properties, enums) + Slice 1 (group moderation entity, guard, scheduled expiry) + Slice 2 (bulk listing suspend service + expiry task + cancellation extension).
- [`-part2.md`](2026-05-12-realty-groups-admin-moderation-part2.md) — Tasks 15–28: Slice 3 (fraud flag extension), Slice 4 (reports against groups + admin queue + reporter visibility), Slice 5a (SL group admin: parser fix, About-text removal, reverify service & task, force-unregister, admin endpoints).
- [`-part3.md`](2026-05-12-realty-groups-admin-moderation-part3.md) — Tasks 29–45: Slice 5b (bulk commission edit + analytics), reputation/rating display, audit filter, frontend (admin tabs, modals, public report modal, status pill, rating badge), polish (README, DEFERRED_WORK, FOOTGUNS, Postman), final PR.

## Dependency ordering

```
Part 1 Task 1 (V28 migration)
       │
       └─> Task 2 (Entities) ─> Task 3 (Repos) ─> Task 4 (Properties)
                                                  ─> Task 5 (Enum additions)

Tasks 6–14 (Slice 1 + Slice 2) — depend on 1–5; mostly disjoint between slices.

Part 2 Tasks 15–28 — depend on 1–5; slices are file-disjoint and can be implemented concurrently.

Part 3 Tasks 29–45 — depend on 1–28; frontend tasks (35–41) are pairwise disjoint.
```

## Concurrent dispatch guidance

Within each part, tasks marked with **[parallel-safe]** in their preamble can be dispatched in the same Agent batch. Tasks without that marker depend on prior tasks within the part and must run after them. The implementer subagent should be told via the prompt whether it's running in a concurrent batch and which files are off-limits for that task.

## Working agreements

- TDD: every backend service / controller task starts with a failing test, then implementation.
- After each task: implementer subagent commits + self-reviews; then spec-compliance review; then code-quality review. Loop until both reviews approve.
- The `slpa` memory in `~/.claude/projects/.../memory/` carries durable conventions — see especially `feedback_no_emojis.md`, `feedback_ledgers_immutable.md`, `feedback_no_claude_code_attribution.md`, `feedback_no_pause_active_auctions.md` (and its admin-moderation carve-out).
- No emojis in any source or commit; brainstorm mockups use text labels only.
- No AI/Claude/Anthropic attribution in commits or PRs.
- Push commits before any review request (memory: `feedback_push_before_review.md`).
- After all tasks: open PR into `dev` (NOT `main` — `feedback_no_merge_to_main.md`). The dev → main PR requires explicit user authorization.
