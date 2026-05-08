"use client";

import { CancellationHistorySection } from "@/components/dashboard/CancellationHistorySection";
import { SuspensionBanner } from "@/components/dashboard/SuspensionBanner";
import { PendingReviewsSection } from "@/components/reviews/PendingReviewsSection";
import { VerifiedOverview } from "@/components/user/VerifiedOverview";

/**
 * Verified-dashboard landing page. Section ordering reflects priority:
 *
 * <ol>
 *   <li>{@link SuspensionBanner} (Epic 08 sub-spec 2 §8.2) — surfaces a
 *       penalty / suspension / ban so the seller sees it before anything
 *       else. Returns {@code null} on a clean account.</li>
 *   <li>{@link PendingReviewsSection} (Epic 08 sub-spec 1 §8.4) — the
 *       review window closes, so the CTA is more urgent than identity
 *       maintenance.</li>
 *   <li>{@link VerifiedOverview} — long-form identity + profile chrome.</li>
 *   <li>{@link CancellationHistorySection} (Epic 08 sub-spec 2 §8.3) —
 *       informational, lowest priority. Renders an empty-state card
 *       when the seller has no cancellations on record.</li>
 * </ol>
 *
 * <p>Each section short-circuits independently when there is nothing to
 * show, so an established user with a clean record sees the same layout
 * they had before sub-spec 2 landed.
 */
export default function OverviewPage() {
  return (
    <div className="flex flex-col gap-8">
      <SuspensionBanner />
      <PendingReviewsSection />
      <VerifiedOverview />
      <CancellationHistorySection />
    </div>
  );
}
