"use client";

import { useState } from "react";
import { Dialog, DialogPanel, DialogTitle } from "@headlessui/react";
import { Button } from "@/components/ui/Button";

/**
 * Session-scoped dismissal storage. Reads and writes happen under a
 * {@code typeof window === "undefined"} guard so SSR (server-components
 * and test harness paths that run without a window) never throws.
 * {@code sessionStorage} (not {@code localStorage}) because the
 * don't-ask-again semantics are for the current tab only — reopening
 * the page should re-prompt the user.
 */
export function isConfirmDismissed(key: string | undefined): boolean {
  if (!key) return false;
  if (typeof window === "undefined") return false;
  try {
    return window.sessionStorage.getItem(key) === "1";
  } catch {
    return false;
  }
}

function setConfirmDismissed(key: string): void {
  if (typeof window === "undefined") return;
  try {
    window.sessionStorage.setItem(key, "1");
  } catch {
    // storage quota or disabled — silently no-op, user will re-see the
    // confirm next time
  }
}

export interface ConfirmBidDialogProps {
  isOpen: boolean;
  title: string;
  message: string;
  confirmLabel?: string;
  cancelLabel?: string;
  onConfirm: () => void;
  onClose: () => void;
  /**
   * When set, renders a "Don't ask again" checkbox that writes the key
   * into {@code sessionStorage} on confirm if the box is checked.
   * Callers short-circuit the dialog with {@link isConfirmDismissed}
   * before firing.
   */
  dontAskAgainKey?: string;
}

/**
 * Reusable confirmation dialog for bid-panel flows (large bid / buy-now
 * overspend / proxy max > buyNow). Uses Headless UI's {@code Dialog}
 * primitive — same pattern as {@code CancelListingModal}.
 *
 * The caller owns the decision to open — this component does NOT read
 * {@link isConfirmDismissed} itself. That lets the place-bid form
 * distinguish "dismiss the large-bid dialog" (session-scoped) from "buy-now
 * overspend" (always prompts regardless of prior dismissal).
 */
export function ConfirmBidDialog({
  isOpen,
  title,
  message,
  confirmLabel = "Confirm",
  cancelLabel = "Cancel",
  onConfirm,
  onClose,
  dontAskAgainKey,
}: ConfirmBidDialogProps) {
  const [dontAskAgain, setDontAskAgain] = useState(false);

  const handleConfirm = () => {
    if (dontAskAgainKey && dontAskAgain) {
      setConfirmDismissed(dontAskAgainKey);
    }
    onConfirm();
  };

  return (
    <Dialog open={isOpen} onClose={onClose} className="relative z-50">
      <div
        className="fixed inset-0 bg-inverse-surface/40 backdrop-blur-sm"
        aria-hidden="true"
      />
      <div className="fixed inset-0 flex items-center justify-center p-4">
        <DialogPanel
          data-testid="confirm-bid-dialog"
          className="w-full max-w-md flex flex-col gap-4 rounded-default bg-surface-container-low p-6"
        >
          <DialogTitle className="text-title-lg text-on-surface">
            {title}
          </DialogTitle>
          <p className="text-body-md text-on-surface">{message}</p>
          {dontAskAgainKey ? (
            <label className="flex items-center gap-2 text-body-sm text-on-surface-variant">
              <input
                type="checkbox"
                checked={dontAskAgain}
                onChange={(e) => setDontAskAgain(e.target.checked)}
                data-testid="confirm-bid-dialog-dont-ask-again"
                className="size-4 rounded border-outline"
              />
              Don&apos;t ask again this session
            </label>
          ) : null}
          <div className="flex justify-end gap-2">
            <Button
              variant="secondary"
              onClick={onClose}
              data-testid="confirm-bid-dialog-cancel"
            >
              {cancelLabel}
            </Button>
            <Button
              variant="primary"
              onClick={handleConfirm}
              data-testid="confirm-bid-dialog-confirm"
            >
              {confirmLabel}
            </Button>
          </div>
        </DialogPanel>
      </div>
    </Dialog>
  );
}
