# Admin User Wallet Ops — Design

**Date:** 2026-05-05
**Status:** approved (autonomous implementation authorized)
**Predecessor:** `docs/superpowers/specs/2026-04-30-wallet-model-design.md`

## 1. Goal

Bring the admin-facing wallet surface to pre-launch parity with the user-side wallet. Today admins can request payouts from the SLParcels service-avatar pool but cannot inspect or operate on any specific user's wallet. This design adds the missing per-user surface: read-only inspection, manual ledger adjustments, freeze/unfreeze, penalty forgiveness, dormancy reset, terms re-prompt, and force-complete / force-fail of stuck pending withdrawals.

Out of scope: bulk operations, two-admin sign-off, adjustment caps. Single solo admin context.

## 2. UX

A new **Wallet tab** on the existing admin user detail page (`/admin/users/{publicId}`), a 7th tab next to Listings / Bids / Cancellations / Reports / Fraud Flags / Moderation, plus a compact panel in the existing right-side `UserActionsRail`.

### 2.1 Wallet tab layout (top-to-bottom)

- **State header:** balance, reserved, available, penalty owed, dormancy phase, frozen badge, accepted-terms version. Each tile is read-only.
- **Pending withdrawals list:** rows from `withdrawals WHERE user_id=? AND status='PENDING' ORDER BY created_at`. Each row shows amount, requested-at, recipient avatar UUID, claim status (idle / "claimed by bot N min ago, lease expires HH:MM"), and two action buttons — `Force complete` and `Force fail`. Both disabled (with tooltip) when `claimed_by_bot_id IS NOT NULL`.
- **Ledger table:** reuses the user-side `LedgerTable` component; filter bar identical to user view; pagination identical. ADJUSTMENT rows show `created_by_admin_id` resolved to admin display name.

### 2.2 Right-rail "Wallet" panel

- Compact balance summary (balance / available / penalty if any).
- `Adjust balance` button → `AdjustBalanceModal`.
- `Freeze wallet` / `Unfreeze wallet` toggle → `ConfirmActionModal` with notes.
- `Forgive penalty` button (rendered only when `penaltyBalanceOwed > 0`) → `ForgivePenaltyModal`.
- `Reset dormancy` button (rendered only when `walletDormancyPhase IS NOT NULL`) → `ConfirmActionModal`.
- `Force terms re-acceptance` button → `ConfirmActionModal`.
- `View as user` link — opens `/wallet?as=<publicId>` in a new tab.

### 2.3 View-as-user

The user-side `/wallet` page accepts an optional `?as=<publicId>` query param. When present, the page checks the requester's role; if ADMIN, it switches data sources to the new admin endpoints, hides every mutation control (Withdraw, Pay Penalty, Accept Terms, CSV Export), and renders a sticky banner: `Viewing wallet of @<username> (admin read-only)`. Non-admin requesters with `?as=` set see the parameter ignored (their own wallet renders).

## 3. Backend

### 3.1 New controller

`AdminWalletController` at `/api/v1/admin/users/{publicId}/wallet/*`. Resolves `publicId` to internal Long via `userRepository.findByPublicId(...)` (same pattern as `AdminUserController`).

| Method + path | Body | Purpose |
|---|---|---|
| `GET    /admin/users/{publicId}/wallet` | — | `AdminWalletSnapshotDto` (balance breakdown, freeze state, dormancy state, terms state, pending withdrawals). |
| `GET    /admin/users/{publicId}/wallet/ledger?page=&size=&type=&from=&to=&minAmount=&maxAmount=` | — | Paginated `Page<AdminLedgerRowDto>`. Same projection as user-side `/me/wallet/ledger` plus `createdByAdminId` for ADJUSTMENT rows and `finalizedByAdminId` for force-finalized withdrawal rows. |
| `POST   /admin/users/{publicId}/wallet/adjust` | `AdminWalletAdjustRequest` | Manual ADJUSTMENT entry. |
| `POST   /admin/users/{publicId}/wallet/freeze` | `AdminWalletNotesRequest` | Freeze. |
| `POST   /admin/users/{publicId}/wallet/unfreeze` | `AdminWalletNotesRequest` | Unfreeze. |
| `POST   /admin/users/{publicId}/wallet/forgive-penalty` | `AdminWalletForgivePenaltyRequest` | Decrement `penaltyBalanceOwed`. |
| `POST   /admin/users/{publicId}/wallet/reset-dormancy` | `AdminWalletNotesRequest` | Clear dormancy state. |
| `POST   /admin/users/{publicId}/wallet/clear-terms` | `AdminWalletNotesRequest` | Clear terms acceptance. |
| `POST   /admin/users/{publicId}/wallet/withdrawals/{withdrawalId}/force-complete` | `AdminWalletNotesRequest` | Mark PENDING withdrawal SUCCESS. |
| `POST   /admin/users/{publicId}/wallet/withdrawals/{withdrawalId}/force-fail` | `AdminWalletNotesRequest` | Mark PENDING withdrawal FAILED, refund balance. |

All mutations return the post-mutation `AdminWalletSnapshotDto`.

### 3.2 Service

`AdminWalletService` — single class, mirrors `AdminUserService` shape. Constructor wires `UserRepository`, `WithdrawalRepository`, `UserLedgerRepository`, `AdminActionService`, `NotificationService`, `Clock`. Each mutation method:

```java
@Transactional
public AdminWalletSnapshotDto adjust(Long userId, AdminWalletAdjustRequest req, Long adminId) {
    User u = userRepo.findByIdForUpdate(userId).orElseThrow(...);
    long newBalance = u.getBalanceLindens() + req.amount();
    if (newBalance < u.getReservedLindens() && !req.overrideReservationFloor()) {
        throw new ReservationFloorViolationException(...);
    }
    u.setBalanceLindens(newBalance);
    UserLedgerEntry entry = UserLedgerEntry.builder()
        .user(u).entryType(LedgerEntryType.ADJUSTMENT)
        .amount(req.amount()).balanceAfter(newBalance)
        .createdByAdminId(adminId).description(req.notes())
        .build();
    ledgerRepo.save(entry);
    adminActionService.record(adminId, AdminActionType.WALLET_ADJUST,
        AdminActionTargetType.USER, userId, req.notes(),
        Map.of("amount", req.amount(), "overrideReservationFloor", req.overrideReservationFloor()));
    notificationService.send(u, NotificationType.WALLET_ADJUSTED,
        Map.of("amount", req.amount(), "notes", req.notes()));
    return toSnapshot(u);
}
```

### 3.3 New DTOs

```java
record AdminWalletSnapshotDto(
    UUID publicId, String username,
    long balanceLindens, long reservedLindens, long availableLindens,
    long penaltyBalanceOwed,
    OffsetDateTime walletFrozenAt, String walletFrozenReason, Long walletFrozenByAdminId,
    OffsetDateTime walletDormancyStartedAt, Integer walletDormancyPhase,
    OffsetDateTime walletTermsAcceptedAt, String walletTermsVersion,
    List<AdminPendingWithdrawalDto> pendingWithdrawals
) {}

record AdminPendingWithdrawalDto(
    Long withdrawalId, long amount, UUID recipientAvatarUuid,
    OffsetDateTime requestedAt,
    Long claimedByBotId, OffsetDateTime claimedAt, OffsetDateTime claimExpiresAt,
    boolean canForceFinalize  // true iff claimedByBotId IS NULL
) {}

record AdminLedgerRowDto(
    Long entryId, LedgerEntryType entryType,
    long amount, long balanceAfter,
    OffsetDateTime createdAt, String description,
    Long createdByAdminId, String createdByAdminDisplayName,
    Long finalizedByAdminId   // only set on withdrawal-related ledger rows
) {}

record AdminWalletAdjustRequest(
    @NotNull Long amount,                          // signed; 0 rejected
    @NotBlank String notes,
    boolean overrideReservationFloor
) {}

record AdminWalletForgivePenaltyRequest(
    @NotNull @Min(1) Long amount,
    @NotBlank String notes
) {}

record AdminWalletNotesRequest(
    @NotBlank String notes
) {}
```

### 3.4 Notifications

New `NotificationType` enum values:

- `WALLET_ADJUSTED`
- `WALLET_FROZEN`
- `WALLET_UNFROZEN`
- `WALLET_PENALTY_FORGIVEN`
- `WALLET_DORMANCY_RESET`
- `WALLET_TERMS_CLEARED`
- `WITHDRAWAL_FORCE_COMPLETED`
- `WITHDRAWAL_FORCE_FAILED`

Categorised under a new always-on `wallet_admin` group. Bypasses the `notify_email` / `notify_sl_im` jsonb maps — material admin decisions about the user's funds are not user-suppressible. Email subject lines and SL IM bodies templated like other notifications.

### 3.5 New `AdminActionType` values

- `WALLET_ADJUST`
- `WALLET_FREEZE`, `WALLET_UNFREEZE`
- `WALLET_FORGIVE_PENALTY`
- `WALLET_RESET_DORMANCY`
- `WALLET_CLEAR_TERMS`
- `WITHDRAWAL_FORCE_COMPLETE`, `WITHDRAWAL_FORCE_FAIL`

All target `AdminActionTargetType.USER` (the user being operated on; for withdrawal force-finalize the user is also recorded — the withdrawal id sits in `metadata`).

### 3.6 Outflow gate (freeze enforcement)

The wallet freeze blocks outflows only — deposits, admin adjustments, and incoming notifications still work. Enforcement is one new `WalletFrozenException` thrown at the top of each outflow path:

- `WalletService.withdraw(...)` — site-initiated withdraw
- `WalletService.payPenalty(...)`
- `WalletService.payListingFee(...)`
- `BidReservationService.reserveForBid(...)`
- `SlWalletController.withdrawRequest(...)` (in-world touch withdraw) — translates the exception to a REFUND response

`WalletFrozenException` maps to HTTP 423 (Locked) with `code=WALLET_FROZEN` for the JSON-API surface; for SL terminal endpoints it maps to the standard REFUND-with-reason response that already exists for other rejected outflows.

## 4. Schema

Single Flyway migration `V15__admin_wallet_ops.sql`:

```sql
ALTER TABLE users
  ADD COLUMN wallet_frozen_at TIMESTAMP WITH TIME ZONE NULL,
  ADD COLUMN wallet_frozen_by_admin_id BIGINT NULL REFERENCES users(id),
  ADD COLUMN wallet_frozen_reason TEXT NULL;

ALTER TABLE withdrawals
  ADD COLUMN finalized_by_admin_id BIGINT NULL REFERENCES users(id);
```

No new ledger entry types — `ADJUSTMENT` already exists; the bot-failure reversal entry type the natural-failure path uses is reused (with `created_by_admin_id` set on admin force-fail rows).

## 5. Race + concurrency model

The risk on force-complete / force-fail: bot has claimed a PENDING withdrawal and is mid L$-payout in SL. If admin force-fails in this window, user gets L$ delivered AND a refund — double pay. Mitigation has three layers:

1. **Pessimistic row lock.** `withdrawalRepo.findByIdForUpdate(withdrawalId)` (new method, mirrors `UserRepository.findByIdForUpdate`) acquires `SELECT … FOR UPDATE` inside the service transaction. Two admins clicking simultaneously serialize.
2. **Bot-claim gate.** Force-complete and force-fail reject (409 with `code=BOT_PROCESSING`) when `claimed_by_bot_id IS NOT NULL`. The bot's claim has a finite lease (`claim_expires_at`); if the bot crashes, the existing lease-expiry job clears the claim and admin action is unblocked.
3. **State machine guard inside the lock.** Re-check `status == PENDING` after acquiring the lock; reject with 409 `code=WITHDRAWAL_NOT_PENDING` otherwise.

The bot worker's existing claim path also acquires a row lock and respects the same status check, so admin-held lock blocks bot-attempted claim.

## 6. Audit

Every mutation writes an `AdminAction` row via the existing `AdminActionService.record(...)` pattern. `notes` is `@NotBlank` at the request layer (no defaults, no empty strings). `metadata` jsonb captures operation-specific details (amount, withdrawalId, overrideReservationFloor, etc.) for later forensic queries. The existing `/admin/audit` UI surfaces these rows immediately — no new audit-side work required.

## 7. Frontend

### 7.1 Files to touch

- `frontend/src/lib/admin/types.ts` — add `AdminWalletSnapshot`, `AdminPendingWithdrawal`, `AdminLedgerRow`, `AdminWalletAdjustRequest`, `AdminWalletForgivePenaltyRequest`, `AdminWalletNotesRequest`
- `frontend/src/lib/admin/api.ts` — add `adminApi.users.wallet.*` namespace with `snapshot`, `ledger`, `adjust`, `freeze`, `unfreeze`, `forgivePenalty`, `resetDormancy`, `clearTerms`, `forceCompleteWithdrawal`, `forceFailWithdrawal`
- `frontend/src/lib/admin/queryKeys.ts` — `wallet: (publicId) => …`, `walletLedger: (publicId, filters) => …`
- `frontend/src/hooks/admin/useAdminWallet.ts` — query the snapshot
- `frontend/src/hooks/admin/useAdminWalletLedger.ts` — paginated ledger
- `frontend/src/hooks/admin/wallet/` — one mutation hook per operation (8 files), each follows the existing pattern from `usePromoteUser`/`useDemoteUser`: invalidates wallet snapshot + ledger + admin user detail
- `frontend/src/components/admin/users/tabs/WalletTab.tsx` — the new tab
- `frontend/src/components/admin/users/wallet/PendingWithdrawalsList.tsx`
- `frontend/src/components/admin/users/wallet/AdjustBalanceModal.tsx`
- `frontend/src/components/admin/users/wallet/ForgivePenaltyModal.tsx`
- `frontend/src/components/admin/users/wallet/ForceFinalizeWithdrawalModal.tsx` (handles both complete + fail variants)
- Reuse the existing `ConfirmActionModal` for freeze, unfreeze, reset-dormancy, clear-terms (notes-only mutations)
- `frontend/src/components/admin/users/AdminUserDetailPage.tsx` — add Wallet tab + render `WalletTab`
- `frontend/src/components/admin/users/UserActionsRail.tsx` — add Wallet panel with quick actions
- `frontend/src/components/admin/users/UserTabsNav.tsx` — add `"wallet"` to tab list
- `frontend/src/components/wallet/WalletPanel.tsx` — accept optional `viewingAs?: { publicId: string; username: string }` prop; switch data source to admin endpoints when set; hide mutation controls; render banner
- `frontend/src/app/wallet/page.tsx` — read `?as=` query param, look up admin role from session, pass through as `viewingAs` prop if both conditions met

### 7.2 Hook structure

Each mutation hook follows the existing admin mutation pattern (e.g. `usePromoteUser`):

```ts
export function useAdjustBalance(publicId: string) {
  const qc = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: (body: AdminWalletAdjustRequest) =>
      adminApi.users.wallet.adjust(publicId, body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: adminQueryKeys.wallet(publicId) });
      qc.invalidateQueries({ queryKey: adminQueryKeys.walletLedger(publicId) });
      qc.invalidateQueries({ queryKey: adminQueryKeys.user(publicId) });
      toast.success("Balance adjusted.");
    },
    onError: handleAdminMutationError("Couldn't adjust balance."),
  });
}
```

### 7.3 Reservation-floor guard UX

The `AdjustBalanceModal` shows the user's current balance and reserved amount. If the admin types an amount that would push `balance < reserved`, the modal shows an inline warning and exposes a checkbox `Override reservation floor — I understand this leaves the user under-reserved`. Submit disabled until either the amount is safe or the override is ticked. Backend independently validates with the same rule.

## 8. Testing

- **Backend unit tests** for `AdminWalletService` per mutation: happy path, validation rejection, lock contention not exercised here (covered implicitly by Postgres test container). MockMvc slice tests for the controller: auth gate (401 anon, 403 non-admin), 200 admin happy path, 400 invalid payload, 404 unknown user/withdrawal.
- **Backend integration test** for force-fail: seed a withdrawal in PENDING with a claim, attempt force-fail, expect 409. Clear the claim, attempt again, expect 200 + ledger reversal + balance restoration.
- **Frontend tests:** skip per the project's recent feedback memory ("Don't create tests for the bootstrapper, that's unnecessary test coverage" — same posture applies here unless a specific test would have caught a recently-shipped bug).

## 9. Deployment

Single PR, dev → main. The migration is additive (no NOT NULL columns without defaults), so no DB wipe needed. Backend deploy auto-applies V15. Frontend Amplify rebuild ships the new tab + view-as-user.

## 10. Rollback

`V15` is additive; rollback is `ALTER TABLE ... DROP COLUMN ...` via a one-shot Fargate task. Frontend rollback is the standard Amplify revert. No data is lost on backward step except admin freeze state and admin-finalized withdrawal attribution.

## 11. Out of scope

- Bulk operations across multiple users
- Two-admin sign-off / approval workflow
- Per-call adjustment amount caps
- Wallet-admin-specific reporting dashboards (use the existing audit log surface)
- Notification preferences for the new `wallet_admin` group (intentionally non-mutable)
