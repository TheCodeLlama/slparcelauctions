"use client";

import { AlertTriangle } from "@/components/ui/icons";

export interface LeaderTermsBlockBannerProps {
  /** ISO-8601 timestamp when the leader accepted wallet ToS, or null. */
  leaderTermsAcceptedAt: string | null | undefined;
}

/**
 * Informational banner shown when the group leader has not yet accepted the
 * wallet Terms of Service. While the leader's acceptance is pending,
 * withdrawals cannot be processed (the L$ recipient's wallet can't accept
 * funds until ToS is agreed to).
 *
 * No CTA exists in the D scope — there is no UI flow to accept ToS on behalf
 * of the leader. The banner is purely informational and renders only when
 * {@code leaderTermsAcceptedAt} is null/undefined.
 */
export function LeaderTermsBlockBanner({
  leaderTermsAcceptedAt,
}: LeaderTermsBlockBannerProps) {
  if (leaderTermsAcceptedAt != null) return null;

  return (
    <div
      className="rounded-lg border border-warning bg-warning-bg p-4 flex gap-3"
      role="alert"
      data-testid="leader-terms-block-banner"
    >
      <AlertTriangle className="h-5 w-5 text-warning shrink-0 mt-0.5" aria-hidden="true" />
      <div>
        <h3 className="font-medium text-warning text-sm">
          Leader must accept Wallet Terms of Service
        </h3>
        <p className="text-sm text-fg-muted mt-1">
          The group leader has not yet accepted the SLParcels Wallet Terms of
          Service. Withdrawals are blocked until the leader accepts the terms by
          visiting their personal wallet page and completing the one-time
          agreement.
        </p>
      </div>
    </div>
  );
}
