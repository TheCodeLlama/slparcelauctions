"use client";
import { useState, useEffect } from "react";
import { Button } from "@/components/ui/Button";
import {
  useForceCompleteWithdrawal,
  useForceFailWithdrawal,
} from "@/hooks/admin/useAdminWallet";
import type { AdminWalletPendingWithdrawal } from "@/lib/admin/types";

const NOTES_MAX = 1000;

type Props = {
  open: boolean;
  publicId: string;
  withdrawal: AdminWalletPendingWithdrawal;
  mode: "complete" | "fail";
  onClose: () => void;
};

export function ForceFinalizeWithdrawalModal({
  open,
  publicId,
  withdrawal,
  mode,
  onClose,
}: Props) {
  const [notes, setNotes] = useState("");
  const complete = useForceCompleteWithdrawal(publicId);
  const fail = useForceFailWithdrawal(publicId);
  const mutation = mode === "complete" ? complete : fail;

  useEffect(() => {
    if (!open) return;
    setNotes("");
  }, [open]);

  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [onClose]);

  if (!open) return null;

  const canSubmit = notes.trim().length > 0 && !mutation.isPending;
  const isComplete = mode === "complete";

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!canSubmit) return;
    mutation.mutate(
      { terminalCommandId: withdrawal.terminalCommandId, body: { notes: notes.trim() } },
      { onSuccess: onClose },
    );
  }

  return (
    <>
      <div
        className="fixed inset-0 z-40 bg-inverse-surface/20"
        onClick={onClose}
        aria-hidden="true"
      />
      <div
        role="dialog"
        aria-modal="true"
        aria-label={isComplete ? "Force-complete withdrawal" : "Force-fail withdrawal"}
        data-testid="force-finalize-withdrawal-modal"
        className="fixed inset-0 z-50 flex items-center justify-center p-4"
      >
        <div
          className="w-full max-w-md rounded-lg bg-bg-subtle border border-border-subtle shadow-md p-6 flex flex-col gap-4"
          onClick={(e) => e.stopPropagation()}
        >
          <div className="flex items-start justify-between gap-2">
            <div>
              <h2 className="text-sm font-semibold text-fg">
                {isComplete ? "Force-complete withdrawal" : "Force-fail and refund"}
              </h2>
              <p className="mt-1 text-sm text-fg-muted">
                {isComplete
                  ? "Confirm that you have manually paid out the L$ in-world. The wallet ledger will append a WITHDRAW_COMPLETED row."
                  : "Refund the L$ back to the user's wallet. The terminal command is marked FAILED and a WITHDRAW_REVERSED row credits the balance."}
              </p>
            </div>
            <button
              type="button"
              onClick={onClose}
              aria-label="Close"
              className="p-1.5 rounded-lg text-fg-muted hover:bg-bg-muted"
            >
              ✕
            </button>
          </div>

          <div className="rounded-lg bg-bg-muted p-3 text-[11px] text-fg-muted flex flex-col gap-1">
            <div className="flex justify-between">
              <span>Amount</span>
              <span className="font-mono text-fg">
                L$ {withdrawal.amount.toLocaleString()}
              </span>
            </div>
            <div className="flex justify-between">
              <span>Recipient avatar UUID</span>
              <span className="font-mono text-fg truncate ml-2" title={withdrawal.recipientUuid}>
                {withdrawal.recipientUuid.slice(0, 8)}…{withdrawal.recipientUuid.slice(-4)}
              </span>
            </div>
            <div className="flex justify-between">
              <span>Queued at</span>
              <span className="text-fg">
                {new Date(withdrawal.queuedAt).toLocaleString()}
              </span>
            </div>
          </div>

          <form onSubmit={handleSubmit} className="flex flex-col gap-4">
            <div className="flex flex-col gap-1">
              <label htmlFor="finalize-notes" className="text-xs font-medium text-fg">
                Notes <span className="text-danger">*</span>
              </label>
              <textarea
                id="finalize-notes"
                rows={3}
                value={notes}
                disabled={mutation.isPending}
                onChange={(e) => setNotes(e.target.value.slice(0, NOTES_MAX))}
                placeholder={
                  isComplete
                    ? "e.g. paid via SL terminal at 14:32"
                    : "Reason for failing this withdrawal…"
                }
                data-testid="finalize-notes-textarea"
                className="w-full resize-y rounded-lg bg-bg-muted px-4 py-3 text-fg placeholder:text-fg-muted ring-1 ring-border-subtle focus:outline-none focus:ring-2 focus:ring-brand disabled:opacity-50"
              />
              <div className="self-end text-[11px] font-medium text-fg-muted">
                {notes.length} / {NOTES_MAX}
              </div>
            </div>

            <div className="flex justify-end gap-2">
              <Button
                variant="secondary"
                type="button"
                onClick={onClose}
                disabled={mutation.isPending}
              >
                Cancel
              </Button>
              <Button
                variant={isComplete ? "primary" : "destructive"}
                type="submit"
                disabled={!canSubmit}
                loading={mutation.isPending}
                data-testid="finalize-submit"
              >
                {isComplete ? "Mark completed" : "Fail and refund"}
              </Button>
            </div>
          </form>
        </div>
      </div>
    </>
  );
}
