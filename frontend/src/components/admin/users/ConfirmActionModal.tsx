"use client";
import { useState, useEffect } from "react";
import { Button } from "@/components/ui/Button";

const NOTES_MAX = 1000;

type Props = {
  open: boolean;
  title: string;
  description?: string;
  confirmLabel: string;
  confirmVariant?: "primary" | "destructive";
  isPending?: boolean;
  onConfirm: (notes: string) => void;
  onClose: () => void;
};

export function ConfirmActionModal({
  open,
  title,
  description,
  confirmLabel,
  confirmVariant = "primary",
  isPending = false,
  onConfirm,
  onClose,
}: Props) {
  const [notes, setNotes] = useState("");

  useEffect(() => {
    if (!open) return;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setNotes("");
  }, [open]);

  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [onClose]);

  const canSubmit = notes.trim().length > 0 && !isPending;

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!canSubmit) return;
    onConfirm(notes.trim());
  }

  if (!open) return null;

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
        aria-label={title}
        data-testid="confirm-action-modal"
        className="fixed inset-0 z-50 flex items-center justify-center p-4"
      >
        <div
          className="w-full max-w-md rounded-lg bg-bg-subtle border border-border-subtle shadow-md p-6 flex flex-col gap-4"
          onClick={(e) => e.stopPropagation()}
        >
          <div className="flex items-start justify-between gap-2">
            <div>
              <h2 className="text-sm font-semibold text-fg">{title}</h2>
              {description && (
                <p className="mt-1 text-sm text-fg-muted">{description}</p>
              )}
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

          <form onSubmit={handleSubmit} className="flex flex-col gap-4">
            <div className="flex flex-col gap-1">
              <label htmlFor="action-notes" className="text-xs font-medium text-fg">
                Notes <span className="text-danger-flat">*</span>
              </label>
              <textarea
                id="action-notes"
                rows={4}
                value={notes}
                disabled={isPending}
                onChange={(e) => setNotes(e.target.value.slice(0, NOTES_MAX))}
                placeholder="Explain the reason for this action…"
                data-testid="action-notes-textarea"
                className="w-full resize-y rounded-lg bg-bg-muted px-4 py-3 text-fg placeholder:text-fg-muted ring-1 ring-border-subtle transition-all focus:outline-none focus:ring-2 focus:ring-brand disabled:opacity-50"
              />
              <div className="self-end text-[11px] font-medium text-fg-muted">
                {notes.length} / {NOTES_MAX}
              </div>
            </div>

            <div className="flex justify-end gap-2">
              <Button variant="secondary" type="button" onClick={onClose} disabled={isPending}>
                Cancel
              </Button>
              <Button
                variant={confirmVariant}
                type="submit"
                disabled={!canSubmit}
                loading={isPending}
                data-testid="confirm-action-submit"
              >
                {confirmLabel}
              </Button>
            </div>
          </form>
        </div>
      </div>
    </>
  );
}
