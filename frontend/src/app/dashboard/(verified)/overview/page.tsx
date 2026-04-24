"use client";

import { PendingReviewsSection } from "@/components/reviews/PendingReviewsSection";
import { VerifiedOverview } from "@/components/user/VerifiedOverview";

/**
 * Verified-dashboard landing page. Renders the time-bound
 * {@link PendingReviewsSection} at the top — reviewing a completed sale is
 * a higher-priority CTA than identity / profile maintenance because the
 * review window closes. The section collapses to {@code null} when the
 * viewer has no pending reviews, so established users see their normal
 * identity / profile layout unchanged.
 */
export default function OverviewPage() {
  return (
    <div className="flex flex-col gap-8">
      <PendingReviewsSection />
      <VerifiedOverview />
    </div>
  );
}
