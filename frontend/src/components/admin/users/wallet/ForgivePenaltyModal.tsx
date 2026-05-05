"use client";
import { useState, useEffect } from "react";
import { Button } from "@/components/ui/Button";
import { useForgivePenalty } from "@/hooks/admin/useAdminWallet";

const NOTES_MAX = 1000;

type Props = {
  open: boolean;
  publicId: string;
  penaltyOwed: number;
  onClose: () => void;
};

export function ForgivePenaltyModal({ open, publicId, penaltyOwed, onClose }: Props) {
  const [amount, setAmount] = useState<string>("");
  const [notes, setNotes] = useState<string>("");
  const forgive = useForgivePenalty(publicId);

  useEffect(() => {
    if (!open) return;
    setAmount(String(penaltyOwed));
    setNotes("");
  }, [open, penaltyOwed]);

  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [onClose]);

  if (!open) return null;

  const amountNum = parseInt(amount, 10);
  const amountValid =
    Number.isFinite(amountNum) && amountNum > 0 && amountNum <= penaltyOwed;
  const canSubmit = amountValid && notes.trim().length > 0 && !forgive.isPending;

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!canSubmit) return;
    forgive.mutate(
      { amount: amountNum, notes: notes.trim() },
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
        aria-label="Forgive penalty"
        data-testid="forgive-penalty-modal"
        className="fixed inset-0 z-50 flex items-center justify-center p-4"
      >
        <div
          className="w-full max-w-md rounded-lg bg-bg-subtle border border-border-subtle shadow-md p-6 flex flex-col gap-4"
          onClick={(e) => e.stopPropagation()}
        >
          <div className="flex items-start justify-between gap-2">
            <div>
              <h2 className="text-sm font-semibold text-fg">Forgive penalty</h2>
              <p className="mt-1 text-sm text-fg-muted">
                Decrements the user&apos;s outstanding penalty without taking L$ from
                their wallet.
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

          <div className="rounded-lg bg-bg-muted p-3 text-[11px] text-fg-muted flex justify-between">
            <span>Currently owed</span>
            <span className="font-mono text-fg">L$ {penaltyOwed.toLocaleString()}</span>
          </div>

          <form onSubmit={handleSubmit} className="flex flex-col gap-4">
            <div className="flex flex-col gap-1">
              <label htmlFor="forgive-amount" className="text-xs font-medium text-fg">
                Amount to forgive <span className="text-danger">*</span>
              </label>
              <input
                id="forgive-amount"
                type="number"
                min={1}
                max={penaltyOwed}
                value={amount}
                disabled={forgive.isPending}
                onChange={(e) => setAmount(e.target.value)}
                data-testid="forgive-amount-input"
                className="w-full rounded-lg bg-bg-muted px-4 py-2.5 text-fg placeholder:text-fg-muted ring-1 ring-border-subtle focus:outline-none focus:ring-2 focus:ring-brand disabled:opacity-50"
              />
            </div>

            <div className="flex flex-col gap-1">
              <label htmlFor="forgive-notes" className="text-xs font-medium text-fg">
                Notes <span className="text-danger">*</span>
              </label>
              <textarea
                id="forgive-notes"
                rows={3}
                value={notes}
                disabled={forgive.isPending}
                onChange={(e) => setNotes(e.target.value.slice(0, NOTES_MAX))}
                placeholder="Reason for forgiveness…"
                data-testid="forgive-notes-textarea"
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
                disabled={forgive.isPending}
              >
                Cancel
              </Button>
              <Button
                variant="primary"
                type="submit"
                disabled={!canSubmit}
                loading={forgive.isPending}
                data-testid="forgive-submit"
              >
                Forgive
              </Button>
            </div>
          </form>
        </div>
      </div>
    </>
  );
}
