"use client";
import { useState, useEffect } from "react";
import { Button } from "@/components/ui/Button";
import { useAdjustBalance } from "@/hooks/admin/useAdminWallet";

const NOTES_MAX = 1000;

type Props = {
  open: boolean;
  publicId: string;
  balanceLindens: number;
  reservedLindens: number;
  onClose: () => void;
};

export function AdjustBalanceModal({
  open,
  publicId,
  balanceLindens,
  reservedLindens,
  onClose,
}: Props) {
  const [amount, setAmount] = useState<string>("");
  const [notes, setNotes] = useState<string>("");
  const [override, setOverride] = useState<boolean>(false);
  const adjust = useAdjustBalance(publicId);

  useEffect(() => {
    if (!open) return;
    setAmount("");
    setNotes("");
    setOverride(false);
  }, [open]);

  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [onClose]);

  if (!open) return null;

  const amountNum = parseInt(amount, 10);
  const amountValid = Number.isFinite(amountNum) && amountNum !== 0;
  const projectedBalance = amountValid ? balanceLindens + amountNum : balanceLindens;
  const wouldBreachFloor = projectedBalance < reservedLindens;
  const canSubmit =
    amountValid &&
    notes.trim().length > 0 &&
    !adjust.isPending &&
    (!wouldBreachFloor || override);

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!canSubmit) return;
    adjust.mutate(
      { amount: amountNum, notes: notes.trim(), overrideReservationFloor: override },
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
        aria-label="Adjust wallet balance"
        data-testid="adjust-balance-modal"
        className="fixed inset-0 z-50 flex items-center justify-center p-4"
      >
        <div
          className="w-full max-w-md rounded-lg bg-bg-subtle border border-border-subtle shadow-md p-6 flex flex-col gap-4"
          onClick={(e) => e.stopPropagation()}
        >
          <div className="flex items-start justify-between gap-2">
            <div>
              <h2 className="text-sm font-semibold text-fg">Adjust balance</h2>
              <p className="mt-1 text-sm text-fg-muted">
                Writes a manual ADJUSTMENT ledger entry. Positive credits, negative debits.
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
              <span>Current balance</span>
              <span className="font-mono text-fg">L$ {balanceLindens.toLocaleString()}</span>
            </div>
            <div className="flex justify-between">
              <span>Reserved (bid floor)</span>
              <span className="font-mono text-fg">L$ {reservedLindens.toLocaleString()}</span>
            </div>
            {amountValid && (
              <div className="flex justify-between border-t border-border-subtle/50 mt-1 pt-1">
                <span>Projected balance</span>
                <span className={`font-mono ${wouldBreachFloor ? "text-danger" : "text-fg"}`}>
                  L$ {projectedBalance.toLocaleString()}
                </span>
              </div>
            )}
          </div>

          <form onSubmit={handleSubmit} className="flex flex-col gap-4">
            <div className="flex flex-col gap-1">
              <label htmlFor="adjust-amount" className="text-xs font-medium text-fg">
                Amount (signed L$) <span className="text-danger">*</span>
              </label>
              <input
                id="adjust-amount"
                type="number"
                value={amount}
                disabled={adjust.isPending}
                onChange={(e) => setAmount(e.target.value)}
                placeholder="e.g. 1000 or -500"
                data-testid="adjust-amount-input"
                className="w-full rounded-lg bg-bg-muted px-4 py-2.5 text-fg placeholder:text-fg-muted ring-1 ring-border-subtle focus:outline-none focus:ring-2 focus:ring-brand disabled:opacity-50"
              />
            </div>

            <div className="flex flex-col gap-1">
              <label htmlFor="adjust-notes" className="text-xs font-medium text-fg">
                Notes <span className="text-danger">*</span>
              </label>
              <textarea
                id="adjust-notes"
                rows={3}
                value={notes}
                disabled={adjust.isPending}
                onChange={(e) => setNotes(e.target.value.slice(0, NOTES_MAX))}
                placeholder="Reason for the adjustment…"
                data-testid="adjust-notes-textarea"
                className="w-full resize-y rounded-lg bg-bg-muted px-4 py-3 text-fg placeholder:text-fg-muted ring-1 ring-border-subtle focus:outline-none focus:ring-2 focus:ring-brand disabled:opacity-50"
              />
              <div className="self-end text-[11px] font-medium text-fg-muted">
                {notes.length} / {NOTES_MAX}
              </div>
            </div>

            {wouldBreachFloor && amountValid && (
              <label className="flex items-start gap-2 text-[11px] text-danger">
                <input
                  type="checkbox"
                  checked={override}
                  onChange={(e) => setOverride(e.target.checked)}
                  data-testid="adjust-override-checkbox"
                  className="mt-0.5"
                />
                <span>
                  Override reservation floor — I understand this will leave the user&apos;s
                  bid reservations under-funded.
                </span>
              </label>
            )}

            <div className="flex justify-end gap-2">
              <Button
                variant="secondary"
                type="button"
                onClick={onClose}
                disabled={adjust.isPending}
              >
                Cancel
              </Button>
              <Button
                variant="primary"
                type="submit"
                disabled={!canSubmit}
                loading={adjust.isPending}
                data-testid="adjust-submit"
              >
                Adjust balance
              </Button>
            </div>
          </form>
        </div>
      </div>
    </>
  );
}
