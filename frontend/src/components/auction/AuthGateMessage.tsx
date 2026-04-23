"use client";

import Link from "next/link";
import { Button } from "@/components/ui/Button";

export type AuthGateKind = "unauth" | "unverified" | "seller";

export interface AuthGateMessageProps {
  kind: AuthGateKind;
  /**
   * Required for the {@code "unauth"} variant — embedded as the {@code
   * next} query param so login bounces the user back to this auction
   * page. The repo-wide auth flow uses {@code /login?next=…} (see
   * {@code RequireAuth}, {@code lib/api.ts}); we match that here rather
   * than the {@code /sign-in?returnTo=} shape in the spec draft.
   */
  auctionId?: number;
}

/**
 * Non-bidder variants of the BidPanel — the three cases where we render
 * an informational card instead of the bid + proxy forms. Sits inside
 * the sticky sidebar slot so the layout stays stable regardless of
 * viewer identity.
 *
 * Variants mirror spec §9:
 * <ul>
 *   <li>{@code unauth}     — anonymous, prompt to sign in</li>
 *   <li>{@code unverified} — signed in but no SL-verified avatar</li>
 *   <li>{@code seller}     — viewer owns this auction</li>
 * </ul>
 */
export function AuthGateMessage({ kind, auctionId }: AuthGateMessageProps) {
  if (kind === "unauth") {
    const next =
      auctionId != null
        ? `?next=${encodeURIComponent(`/auction/${auctionId}`)}`
        : "";
    return (
      <div
        data-testid="auth-gate-message"
        data-kind="unauth"
        className="flex flex-col gap-3 rounded-xl bg-surface-container-lowest p-6 shadow-soft"
      >
        <h2 className="text-title-md text-on-surface">
          Sign in to bid on this auction
        </h2>
        <p className="text-body-sm text-on-surface-variant">
          You need an account to place bids. Signing in takes a moment.
        </p>
        <Link href={`/login${next}`}>
          <Button variant="primary" fullWidth>
            Sign in
          </Button>
        </Link>
      </div>
    );
  }

  if (kind === "unverified") {
    return (
      <div
        data-testid="auth-gate-message"
        data-kind="unverified"
        className="flex flex-col gap-3 rounded-xl bg-surface-container-lowest p-6 shadow-soft"
      >
        <h2 className="text-title-md text-on-surface">
          Verify your Second Life avatar to bid
        </h2>
        <p className="text-body-sm text-on-surface-variant">
          Only SL-verified users can place bids. Head to your dashboard to
          finish verification.
        </p>
        <Link href="/dashboard/overview">
          <Button variant="primary" fullWidth>
            Go to verification
          </Button>
        </Link>
      </div>
    );
  }

  // seller variant — read-only "your auction" callout
  return (
    <div
      data-testid="auth-gate-message"
      data-kind="seller"
      className="flex flex-col gap-2 rounded-xl bg-surface-container-lowest p-6 shadow-soft"
    >
      <h2 className="text-title-md text-on-surface">This is your auction</h2>
      <p className="text-body-sm text-on-surface-variant">
        You cannot bid on your own listing. Watch the current bid and bidder
        count update in real time as bids come in.
      </p>
    </div>
  );
}
