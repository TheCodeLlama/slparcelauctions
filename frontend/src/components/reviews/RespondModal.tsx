"use client";

import { useState } from "react";
import { Dialog, DialogPanel, DialogTitle } from "@headlessui/react";
import { Button } from "@/components/ui/Button";
import { FormError } from "@/components/ui/FormError";
import { ApiError, isApiError } from "@/lib/api";
import { cn } from "@/lib/cn";
import { useRespondToReview } from "@/hooks/useReviews";

const MAX_LEN = 500;

export interface RespondModalProps {
  reviewId: number;
  open: boolean;
  onClose: () => void;
}

/**
 * Modal form for the reviewee to post a single response to a revealed
 * review. One-shot (no editing, spec §15) — on success the cache
 * invalidation in {@link useRespondToReview} drives the card re-render
 * with the nested response pill. 409 "already responded" is handled in the
 * hook (via toast); we still close the modal here so the stale local state
 * does not linger.
 */
export function RespondModal({ reviewId, open, onClose }: RespondModalProps) {
  const [text, setText] = useState("");
  const [error, setError] = useState<string | null>(null);
  const mutation = useRespondToReview(reviewId);

  // Local state resets automatically when the modal unmounts — callers
  // (ReviewCard, ReviewPanel) should mount us only when {@code open} is
  // true so a cancelled-then-reopened modal starts with a fresh
  // textarea.

  const trimmed = text.trim();
  const overLimit = text.length > MAX_LEN;
  const canSubmit = trimmed.length > 0 && !overLimit && !mutation.isPending;

  const submit = async () => {
    if (!canSubmit) return;
    setError(null);
    try {
      await mutation.mutateAsync({ text: trimmed });
      onClose();
    } catch (e) {
      if (isApiError(e) && e.status === 409) {
        // Hook already fires the toast; close so the caller refetches and
        // renders the (already-submitted) response.
        onClose();
        return;
      }
      if (e instanceof ApiError) {
        setError(e.problem.detail ?? e.problem.title ?? "Could not respond.");
        return;
      }
      setError("Could not respond. Please try again.");
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
          data-testid="respond-modal"
          className="w-full max-w-md flex flex-col gap-4 rounded-default bg-surface-container-low p-6"
        >
          <DialogTitle className="text-title-lg text-on-surface">
            Respond to this review
          </DialogTitle>
          <p className="text-body-sm text-on-surface-variant">
            Your response appears publicly beneath the review. You can only
            respond once — no edits.
          </p>
          <FormError message={error ?? undefined} />
          <label className="flex flex-col gap-1">
            <span className="sr-only">Response text</span>
            <textarea
              rows={5}
              value={text}
              onChange={(e) => setText(e.target.value)}
              placeholder="Say something constructive…"
              data-testid="respond-modal-textarea"
              className="w-full resize-y rounded-default bg-surface-container-low px-4 py-3 text-on-surface placeholder:text-on-surface-variant ring-1 ring-outline-variant transition-all focus:outline-none focus:ring-primary"
            />
          </label>
          <div
            data-testid="respond-modal-counter"
            className={cn(
              "self-end text-label-sm",
              overLimit ? "text-error" : "text-on-surface-variant",
            )}
          >
            {text.length} / {MAX_LEN}
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
              data-testid="respond-modal-submit"
            >
              Post response
            </Button>
          </div>
        </DialogPanel>
      </div>
    </Dialog>
  );
}
