"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";
import { Modal } from "@/components/ui/Modal";
import { isApiError } from "@/lib/api";
import { withdrawFromGroupWallet } from "@/lib/api/realtyGroupWallet";
import { useInvalidateGroupWallet } from "@/hooks/realty/useGroupWallet";

function formatLindens(amount: number): string {
  return `L$${amount.toLocaleString()}`;
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
}

/**
 * Controlled-input modal that initiates a group wallet withdrawal. Funds are
 * always sent to the group leader's verified SL avatar — callers do not pick
 * a recipient. The idempotency key is generated client-side with
 * {@code crypto.randomUUID()} to make retries safe.
 *
 * Error codes surfaced with specific copy:
 *  - {@code INSUFFICIENT_GROUP_BALANCE} — shows available/requested amounts.
 *  - {@code LEADER_TERMS_NOT_ACCEPTED} — directs leader to accept ToS first.
 *  - {@code LEADER_FROZEN} — explains the leader account is restricted.
 *
 * On success the wallet + ledger queries are invalidated via
 * {@link useInvalidateGroupWallet} so the balance card and table refresh.
 */
export function GroupWithdrawModal({
  open,
  onClose,
  publicId,
  available,
}: GroupWithdrawModalProps) {
  const [amount, setAmount] = useState<string>("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const invalidateWallet = useInvalidateGroupWallet(publicId);

  const handleClose = () => {
    setAmount("");
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
        <p className="text-sm text-fg-muted">
          Funds will be sent to the group leader&apos;s verified SL avatar via the
          in-world SLParcels terminal pool.
        </p>
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
