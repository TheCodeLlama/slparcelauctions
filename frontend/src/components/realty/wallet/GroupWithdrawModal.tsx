"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";
import { Modal } from "@/components/ui/Modal";
import { isApiError } from "@/lib/api";
import { withdrawFromGroupWallet } from "@/lib/api/realtyGroupWallet";
import { useInvalidateGroupWallet } from "@/hooks/realty/useGroupWallet";
import type { GroupWithdrawRecipient } from "@/types/realty";

function formatLindens(amount: number): string {
  return `L$${amount.toLocaleString()}`;
}

/**
 * Sub-project G §7.3 — minimal view of the realty group's currently-registered
 * SL group as needed by the recipient picker. The modal does not fetch this
 * itself; the parent {@code GroupWalletPage} sources it (from a dedicated hook
 * or an extended wallet payload) and passes it in. {@code null} means no SL
 * group is registered; {@code suspended: true} means the realty group has an
 * active suspension and SL-group withdrawals are blocked. Drift alone does
 * NOT count as suspended — §7.3 explicitly allows withdrawing to a
 * drift-flagged but otherwise verified registration.
 */
export interface GroupWithdrawSlGroupOption {
  /** Display name for the registered SL group (e.g. "Sunset Estate"). */
  name: string;
  /** {@code true} iff the realty group has an active suspension. */
  suspended: boolean;
}

export interface GroupWithdrawModalProps {
  /** Whether the modal is open. */
  open: boolean;
  /** Called to close the modal. */
  onClose: () => void;
  /** Group public UUID. */
  publicId: string;
  /** Available balance (backend-confirmed). */
  available: number;
  /**
   * The realty group's currently-registered SL group, or {@code null} if none.
   * Drives the recipient picker:
   *
   *  - {@code null} — the SL_GROUP radio is omitted entirely and withdrawals
   *    always route to the leader's avatar.
   *  - {@code suspended === true} — the SL_GROUP radio renders but is
   *    disabled with a tooltip; the AVATAR option remains selectable.
   *  - otherwise — both radios are selectable.
   *
   * Defaults to {@code null} so existing callers (pre-G wiring) keep working
   * without changes.
   */
  slGroup?: GroupWithdrawSlGroupOption | null;
}

/**
 * Controlled-input modal that initiates a group wallet withdrawal. The leader
 * picks a recipient via a binary radio group:
 *
 *  - {@code AVATAR} (default) — routes L$ to the group leader's verified
 *    SL avatar via the in-world SLParcels terminal pool.
 *  - {@code SL_GROUP} — routes L$ to the realty group's currently-registered
 *    SL group, fulfilled by the bot via {@code Self.GiveGroupMoney}.
 *
 * The idempotency key is generated client-side with {@code crypto.randomUUID()}
 * to make retries safe.
 *
 * Error codes surfaced with specific copy:
 *  - {@code INSUFFICIENT_GROUP_BALANCE} — shows available/requested amounts.
 *  - {@code LEADER_TERMS_NOT_ACCEPTED} — directs leader to accept ToS first.
 *  - {@code LEADER_FROZEN} — explains the leader account is restricted.
 *  - {@code SL_GROUP_NOT_REGISTERED} — directs the user to the AVATAR option.
 *  - {@code SL_GROUP_REGISTRATION_SUSPENDED} — explains the realty group is
 *    suspended and the AVATAR option is still available.
 *
 * On success the wallet + ledger queries are invalidated via
 * {@link useInvalidateGroupWallet} so the balance card and table refresh.
 */
export function GroupWithdrawModal({
  open,
  onClose,
  publicId,
  available,
  slGroup = null,
}: GroupWithdrawModalProps) {
  const [amount, setAmount] = useState<string>("");
  const [recipient, setRecipient] =
    useState<GroupWithdrawRecipient>("AVATAR");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const invalidateWallet = useInvalidateGroupWallet(publicId);

  const slGroupDisabled = slGroup !== null && slGroup.suspended;

  const handleClose = () => {
    setAmount("");
    setRecipient("AVATAR");
    setError(null);
    onClose();
  };

  const handleSubmit = async () => {
    const parsed = parseInt(amount, 10);
    if (!Number.isFinite(parsed) || parsed <= 0) {
      setError("Enter a positive integer amount.");
      return;
    }
    if (parsed > available) {
      setError(`Available balance is ${formatLindens(available)}.`);
      return;
    }

    setSubmitting(true);
    setError(null);

    try {
      await withdrawFromGroupWallet(publicId, {
        amount: parsed,
        idempotencyKey: crypto.randomUUID(),
        recipient,
      });
      invalidateWallet();
      handleClose();
    } catch (e) {
      if (isApiError(e)) {
        const code = e.problem.code as string | undefined;
        if (code === "INSUFFICIENT_GROUP_BALANCE") {
          const avail = e.problem.available as number | undefined;
          const requested = e.problem.requested as number | undefined;
          const availStr = avail !== undefined ? formatLindens(avail) : "unknown";
          const reqStr = requested !== undefined ? formatLindens(requested) : formatLindens(parsed);
          setError(
            `Insufficient balance. Available: ${availStr}, requested: ${reqStr}.`,
          );
        } else if (code === "LEADER_TERMS_NOT_ACCEPTED") {
          setError(
            "The group leader has not accepted the Wallet Terms of Service. The leader must accept terms on their personal wallet page before withdrawals can proceed.",
          );
        } else if (code === "LEADER_FROZEN") {
          setError(
            "The group leader's account is currently restricted. Withdrawals are unavailable until the restriction is lifted.",
          );
        } else if (code === "SL_GROUP_NOT_REGISTERED") {
          setError(
            "This group has no registered SL group. Choose Leader's avatar or register an SL group first.",
          );
        } else if (code === "SL_GROUP_REGISTRATION_SUSPENDED") {
          setError(
            "SL-group withdrawals are blocked while the realty group is suspended. Choose Leader's avatar instead.",
          );
        } else {
          setError(e.message);
        }
      } else {
        setError("Withdrawal failed. Please try again.");
      }
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open={open}
      title="Withdraw from Group Wallet"
      onClose={handleClose}
      footer={
        <>
          <Button
            variant="secondary"
            onClick={handleClose}
            disabled={submitting}
          >
            Cancel
          </Button>
          <Button
            variant="primary"
            onClick={handleSubmit}
            loading={submitting}
            data-testid="withdraw-submit"
          >
            Withdraw
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-3">
        <p className="text-sm text-fg-muted">
          Available:{" "}
          <strong className="text-fg">{formatLindens(available)}</strong>
        </p>

        <fieldset
          className="flex flex-col gap-2"
          data-testid="withdraw-recipient-picker"
        >
          <legend className="text-xs font-medium text-fg">
            Send funds to
          </legend>
          <label className="flex items-center gap-2 text-sm text-fg">
            <input
              type="radio"
              name={`withdraw-recipient-${publicId}`}
              value="AVATAR"
              checked={recipient === "AVATAR"}
              onChange={() => setRecipient("AVATAR")}
              className="size-4 accent-primary"
              data-testid="withdraw-recipient-avatar"
            />
            <span>Leader&apos;s avatar</span>
          </label>
          {slGroup !== null ? (
            <label
              className={
                slGroupDisabled
                  ? "flex items-center gap-2 text-sm text-fg-muted"
                  : "flex items-center gap-2 text-sm text-fg"
              }
            >
              <input
                type="radio"
                name={`withdraw-recipient-${publicId}`}
                value="SL_GROUP"
                checked={recipient === "SL_GROUP"}
                onChange={() => setRecipient("SL_GROUP")}
                disabled={slGroupDisabled}
                className="size-4 accent-primary"
                data-testid="withdraw-recipient-sl-group"
                title={
                  slGroupDisabled
                    ? "Group registration suspended — choose Leader's avatar instead"
                    : undefined
                }
              />
              <span>SL group: {slGroup.name}</span>
            </label>
          ) : null}
          {slGroupDisabled ? (
            <p
              className="text-xs text-fg-muted"
              data-testid="withdraw-recipient-sl-group-suspended-hint"
            >
              Group registration suspended — choose Leader&apos;s avatar
              instead.
            </p>
          ) : null}
        </fieldset>

        <Input
          type="text"
          inputMode="numeric"
          value={amount}
          onChange={(e) => setAmount(e.target.value)}
          placeholder="Amount in L$"
          aria-label="Withdrawal amount in L$"
          error={error ?? undefined}
          data-testid="withdraw-amount-input"
        />
      </div>
    </Modal>
  );
}
