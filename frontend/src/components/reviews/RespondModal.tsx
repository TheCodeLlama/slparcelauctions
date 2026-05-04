"use client";

import { useState } from "react";
import { Dialog, DialogPanel, DialogTitle } from "@headlessui/react";
import { Button } from "@/components/ui/Button";
import { isApiError } from "@/lib/api";
import { cn } from "@/lib/cn";
import { useRespondToReview } from "@/hooks/useReviews";

const MAX_LEN = 500;

export interface RespondModalProps {
  reviewPublicId: string;
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
export function RespondModal({ reviewPublicId, open, onClose }: RespondModalProps) {
  const [text, setText] = useState("");
  const mutation = useRespondToReview(reviewPublicId);

  // Local state resets automatically when the modal unmounts — callers
  // (ReviewCard, ReviewPanel) should mount us only when {@code open} is
  // true so a cancelled-then-reopened modal starts with a fresh
  // textarea.

  const trimmed = text.trim();
  const overLimit = text.length > MAX_LEN;
  const canSubmit = trimmed.length > 0 && !overLimit && !mutation.isPending;

  const submit = async () => {
    if (!canSubmit) return;
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
      // Non-409 errors surface via the hook's toast; leave the modal open
      // with the textarea preserved so the user can retry.
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
          className="w-full max-w-md flex flex-col gap-4 rounded-lg bg-bg-subtle p-6"
        >
          <DialogTitle className="text-base font-bold tracking-tight text-fg">
            Respond to this review
          </DialogTitle>
          <p className="text-xs text-fg-muted">
            Your response appears publicly beneath the review. You can only
            respond once — no edits.
          </p>
          <label className="flex flex-col gap-1">
            <span className="sr-only">Response text</span>
            <textarea
              rows={5}
              value={text}
              onChange={(e) => setText(e.target.value)}
              placeholder="Say something constructive…"
              data-testid="respond-modal-textarea"
              className="w-full resize-y rounded-lg bg-bg-subtle px-4 py-3 text-fg placeholder:text-fg-muted ring-1 ring-border-subtle transition-all focus:outline-none focus:ring-brand"
            />
          </label>
          <div
            data-testid="respond-modal-counter"
            className={cn(
              "self-end text-[11px] font-medium",
              overLimit ? "text-danger" : "text-fg-muted",
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
