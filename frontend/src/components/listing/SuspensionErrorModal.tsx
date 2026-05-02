"use client";

import Link from "next/link";
import { Dialog, DialogPanel, DialogTitle } from "@headlessui/react";
import { Button } from "@/components/ui/Button";
import type { SuspensionReasonCode } from "@/types/cancellation";

export interface SuspensionErrorModalProps {
  /**
   * Structured {@code code} field on the ProblemDetail returned by the
   * backend's {@code SellerSuspendedException} 403 handler. Drives the
   * focused copy variant. {@code null} closes the modal — the wizard
   * uses this for both initial state and dismissal.
   */
  code: SuspensionReasonCode | null;
  onClose: () => void;
}

/**
 * Title + body copy for each suspension reason. Mirrors the spec §8.4
 * focused-error copy; the dashboard banner carries the same wording for
 * the same reason. Source-of-truth for the {@code code} → copy mapping
 * lives here so a future copy edit is a single-file diff.
 */
const COPY: Record<
  SuspensionReasonCode,
  { title: string; body: string; ctaLabel: string }
> = {
  PENALTY_OWED: {
    title: "Penalty owed",
    body: "You have an outstanding penalty balance. Pay at any SLPA terminal to resume listing.",
    ctaLabel: "Go to dashboard",
  },
  TIMED_SUSPENSION: {
    title: "Listing temporarily suspended",
    body: "Your listing privileges are temporarily suspended. The dashboard banner shows when they will resume.",
    ctaLabel: "Go to dashboard",
  },
  PERMANENT_BAN: {
    title: "Listing privileges revoked",
    body: "Your listing privileges have been permanently suspended. Contact support to request a review.",
    ctaLabel: "Go to dashboard",
  },
};

/**
 * Surfaces the structured 403 from {@code SellerSuspendedException} as a
 * focused error modal during the listing wizard's submit step (Epic 08
 * sub-spec 2 §8.4). Routes on the {@code code} field of the
 * ProblemDetail — the wizard's submit handler reads {@code e.problem.code}
 * and forwards it here. The link goes back to the dashboard so the
 * seller can read the full {@code SuspensionBanner} copy in context.
 *
 * <p>Returns {@code null} when {@code code} is {@code null} — the wizard
 * keeps the modal mounted so the open/close transition is smooth, and
 * dismissal is a {@code setSuspensionError(null)} on the parent.
 */
export function SuspensionErrorModal({
  code,
  onClose,
}: SuspensionErrorModalProps) {
  const open = code !== null;
  // When closing, copy is no longer needed — fall back to PENALTY_OWED
  // so the type narrowing stays clean. The Dialog's `open` prop drives
  // visibility regardless.
  const copy = COPY[code ?? "PENALTY_OWED"];

  return (
    <Dialog open={open} onClose={onClose} className="relative z-50">
      <div
        className="fixed inset-0 bg-inverse-surface/40 backdrop-blur-sm"
        aria-hidden="true"
      />
      <div className="fixed inset-0 flex items-center justify-center p-4">
        <DialogPanel
          className="w-full max-w-md flex flex-col gap-4 rounded-lg bg-bg-subtle p-6"
          data-testid="suspension-error-modal"
          data-code={code ?? undefined}
        >
          <DialogTitle className="text-base font-bold tracking-tight text-fg">
            {copy.title}
          </DialogTitle>
          <p className="text-sm text-fg-muted">{copy.body}</p>
          <div className="flex justify-end gap-2">
            <Button variant="secondary" onClick={onClose}>
              Dismiss
            </Button>
            <Link href="/dashboard">
              <Button variant="primary">{copy.ctaLabel}</Button>
            </Link>
          </div>
        </DialogPanel>
      </div>
    </Dialog>
  );
}
