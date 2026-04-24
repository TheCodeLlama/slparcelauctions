"use client";

import { useState } from "react";
import { Dialog, DialogPanel, DialogTitle } from "@headlessui/react";
import { Button } from "@/components/ui/Button";
import { isApiError } from "@/lib/api";
import { cn } from "@/lib/cn";
import { useFlagReview } from "@/hooks/useReviews";
import type { ReviewFlagReason } from "@/types/review";

const MAX_LEN = 500;

const REASONS: Array<{ code: ReviewFlagReason; label: string }> = [
  { code: "SPAM", label: "Spam" },
  { code: "ABUSIVE", label: "Abusive" },
  { code: "OFF_TOPIC", label: "Off-topic" },
  { code: "FALSE_INFO", label: "False information" },
  { code: "OTHER", label: "Other" },
];

export interface FlagModalProps {
  reviewId: number;
  open: boolean;
  onClose: () => void;
}

/**
 * Modal form for any non-author to flag a review. Reason is a radio group
 * (radiogroup pattern → arrow-key navigation from the browser's native
 * radio handling); elaboration is a 500-char textarea, required iff
 * {@code reason === "OTHER"} (mirrors the backend
 * {@code ElaborationRequiredWhenOtherValidator} so the 422 path stays a
 * fallback). 409 duplicate-flag is handled in {@link useFlagReview} via
 * toast.
 */
export function FlagModal({ reviewId, open, onClose }: FlagModalProps) {
  const [reason, setReason] = useState<ReviewFlagReason | null>(null);
  const [elaboration, setElaboration] = useState("");
  const mutation = useFlagReview(reviewId);

  // Local state resets on unmount — callers (ReviewCard) mount this modal
  // only when {@code open} is true so a cancel-then-reopen sequence starts
  // from a clean slate without a reset effect.

  const overLimit = elaboration.length > MAX_LEN;
  const elaborationRequired = reason === "OTHER";
  const elaborationMissing =
    elaborationRequired && elaboration.trim().length === 0;
  const canSubmit =
    reason !== null && !overLimit && !elaborationMissing && !mutation.isPending;

  const submit = async () => {
    if (!canSubmit || reason === null) return;
    const trimmed = elaboration.trim();
    try {
      await mutation.mutateAsync({
        reason,
        ...(trimmed.length > 0 ? { elaboration: trimmed } : {}),
      });
      onClose();
    } catch (e) {
      if (isApiError(e) && e.status === 409) {
        // Hook fires the "Already flagged" toast; we still close so the UI
        // resets.
        onClose();
        return;
      }
      // Non-409 errors surface via the hook's toast; leave the modal open
      // with the form preserved so the user can retry.
    }
  };

  return (
    <Dialog open={open} onClose={onClose} className="relative z-50">
      <div
        className="fixed inset-0 bg-inverse-surface/40 backdrop-blur-sm"
        aria-hidden="true"
      />
      <div className="fixed inset-0 flex items-center justify-center p-4">
        <DialogPanel
          data-testid="flag-modal"
          className="w-full max-w-md flex flex-col gap-4 rounded-default bg-surface-container-low p-6"
        >
          <DialogTitle className="text-title-lg text-on-surface">
            Flag this review
          </DialogTitle>
          <p className="text-body-sm text-on-surface-variant">
            Tell us why this review needs moderator attention. Flagging is
            confidential — the reviewer is not notified.
          </p>

          <fieldset
            className="flex flex-col gap-2"
            data-testid="flag-modal-reasons"
          >
            <legend className="text-label-md font-medium text-on-surface">
              Reason
            </legend>
            {REASONS.map((r) => (
              <label
                key={r.code}
                className="flex items-center gap-2 text-body-md text-on-surface"
              >
                <input
                  type="radio"
                  name={`flag-reason-${reviewId}`}
                  value={r.code}
                  checked={reason === r.code}
                  onChange={() => setReason(r.code)}
                  className="size-4 accent-primary"
                  data-testid={`flag-modal-reason-${r.code}`}
                />
                <span>{r.label}</span>
              </label>
            ))}
          </fieldset>

          <label className="flex flex-col gap-1">
            <span className="text-label-md text-on-surface">
              {elaborationRequired
                ? "Please elaborate (required)"
                : "Additional details (optional)"}
            </span>
            <textarea
              rows={4}
              value={elaboration}
              onChange={(e) => setElaboration(e.target.value)}
              placeholder="What should the moderator know?"
              data-testid="flag-modal-elaboration"
              className="w-full resize-y rounded-default bg-surface-container-low px-4 py-3 text-on-surface placeholder:text-on-surface-variant ring-1 ring-outline-variant transition-all focus:outline-none focus:ring-primary"
            />
          </label>
          <div
            data-testid="flag-modal-counter"
            className={cn(
              "self-end text-label-sm",
              overLimit ? "text-error" : "text-on-surface-variant",
            )}
          >
            {elaboration.length} / {MAX_LEN}
          </div>

          <div className="flex justify-end gap-2">
            <Button
              variant="secondary"
              onClick={onClose}
              disabled={mutation.isPending}
            >
              Cancel
            </Button>
            <Button
              onClick={submit}
              disabled={!canSubmit}
              loading={mutation.isPending}
              data-testid="flag-modal-submit"
            >
              Flag review
            </Button>
          </div>
        </DialogPanel>
      </div>
    </Dialog>
  );
}
