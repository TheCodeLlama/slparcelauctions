# User Wallet Dormancy Design

Status: implemented 2026-05-19

## 1. Goal

Mirror the realty-group wallet dormancy state machine
(`docs/superpowers/specs/2026-05-11-realty-groups-group-wallet-design.md` §10)
on the user-wallet side. Users with a positive `balance_lindens` who go
inactive for the dormancy window get four weekly escalation notices,
then an auto-return of the unreserved balance to their verified SL
avatar via the `TerminalCommand{WITHDRAW}` pipeline.

The User entity already carries `wallet_dormancy_started_at` and
`wallet_dormancy_phase` columns; only `AdminWalletService.resetDormancy`
writes them today and there is no scheduled job. This spec adds the
weekly sweep, the per-user state-machine task, and the per-login reset
hook.

## 2. Inactivity signal

There is no `users.last_login_at` column. Per project convention
("ddl-auto: update + DB wipe, no Flyway migrations until we have
users") we reuse the same signal the group-side spec uses: the
most-recent `refresh_tokens.created_at` for the user. The user-side
query is simpler than the group-side query because there is no
many-to-many `realty_group_members` join.

```sql
SELECT u.id FROM users u
WHERE u.balance_lindens > 0
  AND u.wallet_dormancy_phase IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM refresh_tokens rt
      WHERE rt.user_id = u.id
        AND rt.created_at > now() - make_interval(days => :windowDays)
  );
```

A user with no refresh tokens at all (e.g. account never logged in
since registration; refresh-token sweeps deleted older rows) is
correctly flagged: the NOT EXISTS predicate is true on an empty
right-hand side.

Reserved L$ are **not** filtered out at this layer — a user can be
flagged dormant even with `reserved_lindens > 0` (active bids). The
auto-return at phase 4 only debits `balance_lindens - reserved_lindens`
so active bid reservations are preserved (§5).

## 3. Phase semantics

Identical to the group spec:

- **Phase 1 (flag).** Stamp `wallet_dormancy_started_at = now,
  wallet_dormancy_phase = 1`. `userWalletDormancyFlagged(userId, 1,
  balance)` notification.
- **Phases 2–4 (escalate).** Increment `wallet_dormancy_phase` by 1 on
  each weekly sweep; same notification with the new phase number.
- **Phase 4 → Phase 99 (auto-return).** Queue a
  `TerminalCommand{action=WITHDRAW, purpose=USER_WALLET_DORMANCY_AUTO_RETURN,
   recipientUuid=user.slAvatarUuid, amount=availableBalance,
   idempotencyKey=user-dormancy-{userId}-{startedAtEpochSeconds}}`.
  Append a `UserLedgerEntry{entry_type=DORMANCY_AUTO_RETURN,
  ref_type=TERMINAL_COMMAND, ref_id=cmd.id}` row. Decrement
  `balance_lindens` by `availableBalance` (leaving `reserved_lindens`
  intact). Stamp `wallet_dormancy_phase = 99`. Send
  `userWalletDormancyAutoReturned(userId, amount)`.

`reserved_lindens` is untouched: bid reservations hold L$ for live
auctions and must not be flushed by a dormancy sweep. If
`availableBalance == 0` (every L$ is reserved) the WITHDRAW is
skipped, the ledger row is skipped, and the user stays at phase 99
with the reserved balance intact. The (rare) case where every L$ is
reserved is logged at WARN.

## 4. Active-signal reset

Two reset paths, mirroring the group-side §10.4:

1. **Login / refresh-token rotation.** `AuthService.buildResult` and
   `AuthService.refresh` already call
   `clearGroupDormancyForUser(userId)`. They now also call
   `userWalletDormancyTask.clearForUser(userId)` before the group
   clear. Phase 99 (COMPLETED) is intentionally preserved — the
   auto-return already fired and reverting the phase would lose audit
   context.
2. **Admin reset.** `AdminWalletService.resetDormancy` is unchanged;
   it remains the manual-override path. The job's "newly-dormant"
   query already filters on `wallet_dormancy_phase IS NULL` so a user
   reset by an admin to NULL is re-eligible for flagging on the next
   sweep (matching the group-side behaviour).

## 5. Reserved-L$ handling at phase 4

```java
long balance       = user.getBalanceLindens();
long reserved      = user.getReservedLindens();
long autoReturnAmt = Math.max(0L, balance - reserved);
```

`autoReturnAmt` is what gets withdrawn and what shows up on the
ledger row. If `autoReturnAmt == 0`, the WITHDRAW is not queued, no
ledger row is appended, and the user lands at phase 99 with their
reserved balance intact. This matches the spec's "don't auto-return
reserved L$" out-of-scope item.

## 6. Idempotency

The terminal-command idempotency key is
`user-dormancy-{userId}-{startedAtEpochSeconds}`. Reusing the same
phase-1 stamp on every escalation keeps the key stable across the
multi-week cycle even though only phase 4 actually queues a command.
If a manual admin reset clears the phase and a new dormancy cycle
later re-flags the user, the new `wallet_dormancy_started_at`
generates a fresh key.

The user-ledger row carries no `idempotency_key` (the column is
nullable on `user_ledger` and the column is only used by client-
supplied frontend retry keys). The ledger row is logically
deduplicated by the WITHDRAW terminal-command's idempotency_key via
`ref_id = cmd.id`.

## 7. Notifications

- `userWalletDormancyFlagged(userId, phase, balance)` — phases 1–4,
  in-app notification only (SL IM body deferred to the Epic 09 dispatch
  refactor, matching the group-side stub).
- `userWalletDormancyAutoReturned(userId, amount)` — terminal phase-4
  IM, same stub policy.

Both methods land on `NotificationPublisher` as log-only stubs in
`NotificationPublisherImpl`, mirroring the group-side
`groupWalletDormancyFlagged` and `groupWalletDormancyAutoReturned`
methods.

## 8. Scheduling

`UserWalletDormancyJob` runs weekly. Default cron `0 0 4 * * MON` —
30 minutes before the group-side `0 30 4 * * MON` so the user dormancy
sweep completes before any group-member reset side effect runs (group
spec §10.2 calls out the offset). Configurable via
`slpa.wallet.dormancy-job.cron`, `slpa.wallet.dormancy.window-days`
(default 30), `slpa.wallet.dormancy.phase-duration-days` (default 7).

Each per-user step (`flag` / `escalateOrAutoReturn`) is its own
transaction so a failure on one user doesn't block the rest of the
sweep.

## 9. New enum values

- `TerminalCommandPurpose.USER_WALLET_DORMANCY_AUTO_RETURN` — discriminates
  the dormancy-driven withdraw from a user-initiated `WALLET_WITHDRAWAL`.
  The dispatcher dispatches both identically; the callback handler can
  be wired separately if dormancy-specific bookkeeping is ever needed.
- `UserLedgerEntryType.DORMANCY_AUTO_RETURN` — paired with a
  matching CHECK-constraint update (Flyway migration V40) and a
  registered `UserLedgerEntryTypeCheckConstraintInitializer`-style sync
  for future enum additions.

The `WITHDRAW` action is reused (same as group dormancy). The
`recipient_uuid` is the user's `slAvatarUuid` so the bot routes
through the standard avatar-pay path.

## 10. Out of scope

- Email dispatch (SL IM stub bodies are fine — Epic 09 will add real
  copy).
- Modifying `AdminWalletService.resetDormancy`.
- Reserved-L$ auto-return — explicitly preserved (§5).
- Cross-checking refresh-token rows against soft-deleted users — the
  reconciliation invariant assumes refresh tokens are pruned by the
  cleanup task on user deletion.

## 11. Files

New:
- `backend/src/main/java/com/slparcelauctions/backend/wallet/dormancy/UserWalletDormancyTask.java`
- `backend/src/main/java/com/slparcelauctions/backend/wallet/dormancy/UserWalletDormancyJob.java`
- `backend/src/main/resources/db/migration/V40__user_ledger_dormancy_auto_return.sql`
- `backend/src/test/java/com/slparcelauctions/backend/wallet/dormancy/UserWalletDormancyTaskTest.java`

Modified:
- `backend/src/main/java/com/slparcelauctions/backend/user/UserRepository.java`
  — adds `findEligibleForDormancyFlag(int windowDays)` and
  `findDormancyPhaseDue(int phaseDurationDays)` native queries.
- `backend/src/main/java/com/slparcelauctions/backend/auth/AuthService.java`
  — invokes `userWalletDormancyTask.clearForUser(userId)` in the
  same place `clearGroupDormancyForUser` runs.
- `backend/src/main/java/com/slparcelauctions/backend/escrow/command/TerminalCommandPurpose.java`
  — adds `USER_WALLET_DORMANCY_AUTO_RETURN`.
- `backend/src/main/java/com/slparcelauctions/backend/wallet/UserLedgerEntryType.java`
  — adds `DORMANCY_AUTO_RETURN`.
- `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisher.java`
  + `NotificationPublisherImpl.java` — adds the two stub methods.
- `backend/src/test/java/com/slparcelauctions/backend/auth/AuthServiceTest.java`
  — verifies the new dormancy-clear call on login + refresh.
