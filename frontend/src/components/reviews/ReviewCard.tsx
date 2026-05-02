"use client";

import { useState } from "react";
import Link from "next/link";
import { Avatar } from "@/components/ui/Avatar";
import { Button } from "@/components/ui/Button";
import { CornerDownRight, Flag } from "@/components/ui/icons";
import { useAuth } from "@/lib/auth";
import { cn } from "@/lib/cn";
import { formatAbsoluteTime, formatRelativeTime } from "@/lib/time/relativeTime";
import type { ReviewDto } from "@/types/review";
import { FlagModal } from "./FlagModal";
import { RatingSummary } from "./RatingSummary";
import { RespondModal } from "./RespondModal";

export interface ReviewCardProps {
  review: ReviewDto;
  /**
   * When {@code true}, the auction-link pill below the review text is
   * hidden — useful on the escrow page where the review is already in
   * auction context.
   */
  hideAuctionLink?: boolean;
  className?: string;
}

/**
 * Label for the reviewee's response pill — mirrors the role of the
 * reviewee, not the reviewer (spec §8.2). A SELLER review is authored by
 * the buyer about the seller, so the response comes from the seller and
 * reads "Seller response".
 */
function responseLabel(reviewedRole: ReviewDto["reviewedRole"]): string {
  return reviewedRole === "SELLER" ? "Seller response" : "Buyer response";
}

export function ReviewCard({
  review,
  hideAuctionLink,
  className,
}: ReviewCardProps) {
  const { status, user } = useAuth();
  const viewerId = status === "authenticated" ? user.id : null;
  const [flagOpen, setFlagOpen] = useState(false);
  const [respondOpen, setRespondOpen] = useState(false);

  const viewerIsAuthor = viewerId !== null && viewerId === review.reviewerId;
  const viewerIsReviewee = viewerId !== null && viewerId === review.revieweeId;
  const canRespond = viewerIsReviewee && review.response === null;
  const canFlag = viewerId !== null && !viewerIsAuthor;

  const rating = review.rating ?? 0;
  const submittedAt = review.submittedAt ?? review.revealedAt;

  return (
    <article
      data-testid="review-card"
      data-review-id={review.id}
      className={cn(
        "flex flex-col gap-3 rounded-lg bg-bg-subtle p-4 ring-1 ring-border-subtle",
        className,
      )}
    >
      <header className="flex items-start gap-3">
        <Avatar
          src={review.reviewerAvatarUrl ?? undefined}
          alt={review.reviewerDisplayName}
          name={review.reviewerDisplayName}
          size="sm"
        />
        <div className="flex min-w-0 flex-1 flex-col">
          <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
            <span className="text-sm font-medium text-fg">
              {review.reviewerDisplayName}
            </span>
            {submittedAt && (
              <time
                dateTime={submittedAt}
                title={formatAbsoluteTime(submittedAt)}
                className="text-[11px] font-medium text-fg-muted"
              >
                {formatRelativeTime(submittedAt)}
              </time>
            )}
          </div>
          <div className="mt-1">
            <RatingSummary
              rating={rating > 0 ? rating : null}
              reviewCount={rating > 0 ? 1 : 0}
              size="sm"
              hideCountText
            />
          </div>
        </div>
        {canFlag && (
          <button
            type="button"
            onClick={() => setFlagOpen(true)}
            className="inline-flex shrink-0 items-center gap-1 rounded-lg px-2 py-1 text-[11px] font-medium text-fg-muted transition-colors hover:text-fg focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand"
            aria-label="Flag this review"
            data-testid="review-card-flag"
          >
            <Flag className="size-3.5" aria-hidden="true" />
            <span>Flag</span>
          </button>
        )}
      </header>

      {review.text && (
        <p
          className="whitespace-pre-wrap text-sm text-fg"
          data-testid="review-card-text"
        >
          {review.text}
        </p>
      )}

      {!hideAuctionLink && (
        <Link
          href={`/auction/${review.auctionId}`}
          className="inline-flex w-fit items-center gap-1 rounded-lg bg-bg-muted px-3 py-1 text-[11px] font-medium text-fg-muted transition-colors hover:text-brand"
          data-testid="review-card-auction-link"
        >
          <span className="truncate">{review.auctionTitle}</span>
        </Link>
      )}

      {review.response && (
        <div
          className="ml-6 flex flex-col gap-1 rounded-lg bg-bg-muted px-3 py-2"
          data-testid="review-card-response"
        >
          <div className="flex items-center gap-1 text-[11px] font-medium text-fg-muted">
            <CornerDownRight className="size-3.5" aria-hidden="true" />
            <span>{responseLabel(review.reviewedRole)}</span>
            <time
              dateTime={review.response.createdAt}
              title={formatAbsoluteTime(review.response.createdAt)}
              className="text-[11px] font-medium text-fg-muted"
            >
              · {formatRelativeTime(review.response.createdAt)}
            </time>
          </div>
          <p className="whitespace-pre-wrap text-sm text-fg">
            {review.response.text}
          </p>
        </div>
      )}

      {canRespond && (
        <div className="flex justify-end">
          <Button
            variant="secondary"
            size="sm"
            onClick={() => setRespondOpen(true)}
            data-testid="review-card-respond"
          >
            Respond
          </Button>
        </div>
      )}

      {flagOpen && (
        <FlagModal
          reviewId={review.id}
          open={flagOpen}
          onClose={() => setFlagOpen(false)}
        />
      )}
      {respondOpen && (
        <RespondModal
          reviewId={review.id}
          open={respondOpen}
          onClose={() => setRespondOpen(false)}
        />
      )}
    </article>
  );
}
