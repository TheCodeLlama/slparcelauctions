# Realty Groups: G — Final Cleanup Pass — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Concurrent subagent dispatch is permitted when two tasks touch disjoint file sets (see `feedback_parallel_subagents_disjoint_files.md`).

**Goal:** Close every realty-groups deferred item — C-era code & schema removal, deferred admin wallet ops + SL-group withdraw, escrow case-3 zero-payout fix, F polish items, and the in-scope OOS items pulled into G (group report-threshold fan-out, dedicated reviews page, SL-group UUID ban-evasion gate, `AdminActionType` label map, drop `SPEND_FROM_GROUP_WALLET`). Single PR into `dev`.

**Architecture:** Four vertical slices land in commit order — code removal → schema drop (V29) → new features & polish → docs sweep. Spec at `docs/superpowers/specs/2026-05-12-realty-groups-final-cleanup-design.md`.

**Tech Stack:** Spring Boot 4 / Java 26 / Lombok / JPA / Flyway / PostgreSQL / JUnit 5 / Mockito / AssertJ. Next.js 16.2.3 / React 19 / TypeScript 5 / Tailwind CSS 4 / Vitest / MSW / TanStack Query. LibreMetaverse / .NET 8 (bot). LSL (in-world).

**Branch:** `feat/realty-groups-final-cleanup` (already created off `dev`; spec committed at `1a6cbae7`; pushed).

---

## Plan structure

This plan splits into four part files for context manageability:

- [`-part1.md`](2026-05-12-realty-groups-final-cleanup-part1.md) — Tasks 1–10: V29 Flyway migration, enum additions, Section A C-era code removal, V29 smoke test.
- [`-part2.md`](2026-05-12-realty-groups-final-cleanup-part2.md) — Tasks 11–21: Section C DTO/N+1 fixes, Section D admin wallet adjust + SL-group withdraw + leader-terms banner (backend + bot + frontend).
- [`-part3.md`](2026-05-12-realty-groups-final-cleanup-part3.md) — Tasks 22–27: Section E case-3 escrow + LSL + notification + spec sync; Section F polish (SystemUserResolver, reverify-batch-size, @Transactional); Section B frontend cleanup.
- [`-part4.md`](2026-05-12-realty-groups-final-cleanup-part4.md) — Tasks 28–34: pulled-in items (report-threshold fan-out, reviews page, reverse-search, label map), Postman additions, docs sweep, final PR into dev.

## Dependency ordering

```
Part 1 — Task 1 (V29) ─┬─> Tasks 2-9 (enums + code removal, parallel-safe)
                       └─> Task 10 (V29 smoke test) [after Tasks 2-9]

Part 2 — depends on Tasks 1 + 8 (V29 + new enum values).
         Task 11 (mapper N+1) parallel-safe with Task 12 (ListingEligibleGroupDto).
         Task 18 (GroupWalletDto.leaderTermsAcceptedAt) lands BEFORE Tasks 13-14 —
         the admin wallet service returns `GroupWalletDto` and the constructor
         shape must already include the new field.
         Tasks 13-14 (admin wallet) chain; Task 14 depends on Task 13.
         Tasks 15-17 (SL-group withdraw) chain.
         Tasks 19-21 (frontend) parallel-safe with each other once backend DTOs exist.

Part 3 — Tasks 22-25 (Section E) parallel-safe pairwise.
         Task 26 (F polish) parallel-safe with Tasks 22-25.
         Task 27 (frontend cleanup) parallel-safe.

Part 4 — Tasks 28-31 (new features) parallel-safe with each other.
         Task 32 (Postman) depends on all new endpoints landing first.
         Task 33 (docs sweep) depends on everything except 34.
         Task 34 (final PR) is the terminus.
```

## Concurrent dispatch guidance

Tasks marked **[parallel-safe]** in their preamble can be dispatched in the same Agent batch when their file sets are disjoint. Tasks without that marker depend on prior tasks within the part and must run after them. The implementer subagent should be told via the prompt whether it's running in a concurrent batch and which files are off-limits for that task.

## Working agreements

- **TDD:** every backend service / controller task starts with a failing test, then implementation. Bot tasks use the existing `IBotSession` fake pattern. Frontend tasks pair Vitest + MSW.
- **After each task:** implementer subagent commits + self-reviews; controller dispatches spec-compliance review; then code-quality review. Loop until both reviews approve.
- **Memory durable conventions** to load alongside this plan:
  - `feedback_no_emojis.md` — no emojis in source or commits.
  - `feedback_no_claude_code_attribution.md` — no AI/Claude/Anthropic attribution in commits or PRs.
  - `feedback_push_before_review.md` — push commits before requesting any review.
  - `feedback_ledgers_immutable.md` — append-only ledger rows; never mutate.
  - `feedback_no_redundant_rewrites.md` — never re-Write a file from scratch; use Edit.
  - `feedback_centralized_fix_directives.md` — "X should already do Y" = fix at the centralized point in this task.
  - `feedback_no_pause_active_auctions.md` — auctions are a one-way race; the moderation freeze carve-out is the only exception.
  - `feedback_parallel_subagents_disjoint_files.md` — parallel dispatch is OK when files don't overlap.
- **No emojis** in any source or commit; brainstorm mockups use text labels only.
- **No AI attribution** in commits or PRs.
- **Push commits before any review request.**
- **After all tasks:** open PR into `dev` (NOT `main` — `feedback_no_merge_to_main.md`). The dev → main PR requires explicit user authorization.

## Spec coverage map

| Spec section | Plan task(s) |
|---|---|
| §4 (Section A: C-era code & schema) | Tasks 1, 3, 4, 5, 6, 7, 8, 10 |
| §5 (Section B: frontend cleanup) | Task 27 |
| §6 (Section C: DTO/N+1) | Tasks 11, 12 |
| §7 (Section D: admin wallet + SL-group withdraw + leader-terms) | Tasks 2, 13, 14, 15, 16, 17, 18, 19, 20, 21 |
| §8 (Section E: case-3 escrow + LSL + notification + spec sync) | Tasks 22, 23, 24, 25 |
| §9 (Section F: polish) | Task 26 |
| §10 (Section G: Postman) | Task 32 |
| §11 (drop SPEND_FROM_GROUP_WALLET) | Task 9 |
| §12 (report-threshold fan-out) | Task 28 |
| §13 (reviews page) | Task 29 |
| §14 (reverse-search hard block) | Task 30 |
| §15 (AdminActionType label map) | Task 31 |
| §16 (PR shape / commit ordering) | Tasks 1, 10, 33, 34 |
| §17 (migration concerns) | Task 1, Task 10 |
| §18 (out of scope) | Task 33 (DEFERRED_WORK sweep) |
| §19 (top-level acceptance) | Task 34 (PR body checklist) |

## Task inventory

### Part 1 — Foundation + Section A
- Task 1: V29 Flyway migration (column drops + CHECK widen + threshold column + SPEND scrub)
- Task 2: New enum values — `RealtyGroupLedgerEntryType.ADMIN_ADJUSTMENT`, `AdminActionType.REALTY_GROUP_WALLET_ADMIN_ADJUSTMENT`, `TerminalCommandAction.WITHDRAW_GROUP`
- Task 3: Delete `AgentFeeDistributor` + tests
- Task 4: Strip case-1 snapshot from `RealtyGroupListingService`
- Task 5: Strip `RealtyGroupMembershipService.reassignListingAgentForCase1`
- Task 6: Strip `RealtyGroupService` fee branches + `UpdateRealtyGroupRequest.agentFeeRate/Split`
- Task 7: Drop `agentFeeRate` field from `RealtyGroup` entity
- Task 8: Drop `agentFeeRate` from `RealtyGroupPublicDto` + frontend type
- Task 9: Drop `RealtyGroupPermission.SPEND_FROM_GROUP_WALLET` + fixture sweep
- Task 10: Local apply V29 + smoke test

### Part 2 — Section C + Section D
- Task 11: `AuctionDtoMapper` `MapperBatchContext` + three N+1 fixes
- Task 12: `ListingEligibleGroupDto` `agentCommissionRate` swap
- Task 13: `AdminRealtyGroupWalletService` — adjust core
- Task 14: `AdminRealtyGroupWalletController` + DTOs + exception handler
- Task 15: `GroupWithdrawRequest` extension + `GroupWithdrawRecipient` enum
- Task 16: `RealtyGroupWalletService.withdraw` SL_GROUP branch + new repo query + new exceptions
- Task 17: Bot `WithdrawGroupHandler` (LibreMetaverse `GiveGroupMoney`)
- Task 18: `GroupWalletDto.leaderTermsAcceptedAt` + mapper extension
- Task 19: Frontend admin wallet adjust modal
- Task 20: Frontend withdraw modal recipient picker
- Task 21: Frontend `LeaderTermsBlockBanner` condition flip

### Part 3 — Section E + Section F + Section B
- Task 22: `TerminalCommandService.queuePayout` zero-payout early-return + `runZeroPayoutSuccessInline` + `EscrowPayoutService` caller fix
- Task 23: LSL `slpa-terminal.lsl` graceful $0 PAYOUT handler + README update
- Task 24: Seller payout notification body — case-3 tweak
- Task 25: Spec §9.6 sync in E design doc
- Task 26: F polish — `SystemUserResolver` field removal + `reverify-batch-size` property + `@Transactional` audit/move + CONVENTIONS.md
- Task 27: Section B frontend cleanup — `AgentFeePreview` replacement + group profile page block removal

### Part 4 — Pulled-in items + Postman + docs + PR
- Task 28: Group report-threshold notification fan-out (`NotificationCategory`, property, fan-out logic + cycle reset)
- Task 29: Dedicated group reviews page (backend endpoint + service + DTO + frontend page + `GroupRatingBadge` link update)
- Task 30: SL group UUID reverse-search hard block at registration (repository query + service gate + exception + handler + frontend translation)
- Task 31: Frontend `AdminActionType` label map + audit-log row consumer
- Task 32: Postman collection additions (List as Group, Dissolve gate, Wallet folders, founder-terminal verify header fix)
- Task 33: DEFERRED_WORK.md sweep + README.md updates + FOOTGUNS.md carry-forwards
- Task 34: Final PR into `dev`

Total: 34 tasks. Expected wall-clock with concurrent dispatch: bounded by Part 1 sequencing (V29 before everything else) and Part 4's Task 32 (Postman after every endpoint exists).

## Branch hygiene

- Branch: `feat/realty-groups-final-cleanup` already exists and is pushed.
- After Tasks 1–9 land in Part 1, push the branch so reviews can see the schema and code-removal commits as separate entries.
- Final PR opens via Task 34 with the body checklist from spec §16 (LSL deploy step, Postman additions, etc.).
