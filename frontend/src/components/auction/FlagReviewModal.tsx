"use client";

import { useState } from "react";
import { Modal } from "@/components/ui/Modal";
import { Button } from "@/components/ui/Button";
import { Flag } from "@/components/ui/icons";
import { flagReview } from "@/lib/api/reviews";
import type { ReviewFlagReason } from "@/types/review";
import { cn } from "@/lib/cn";

const REASONS: Array<{ value: ReviewFlagReason; label: string; hint: string }> = [
  {
    value: "FALSE_INFO",
    label: "Contains false information",
    hint: "The reviewer states facts about the transaction that didn't happen.",
  },
  {
    value: "ABUSIVE",
    label: "Abusive or harassing language",
    hint: "Personal attacks, slurs, or threats unrelated to the transaction.",
  },
  {
    value: "OFF_TOPIC",
    label: "Off-topic",
    hint: "Talks about something other than this auction or seller.",
  },
  {
    value: "SPAM",
    label: "Spam or self-promotion",
    hint: "Promotes another listing, account, or external service.",
  },
  {
    value: "OTHER",
    label: "Something else",
    hint: "A concern that doesn't fit the categories above.",
  },
];

export interface FlagReviewModalProps {
  open: boolean;
  onClose: () => void;
  reviewId: number;
  reviewSnippet?: string;
}

export function FlagReviewModal({
  open,
  onClose,
  reviewId,
  reviewSnippet,
}: FlagReviewModalProps) {
  const [reason, setReason] = useState<ReviewFlagReason | null>(null);
  const [elaboration, setElaboration] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [submitted, setSubmitted] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const reset = () => {
    setReason(null);
    setElaboration("");
    setSubmitting(false);
    setSubmitted(false);
    setError(null);
    onClose();
  };

  const handleSubmit = async () => {
    if (!reason) return;
    setSubmitting(true);
    setError(null);
    try {
      const trimmed = elaboration.trim();
      await flagReview(reviewId, {
        reason,
        ...(trimmed.length > 0 ? { elaboration: trimmed } : {}),
      });
      setSubmitted(true);
    } catch (err) {
      setError(
        err instanceof Error ? err.message : "Could not submit flag. Please try again.",
      );
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open={open}
      onClose={reset}
      title={submitted ? "Flag submitted" : "Flag this review"}
      footer={
        submitted ? (
          <Button variant="primary" onClick={reset}>
            Done
          </Button>
        ) : (
          <>
            <Button variant="tertiary" onClick={reset} disabled={submitting}>
              Cancel
            </Button>
            <Button
              variant="primary"
              disabled={!reason || submitting}
              loading={submitting}
              onClick={handleSubmit}
            >
              Submit flag
            </Button>
          </>
        )
      }
    >
      {submitted ? (
        <div className="text-center">
          <div className="mx-auto mb-3 grid size-12 place-items-center rounded-full bg-success-bg text-success">
            <Flag className="size-5" aria-hidden />
          </div>
          <div className="mb-2 text-base font-semibold text-fg">
            Thanks &mdash; we&apos;ve received your flag.
          </div>
          <div className="mx-auto max-w-sm text-sm leading-relaxed text-fg-muted">
            Trust &amp; safety reviews flagged content within 24 hours. The
            reviewer is not notified that you flagged this.
          </div>
        </div>
      ) : (
        <>
          {reviewSnippet && (
            <div className="mb-3 rounded-md border border-border-subtle bg-bg-subtle p-3 text-xs italic text-fg-muted">
              &ldquo;{reviewSnippet}&rdquo;
            </div>
          )}
          <div className="mb-3 text-sm text-fg-muted">
            Flag a review when it violates community guidelines. Flags are
            anonymous to the reviewer.
          </div>
          <div className="flex flex-col gap-2">
            {REASONS.map((r) => (
              <label
                key={r.value}
                className={cn(
                  "flex cursor-pointer items-start gap-2.5 rounded-md border p-3 transition-colors",
                  reason === r.value
                    ? "border-brand bg-brand-soft"
                    : "border-border bg-surface-raised hover:border-border",
                )}
              >
                <input
                  type="radio"
                  name="flag-reason"
                  value={r.value}
                  checked={reason === r.value}
                  onChange={() => setReason(r.value)}
                  className="mt-1 accent-brand"
                />
                <div>
                  <div className="text-sm font-medium text-fg">{r.label}</div>
                  <div className="mt-0.5 text-xs text-fg-muted">{r.hint}</div>
                </div>
              </label>
            ))}
          </div>
          <textarea
            value={elaboration}
            onChange={(e) => setElaboration(e.target.value)}
            placeholder="Optional context (won't be shared with the reviewer)…"
            rows={3}
            className="mt-3 w-full rounded-sm border border-border bg-surface-raised px-3 py-2 text-sm text-fg placeholder:text-fg-muted focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand"
          />
          {error && (
            <div className="mt-3 rounded-md border border-danger/40 bg-danger-bg p-2.5 text-xs text-danger">
              {error}
            </div>
          )}
        </>
      )}
    </Modal>
  );
}
