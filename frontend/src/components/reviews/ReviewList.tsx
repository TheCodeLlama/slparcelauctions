"use client";

import { AlertCircle, MessageSquare } from "@/components/ui/icons";
import { EmptyState } from "@/components/ui/EmptyState";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { Pagination } from "@/components/ui/Pagination";
import { useUserReviews } from "@/hooks/useReviews";
import type { ReviewedRole } from "@/types/review";
import { ReviewCard } from "./ReviewCard";

export interface ReviewListProps {
  userId: number;
  role: ReviewedRole;
  /** 0-indexed current page. */
  page: number;
  onPageChange: (page: number) => void;
}

/**
 * Paginated list of {@link ReviewCard} for one user + role. Loading,
 * error, and role-specific empty states live here so {@link
 * ProfileReviewTabs} can focus on tab routing. Page number is driven by
 * the caller (URL-synced) so switching tabs preserves each tab's
 * independent pagination.
 */
export function ReviewList({ userId, role, page, onPageChange }: ReviewListProps) {
  const { data, isPending, isError } = useUserReviews(userId, role, page);

  if (isPending) {
    return (
      <div className="flex justify-center py-8">
        <LoadingSpinner label="Loading reviews..." />
      </div>
    );
  }

  if (isError) {
    return (
      <EmptyState
        icon={AlertCircle}
        headline="Could not load reviews"
        description="Please try again in a moment."
      />
    );
  }

  if (data.content.length === 0) {
    return (
      <EmptyState
        icon={MessageSquare}
        headline={
          role === "SELLER"
            ? "No reviews as seller yet"
            : "No reviews as buyer yet"
        }
      />
    );
  }

  return (
    <div className="flex flex-col gap-4" data-testid={`review-list-${role.toLowerCase()}`}>
      <ul className="flex flex-col gap-3">
        {data.content.map((review) => (
          <li key={review.id}>
            <ReviewCard review={review} />
          </li>
        ))}
      </ul>
      {data.totalPages > 1 && (
        <Pagination
          page={data.number}
          totalPages={data.totalPages}
          onPageChange={onPageChange}
        />
      )}
    </div>
  );
}
