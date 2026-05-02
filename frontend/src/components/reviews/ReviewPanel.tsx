"use client";

import { useMemo, useState } from "react";
import { Button } from "@/components/ui/Button";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { cn } from "@/lib/cn";
import { formatAbsoluteTime } from "@/lib/time/relativeTime";
import { useAuth } from "@/lib/auth";
import { useAuctionReviews, useSubmitReview } from "@/hooks/useReviews";
import type {
  AuctionReviewsResponse,
  ReviewDto,
} from "@/types/review";
import { RatingSummary } from "./RatingSummary";
import { ReviewCard } from "./ReviewCard";
import { StarSelector } from "./StarSelector";

/**
 * Derived state keys for {@link ReviewPanel}, mirroring spec §8.1 one-to-one
 * plus two viewer-scoped additions (`loading` for the in-flight envelope
 * fetch and `read-only` for anonymous / non-party viewers who land here via
 * the escrow-page link).
 */
export type ReviewPanelState =
  | "loading"
  | "submit"
  | "pending"
  | "revealed-both"
  | "revealed-one"
  | "window-closed-none"
  | "read-only";

/**
 * Spec §10: review text cap. Kept in sync with the backend DTO bean-validation
 * rule (see {@code ReviewSubmitRequest.text}). Mirrors the 500-char cap used
 * on the flag + respond modals so the UX feels consistent across the surface.
 */
const MAX_TEXT_LEN = 500;

/**
 * Pure state-derivation helper. Exported for unit tests — the component
 * itself memoises the call inside {@link ReviewPanel}. {@code isPartyAuthed}
 * is {@code true} when the viewer is logged in AND is either the buyer or
 * the seller for this auction (escrow page's auth + role gate upstream).
 * Anonymous or non-party viewers follow the {@code read-only} branch even
 * when {@code windowClosesAt} has elapsed — the "window closed" copy is
 * seller-/buyer-facing only.
 */
export function deriveReviewPanelState(
  envelope: AuctionReviewsResponse | undefined,
  isPartyAuthed: boolean,
  now: Date,
): ReviewPanelState {
  if (!envelope) return "loading";
  const { canReview, myPendingReview, reviews, windowClosesAt } = envelope;
  const windowClosed =
    windowClosesAt !== null && new Date(windowClosesAt) < now;
  if (canReview) return "submit";
  if (myPendingReview) return "pending";
  if (reviews.length >= 2) return "revealed-both";
  if (reviews.length === 1) return "revealed-one";
  if (isPartyAuthed && windowClosed) return "window-closed-none";
  return "read-only";
}

export interface ReviewPanelProps {
  auctionId: number;
  /**
   * When {@code true}, the viewer is the seller or the winner on this
   * auction — the escrow-page client enforces this upstream via the
   * server-side authz gate. Drives the read-only fallback vs. window-
   * closed copy in {@link deriveReviewPanelState}.
   */
  isParty: boolean;
  className?: string;
}

/**
 * Escrow-page review surface, rendered beneath {@code EscrowStepCard} once
 * escrow reaches {@code COMPLETED}. Fetches the viewer-scoped review
 * envelope via {@link useAuctionReviews} and collapses it into one of the
 * five spec-defined states (spec §8.1) plus a loading spinner and a
 * read-only fallback.
 *
 * <p>The {@code id="review-panel"} on the root element is load-bearing —
 * the dashboard pending-reviews card deep-links here via
 * {@code /auction/{id}/escrow#review-panel} and the browser's native
 * hash-scroll lands on the panel.
 */
export function ReviewPanel({
  auctionId,
  isParty,
  className,
}: ReviewPanelProps) {
  const { data: envelope, isLoading } = useAuctionReviews(auctionId);
  const session = useAuth();
  const isPartyAuthed = isParty && session.status === "authenticated";

  // Re-derive only when the envelope changes. {@code Date.now()} is fine —
  // the state transitions on windowClosesAt elapse only matter at grain of
  // minutes, and re-mounts from the WS-invalidation refetch carry a fresh
  // {@code now}.
  const state = useMemo<ReviewPanelState>(
    () => deriveReviewPanelState(envelope, isPartyAuthed, new Date()),
    [envelope, isPartyAuthed],
  );

  return (
    <section
      id="review-panel"
      data-testid="review-panel"
      data-state={state}
      aria-labelledby="review-panel-heading"
      className={cn(
        "flex flex-col gap-4 rounded-lg bg-bg-subtle p-6 ring-1 ring-border-subtle",
        className,
      )}
    >
      <header className="flex items-baseline justify-between">
        <h2
          id="review-panel-heading"
          className="text-base font-bold tracking-tight font-bold text-fg"
        >
          Reviews
        </h2>
        {envelope && envelope.reviews.length > 0 && (
          <span className="text-xs font-medium text-fg-muted">
            {envelope.reviews.length} of 2 submitted
          </span>
        )}
      </header>

      {(state === "loading" || isLoading) && (
        <div
          data-testid="review-panel-loading"
          className="flex min-h-[6rem] items-center justify-center"
        >
          <LoadingSpinner label="Loading reviews..." />
        </div>
      )}

      {state === "submit" && envelope && (
        <SubmitState auctionId={auctionId} envelope={envelope} />
      )}

      {state === "pending" && envelope?.myPendingReview && (
        <PendingState
          review={envelope.myPendingReview}
          windowClosesAt={envelope.windowClosesAt}
        />
      )}

      {(state === "revealed-both" || state === "revealed-one") && envelope && (
        <RevealedState
          envelope={envelope}
          viewerId={
            session.status === "authenticated" ? session.user.id : null
          }
        />
      )}

      {state === "window-closed-none" && (
        <p
          data-testid="review-panel-window-closed"
          className="text-sm text-fg-muted"
        >
          The review window for this auction has closed.
        </p>
      )}

      {state === "read-only" && (!envelope || envelope.reviews.length === 0) && (
        <p
          data-testid="review-panel-readonly"
          className="text-sm text-fg-muted"
        >
          No reviews have been published for this auction yet.
        </p>
      )}
    </section>
  );
}

// --------------------------------------------------------------------------
// Internal sub-states. Kept inline — each one is small enough that splitting
// would cost more than the one-shot compose saves, and the shared props
// (envelope / auctionId) flow naturally from the outer hook.
// --------------------------------------------------------------------------

function SubmitState({
  auctionId,
  envelope,
}: {
  auctionId: number;
  envelope: AuctionReviewsResponse;
}) {
  const mutation = useSubmitReview(auctionId);
  const [rating, setRating] = useState<number | null>(null);
  const [text, setText] = useState("");

  const overLimit = text.length > MAX_TEXT_LEN;
  const trimmed = text.trim();
  const canSubmit =
    rating !== null && !overLimit && !mutation.isPending;

  const submit = async () => {
    if (!canSubmit || rating === null) return;
    try {
      await mutation.mutateAsync({
        rating,
        ...(trimmed.length > 0 ? { text: trimmed } : {}),
      });
      // Cache invalidation in the hook drives the panel's next state — no
      // local optimism required.
    } catch {
      // Hook's {@code onError} already surfaced a toast.
    }
  };

  return (
    <div
      data-testid="review-panel-submit"
      className="flex flex-col gap-4"
    >
      <p className="text-sm text-fg">
        How was the other party? Your review stays hidden until they submit
        theirs or on{" "}
        <time
          dateTime={envelope.windowClosesAt ?? undefined}
          className="font-medium"
        >
          {envelope.windowClosesAt
            ? formatAbsoluteTime(envelope.windowClosesAt)
            : "the window close"}
        </time>
        .
      </p>

      <div className="flex flex-col gap-2">
        <span className="text-xs font-medium text-fg">
          Rating
        </span>
        <StarSelector
          value={rating}
          onChange={setRating}
          disabled={mutation.isPending}
        />
      </div>

      <label className="flex flex-col gap-1">
        <span className="text-xs font-medium text-fg">
          Your review (optional)
        </span>
        <textarea
          rows={4}
          value={text}
          onChange={(e) => setText(e.target.value)}
          disabled={mutation.isPending}
          placeholder="Share what went well — or what didn't."
          data-testid="review-panel-submit-text"
          className="w-full resize-y rounded-lg bg-bg-subtle px-4 py-3 text-fg placeholder:text-fg-muted ring-1 ring-border-subtle transition-all focus:outline-none focus:ring-brand"
        />
      </label>

      <div className="flex items-center justify-between gap-4">
        <span
          data-testid="review-panel-submit-counter"
          className={cn(
            "text-[11px] font-medium",
            overLimit ? "text-danger" : "text-fg-muted",
          )}
        >
          {text.length} / {MAX_TEXT_LEN}
        </span>
        <Button
          onClick={submit}
          disabled={!canSubmit}
          loading={mutation.isPending}
          data-testid="review-panel-submit-button"
        >
          Submit review
        </Button>
      </div>
    </div>
  );
}

function PendingState({
  review,
  windowClosesAt,
}: {
  review: ReviewDto;
  windowClosesAt: string | null;
}) {
  const rating = review.rating ?? 0;
  return (
    <div
      data-testid="review-panel-pending"
      className="flex flex-col gap-3"
    >
      <p className="text-sm text-fg">
        Your review has been submitted. It will appear when the other party
        submits theirs
        {windowClosesAt ? (
          <>
            {" "}or on{" "}
            <time
              dateTime={windowClosesAt}
              title={formatAbsoluteTime(windowClosesAt)}
              className="font-medium"
            >
              {formatAbsoluteTime(windowClosesAt)}
            </time>
            .
          </>
        ) : (
          <>.</>
        )}
      </p>

      <div
        className="flex flex-col gap-2 rounded-lg bg-bg-muted px-4 py-3"
        data-testid="review-panel-pending-card"
      >
        <div className="flex items-center justify-between">
          <span className="text-xs font-medium text-fg-muted">
            Your submission
          </span>
          <RatingSummary
            rating={rating > 0 ? rating : null}
            reviewCount={rating > 0 ? 1 : 0}
            size="sm"
            hideCountText
          />
        </div>
        {review.text && (
          <p
            className="whitespace-pre-wrap text-sm text-fg"
            data-testid="review-panel-pending-text"
          >
            {review.text}
          </p>
        )}
      </div>
    </div>
  );
}

function RevealedState({
  envelope,
  viewerId,
}: {
  envelope: AuctionReviewsResponse;
  viewerId: number | null;
}) {
  const reviews = envelope.reviews;
  // Spec §8.1 notes: on revealed-one, if the viewer never submitted a
  // review, show a subtle "your review window closed" note. Detect that
  // by the absence of a row authored by the viewer in {@code reviews}.
  const viewerAuthored =
    viewerId !== null &&
    reviews.some((r) => r.reviewerId === viewerId);
  const showWindowClosedNote =
    reviews.length === 1 && viewerId !== null && !viewerAuthored;

  return (
    <div
      data-testid="review-panel-revealed"
      className="flex flex-col gap-4 md:grid md:grid-cols-2"
    >
      {reviews.map((r) => (
        <ReviewCard key={r.id} review={r} hideAuctionLink />
      ))}
      {showWindowClosedNote && (
        <p
          data-testid="review-panel-window-closed-note"
          className="text-xs text-fg-muted md:col-span-2"
        >
          Your review window closed.
        </p>
      )}
    </div>
  );
}
