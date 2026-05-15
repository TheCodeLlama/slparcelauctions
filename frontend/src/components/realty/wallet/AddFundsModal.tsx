"use client";

import { useRef, useState } from "react";
import { Button } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";
import { Modal } from "@/components/ui/Modal";
import { useToast } from "@/components/ui/Toast/useToast";
import { isApiError } from "@/lib/api";
import { useGroupDeposit } from "@/hooks/realty/useGroupDeposit";

const DEFAULT_MAX_DEPOSIT_L = 500_000;
const MEMO_MAX_LENGTH = 200;

function formatLindens(amount: number): string {
  return `L$${amount.toLocaleString()}`;
}

export interface AddFundsModalProps {
  /** Whether the modal is open. */
  open: boolean;
  /** Called to dismiss the modal (success, cancel, Escape, or backdrop). */
  onClose: () => void;
  /** Group being deposited into. Only {@code publicId} is wire-relevant;
   *  {@code name} is used in the title + success toast copy. */
  group: { publicId: string; name: string };
  /** Depositor's current personal wallet available balance (L$). Drives the
   *  inline validation cap and the helper text under the amount field. */
  personalAvailable: number;
  /** Per-deposit cap from {@code slpa.realty.group-deposit-max-l}.
   *  Defaults to 500_000 to match the backend default; callers SHOULD pass
   *  the live value from {@code /api/v1/config} once that is plumbed through.
   */
  maxDepositL?: number;
}

/**
 * Sub-project H — modal for depositing L$ from the caller's personal
 * SLParcels wallet into a realty group's wallet. Atomic personal -> group
 * transfer; idempotent on a client-generated UUID.
 *
 * <p>The idempotency key is regenerated each time the modal opens so a
 * canceled-and-retried flow doesn't collapse into the same ledger row, but a
 * within-session retry of the same submission (e.g. transient 5xx, user
 * presses Deposit again) reuses the key and is safe.
 *
 * <p>Error mapping (RFC 9457 problem.code):
 * <ul>
 *   <li>{@code INSUFFICIENT_BALANCE} -- inline under amount.</li>
 *   <li>{@code AMOUNT_OUT_OF_RANGE} -- inline under amount.</li>
 *   <li>{@code USER_FROZEN}, {@code GROUP_DISSOLVED}, {@code GROUP_SUSPENDED},
 *       {@code REALTY_GROUP_PERMISSION_DENIED} -- toast, close modal.</li>
 *   <li>anything else -- toast the error message, close modal.</li>
 * </ul>
 */
export function AddFundsModal({
  open,
  onClose,
  group,
  personalAvailable,
  maxDepositL = DEFAULT_MAX_DEPOSIT_L,
}: AddFundsModalProps) {
  const [amount, setAmount] = useState<string>("");
  const [memo, setMemo] = useState<string>("");
  const [inlineError, setInlineError] = useState<string | null>(null);
  // Idempotency key is generated lazily on the first submission of each
  // open-session and cleared by {@link handleClose}. A within-session retry
  // (transient 5xx then user presses Deposit again) reuses the key so the
  // backend collapses duplicates; a closed-and-reopened flow gets a fresh
  // key because the close path nulls it out.
  const idempotencyKeyRef = useRef<string | null>(null);
  const toast = useToast();
  const deposit = useGroupDeposit(group.publicId);

  const parsed = parseInt(amount, 10);
  const amountIsInt = /^\d+$/.test(amount) && Number.isFinite(parsed);
  const amountTooHighForWallet = amountIsInt && parsed > personalAvailable;
  const amountTooHighForCap = amountIsInt && parsed > maxDepositL;
  const validationError: string | null = amountTooHighForWallet
    ? "Not enough funds in your wallet."
    : amountTooHighForCap
      ? `Maximum per deposit is L$${maxDepositL.toLocaleString()}.`
      : null;

  const canSubmit =
    amountIsInt &&
    parsed >= 1 &&
    parsed <= personalAvailable &&
    parsed <= maxDepositL &&
    !deposit.isPending;

  const handleClose = () => {
    setAmount("");
    setMemo("");
    setInlineError(null);
    idempotencyKeyRef.current = null;
    onClose();
  };

  const handleSubmit = async () => {
    if (!amountIsInt || parsed < 1) {
      setInlineError("Enter a positive integer amount.");
      return;
    }
    if (parsed > personalAvailable) {
      setInlineError("Not enough funds in your wallet.");
      return;
    }
    if (parsed > maxDepositL) {
      setInlineError(`Maximum per deposit is L$${maxDepositL.toLocaleString()}.`);
      return;
    }

    setInlineError(null);

    if (idempotencyKeyRef.current === null) {
      idempotencyKeyRef.current = crypto.randomUUID();
    }

    try {
      await deposit.mutateAsync({
        amount: parsed,
        memo: memo.trim() === "" ? undefined : memo.trim(),
        idempotencyKey: idempotencyKeyRef.current,
      });
      toast.success(`Deposited L$${parsed.toLocaleString()} to ${group.name}.`);
      handleClose();
    } catch (e) {
      if (isApiError(e)) {
        const code = (e.problem as { code?: string }).code;
        if (code === "INSUFFICIENT_BALANCE") {
          setInlineError("Not enough funds in your wallet.");
          return;
        }
        if (code === "AMOUNT_OUT_OF_RANGE") {
          setInlineError("Amount is outside the allowed range.");
          return;
        }
        if (code === "USER_FROZEN") {
          toast.error("Your wallet is currently frozen.");
          handleClose();
          return;
        }
        if (code === "GROUP_DISSOLVED") {
          toast.error("This group has been dissolved.");
          handleClose();
          return;
        }
        if (code === "GROUP_SUSPENDED") {
          toast.error("This group is currently suspended.");
          handleClose();
          return;
        }
        if (code === "REALTY_GROUP_PERMISSION_DENIED") {
          toast.error("You no longer have permission to deposit.");
          handleClose();
          return;
        }
        toast.error(e.message);
        handleClose();
        return;
      }
      toast.error("Deposit failed. Please try again.");
      handleClose();
    }
  };

  const depositLabel = amountIsInt && parsed >= 1
    ? `Deposit L$${parsed.toLocaleString()}`
    : "Deposit";

  return (
    <Modal
      open={open}
      title={`Add funds to ${group.name}`}
      onClose={handleClose}
      footer={
        <>
          <Button
            variant="secondary"
            onClick={handleClose}
            disabled={deposit.isPending}
            data-testid="add-funds-cancel"
          >
            Cancel
          </Button>
          <Button
            variant="primary"
            onClick={handleSubmit}
            loading={deposit.isPending}
            disabled={!canSubmit}
            data-testid="add-funds-submit"
          >
            {depositLabel}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-3">
        <Input
          type="text"
          inputMode="numeric"
          step="1"
          value={amount}
          onChange={(e) => {
            setAmount(e.target.value);
            setInlineError(null);
          }}
          placeholder="Amount in L$"
          aria-label="Deposit amount in L$"
          label="Amount"
          helperText={`Available: ${formatLindens(personalAvailable)} (your wallet)`}
          error={(inlineError ?? validationError) ?? undefined}
          data-testid="add-funds-amount-input"
        />

        <Input
          type="text"
          value={memo}
          onChange={(e) => setMemo(e.target.value.slice(0, MEMO_MAX_LENGTH))}
          placeholder="Optional note"
          aria-label="Memo"
          label="Memo (optional)"
          maxLength={MEMO_MAX_LENGTH}
          data-testid="add-funds-memo-input"
        />

        <p className="text-xs text-fg-muted">
          This transfer is final. Use Withdraw to retrieve funds.
        </p>
      </div>
    </Modal>
  );
}
