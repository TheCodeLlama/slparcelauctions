# Realty Groups — Sub-project D — Group Wallet Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a balance-bearing wallet to each realty group, distributing agent fees on auction completion, debiting listing fees from the group wallet on group-listed auctions, supporting leader-initiated withdraws to the leader's SL avatar, and tightening the dissolution gate.

**Architecture:** New `realty/wallet/` package mirrors `wallet/`. Wallet columns live on `realty_groups`; ledger is a separate `realty_group_ledger` table. Agent-fee L$ stays in SLPA by reducing `escrow.payoutAmt` at escrow creation; wallet credits land in the `handleEscrowPayoutSuccess` callback. Listing-fee refunds route by looking up the originating ledger row.

**Tech Stack:** Spring Boot 4 / Java 26 / Lombok / JPA / Flyway / PostgreSQL / JUnit 5 / Mockito / AssertJ. Frontend: Next.js 16 / React 19 / TypeScript 5 / Tailwind CSS 4 / Vitest / MSW / TanStack Query.

**Branch:** `feat/realty-groups-group-wallet` (already branched from `dev` and pushed; spec committed at `8cb22044`).

---

## Spec Reference

The spec is at `docs/superpowers/specs/2026-05-11-realty-groups-group-wallet-design.md`. Each task below names the spec sections it implements.

## Conventions Refresh (read before starting)

- **`BaseEntity` / `BaseMutableEntity`:** every entity extends one of these. `Long id` is internal; `UUID publicId` is the public identifier. Use `@SuperBuilder`, never `@Builder`, on subclasses. Don't redeclare `id`, `publicId`, `createdAt`, `updatedAt`, `version`. Don't override `equals`/`hashCode`.
- **Migrations:** `application.yml` runs Flyway; entity changes need a paired migration. The next number is `V26`.
- **Lock ordering:** wallet writes always acquire `findByIdForUpdate(...)` (PESSIMISTIC_WRITE) before mutating balances.
- **No emojis** anywhere. No AI/tool attribution in commits.
- **Test-first:** every code task starts with the failing test, runs it red, then implements.
- **Commit cadence:** one commit per task, conventional-commit format (`feat(realty-wallet): ...`).
- **Push** after every commit so the branch on GitHub stays current.

---

## File Structure

### Backend — new files

```
backend/src/main/resources/db/migration/
  V26__realty_group_wallet.sql

backend/src/main/java/com/slparcelauctions/backend/realty/wallet/
  RealtyGroupLedgerEntry.java              # entity, extends BaseEntity
  RealtyGroupLedgerEntryType.java          # enum
  RealtyGroupLedgerRepository.java
  RealtyGroupWalletService.java            # debit/credit/withdraw primitives + endpoint entrypoints
  RealtyGroupWalletController.java         # GET wallet, GET ledger, POST withdraw
  GroupWalletWithdrawalCallbackHandler.java  # success/stall handler
  broadcast/
    GroupWalletBalanceChangedEnvelope.java
    GroupWalletBroadcastPublisher.java
  dormancy/
    GroupWalletDormancyTask.java           # per-group state transitions
    GroupWalletDormancyJob.java            # @Scheduled weekly sweep
  dto/
    GroupWalletDto.java
    GroupLedgerEntryDto.java
    LedgerActorDto.java
    GroupWithdrawRequest.java
    GroupWithdrawResponse.java
    GroupWalletDtoMapper.java
  exception/
    InsufficientGroupBalanceException.java
    LeaderTermsNotAcceptedException.java
    LeaderFrozenException.java
    GroupHasNonzeroBalanceException.java
    GroupHasInFlightEscrowsException.java

backend/src/main/java/com/slparcelauctions/backend/auction/agentfee/
  AgentFeeDistributor.java                 # MANDATORY-tx wallet-credit dispatcher
```

### Backend — modified files

```
backend/src/main/java/com/slparcelauctions/backend/realty/RealtyGroup.java
  + balance_lindens, reserved_lindens, wallet_dormancy_started_at, wallet_dormancy_phase

backend/src/main/java/com/slparcelauctions/backend/realty/RealtyGroupRepository.java
  + findEligibleForDormancyFlag, findDormancyPhaseDue, findByIdForUpdate

backend/src/main/java/com/slparcelauctions/backend/realty/permission/RealtyGroupPermission.java
  + SPEND_FROM_GROUP_WALLET, WITHDRAW_FROM_GROUP_WALLET, VIEW_GROUP_TRANSACTIONS

backend/src/main/java/com/slparcelauctions/backend/realty/service/RealtyGroupService.java
  + dissolve() gate extension (balance + in-flight escrows)

backend/src/main/java/com/slparcelauctions/backend/realty/exception/RealtyExceptionHandler.java
  + new exception mappings

backend/src/main/java/com/slparcelauctions/backend/escrow/command/TerminalCommand.java
backend/src/main/java/com/slparcelauctions/backend/escrow/command/TerminalCommandPurpose.java
backend/src/main/java/com/slparcelauctions/backend/escrow/command/TerminalCommandService.java
  + realtyGroupId column, GROUP_WALLET_WITHDRAWAL purpose, callback routing

backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowService.java
  + agent_fee_amt subtraction in payoutAmt

backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowRepository.java
  + existsInFlightForGroup

backend/src/main/java/com/slparcelauctions/backend/wallet/WalletService.java
  + creditAgentFee primitive

backend/src/main/java/com/slparcelauctions/backend/wallet/UserLedgerEntryType.java
  + AGENT_FEE_CREDIT

backend/src/main/java/com/slparcelauctions/backend/wallet/me/MeWalletController.java
  + branch on auction.realty_group_id in payListingFee

backend/src/main/java/com/slparcelauctions/backend/escrow/scheduler/ListingFeeRefundProcessorTask.java
  + route by originating ledger row

backend/src/main/java/com/slparcelauctions/backend/auth/JwtAuthFilter.java   # OR LoginService — wherever refresh-token rotation happens
  + clear group dormancy on member login
```

### Frontend — new files

```
frontend/src/types/realty.ts                        # extend with GroupWallet, GroupLedgerEntry
frontend/src/lib/api/realtyGroupWallet.ts
frontend/src/hooks/realty/useGroupWallet.ts
frontend/src/hooks/realty/useGroupLedger.ts
frontend/src/app/realty/groups/[publicId]/wallet/page.tsx
frontend/src/components/realty/wallet/GroupWalletPage.tsx
frontend/src/components/realty/wallet/GroupWalletBalanceCard.tsx
frontend/src/components/realty/wallet/GroupWalletLedgerTable.tsx
frontend/src/components/realty/wallet/GroupWithdrawModal.tsx
frontend/src/components/realty/wallet/LeaderTermsBlockBanner.tsx
```

### Frontend — modified files

```
frontend/src/components/listing/AgentFeePreview.tsx   # add wallet-source line
frontend/src/test/msw/handlers.ts                      # add default wallet/ledger handlers
```

---

The plan below decomposes into **30 tasks** in dependency order. I'm writing them out in batches because they don't all fit in a single message.

Continuation files:

- **Part 1 — Tasks 1–10** (schema, entities, permissions, wallet primitives): see `2026-05-11-realty-groups-group-wallet-part1.md`
- **Part 2 — Tasks 11–20** (agent-fee distribution, listing-fee routing, withdraw flow, dissolution): see `2026-05-11-realty-groups-group-wallet-part2.md`
- **Part 3 — Tasks 21–30** (dormancy, HTTP surface, frontend, finalisation): see `2026-05-11-realty-groups-group-wallet-part3.md`

The implementer should read Part 1 → Part 2 → Part 3 in order. Each part lists its tasks with full TDD steps, exact file paths, and code blocks.

---

## Self-Review Note for the Implementer

After finishing all 30 tasks, before opening the PR:

1. **Spec coverage sweep:** open `docs/superpowers/specs/2026-05-11-realty-groups-group-wallet-design.md` and verify each section has been implemented. The §15 deferral table should match `docs/implementation/DEFERRED_WORK.md`.
2. **Backend test run:** `cd backend && ./mvnw test` — all green.
3. **Frontend test run:** `cd frontend && npm test && npm run verify` — all green.
4. **README sweep:** root `README.md` gets a new "Realty groups — group wallet" subsection in the realty-groups area, mirroring how C added one.
5. **DEFERRED_WORK update:** add D-deferred items (Postman if not done, user-side WalletDormancyJob if still absent, etc.).
6. **Final commit + push.**
