"use client";

import { useCallback, useEffect, useRef } from "react";
import { useRouter } from "next/navigation";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import type { EscrowChipRole } from "@/components/escrow/EscrowChip";
import type { AuctionTopicEnvelope } from "@/types/auction";
import { getEscrowStatus } from "@/lib/api/escrow";
import { isApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import {
  useConnectionState,
  useStompSubscription,
} from "@/lib/ws/hooks";
import { EscrowPageLayout } from "@/components/escrow/EscrowPageLayout";
import { EscrowPageHeader } from "@/components/escrow/EscrowPageHeader";
import { EscrowPageSkeleton } from "@/components/escrow/EscrowPageSkeleton";
import { EscrowPageError } from "@/components/escrow/EscrowPageError";
import { EscrowPageEmpty } from "@/components/escrow/EscrowPageEmpty";
import { EscrowStepper } from "@/components/escrow/EscrowStepper";
import { EscrowStepCard } from "@/components/escrow/EscrowStepCard";
import { ReviewPanel } from "@/components/reviews/ReviewPanel";
import { SellerEvidencePanel } from "@/components/escrow/SellerEvidencePanel";
import { ReconnectingBanner } from "@/components/auction/ReconnectingBanner";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";

export interface EscrowPageClientProps {
  auctionPublicId: string;
  /**
   * Seller public id sourced from the server-side auction fetch in the RSC
   * shell. Used with the authenticated user's publicId to derive the viewer's
   * role — `seller` when the ids match, `winner` otherwise (the escrow
   * endpoint's 200/403 gate guarantees only seller and winner reach this
   * client).
   */
  sellerPublicId: string;
}

export const escrowKey = (publicId: string) => ["escrow", publicId] as const;

/**
 * Client shell for the escrow page.
 *
 * Runs the authenticated-viewer gate (unauthenticated → /login redirect
 * with returnTo), derives the caller's role from the server-seeded
 * `sellerId` against `useAuth()`, seeds `useQuery(["escrow", id])` via
 * {@link getEscrowStatus}, subscribes to `/topic/auction/{id}` for the
 * nine escrow envelopes (coarse cache-invalidation signals per sub-spec
 * 1 §8), and reconciles missed envelopes via REST invalidation on the
 * reconnecting → connected edge (mirror of {@code AuctionDetailClient}).
 *
 * Retries are disabled on the escrow query so 404 / 403 surface
 * immediately rather than spinning through React Query's default
 * exponential backoff.
 *
 * See sub-spec 2 §5 (page composition), §8 (WS strategy), §15 (state
 * matrix). Authz-at-the-gate: the backend already rejects non-seller /
 * non-winner callers with 403; the role derivation here is purely a UI
 * concern.
 */
export function EscrowPageClient({ auctionPublicId, sellerPublicId }: EscrowPageClientProps) {
  const queryClient = useQueryClient();
  const connectionState = useConnectionState();
  const session = useAuth();
  const router = useRouter();
  const wasReconnectingRef = useRef(false);

  // Unauthenticated users get redirected to /login with returnTo so the
  // post-login hop lands them back here. Matches the RequireAuth pattern
  // used by /dashboard — client-side guard (not RSC) because the
  // auth stack lives entirely in the browser.
  useEffect(() => {
    if (session.status === "unauthenticated") {
      const returnTo = encodeURIComponent(`/auction/${auctionPublicId}/escrow`);
      router.replace(`/login?next=${returnTo}`);
    }
  }, [session.status, auctionPublicId, router]);

  const isAuthenticated = session.status === "authenticated";

  const { data: escrow, isLoading, error } = useQuery({
    queryKey: escrowKey(auctionPublicId),
    queryFn: () => getEscrowStatus(auctionPublicId),
    // Gate the fetch on the authed state so anonymous callers don't fire a
    // 401/403 on the way to being redirected.
    enabled: isAuthenticated,
    refetchOnWindowFocus: true,
    // Let 404 / 403 surface immediately; no silent retries on auth failures.
    retry: false,
  });

  useStompSubscription<AuctionTopicEnvelope>(
    `/topic/auction/${auctionPublicId}`,
    useCallback(
      (env) => {
        // Escrow envelopes are coarse cache-invalidation signals; the
        // payload fields aren't read. A REST refetch reconstructs the
        // authoritative state.
        if (env.type.startsWith("ESCROW_")) {
          queryClient.invalidateQueries({ queryKey: escrowKey(auctionPublicId) });
        } else if (env.type === "REVIEW_REVEALED") {
          // Epic 08 sub-spec 1 §7.2: refresh the {@code useAuctionReviews}
          // envelope so the ReviewPanel transitions from pending → revealed
          // (or revealed-one → revealed-both) without a page reload. The
          // query key mirrors {@code reviewsKeys.auction} in useReviews.ts.
          queryClient.invalidateQueries({
            queryKey: ["reviews", "auction", auctionPublicId],
          });
        }
      },
      [auctionPublicId, queryClient],
    ),
  );

  // Reconnect reconcile — mirror of the AuctionDetailClient pattern. On the
  // first `connected` edge after any non-connected state, invalidate the
  // escrow query so any envelope missed during the outage lands via the
  // refetch. The ref flip happens AFTER invalidation so a rapid re-
  // disconnect doesn't lose the pending-reconcile signal.
  useEffect(() => {
    const status = connectionState.status;
    if (status === "connected" && wasReconnectingRef.current) {
      queryClient.invalidateQueries({ queryKey: escrowKey(auctionPublicId) });
      wasReconnectingRef.current = false;
    }
    if (status === "reconnecting" || status === "error") {
      wasReconnectingRef.current = true;
    }
  }, [connectionState.status, queryClient, auctionPublicId]);

  // Loading and redirect-in-flight states.
  if (session.status === "loading") {
    return <LoadingSpinner label="Loading..." />;
  }
  if (session.status === "unauthenticated") {
    // Redirect is in-flight via the effect above. Render null to avoid
    // flashing the protected surface before the navigation resolves.
    return null;
  }

  // Derive the viewer's role from the server-seeded sellerPublicId. Non-seller
  // authenticated callers must be the winner — the escrow endpoint's 403
  // gate rejects everyone else before this branch runs (the 403 lands in
  // `error` below and surfaces via EscrowPageError).
  const role: EscrowChipRole =
    session.user.publicId === sellerPublicId ? "seller" : "winner";

  return (
    <EscrowPageLayout auctionPublicId={auctionPublicId}>
      {isLoading && <EscrowPageSkeleton />}
      {error && isApiError(error) && error.status === 404 && (
        <EscrowPageEmpty auctionPublicId={auctionPublicId} />
      )}
      {error && !(isApiError(error) && error.status === 404) && (
        <EscrowPageError error={error as Error} />
      )}
      {escrow && (
        <>
          <EscrowPageHeader escrow={escrow} role={role} />
          <EscrowStepper escrow={escrow} />
          <EscrowStepCard escrow={escrow} role={role} />
          {escrow.state === "DISPUTED" && role === "seller" && (
            <SellerEvidencePanel auctionPublicId={auctionPublicId} />
          )}
          {escrow.state === "COMPLETED" && (
            <ReviewPanel auctionPublicId={auctionPublicId} isParty />
          )}
        </>
      )}
      <ReconnectingBanner state={connectionState} />
    </EscrowPageLayout>
  );
}
