import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { getAuction } from "@/lib/api/auctions";
import { isApiError } from "@/lib/api";
import { DisputeFormClient } from "./DisputeFormClient";

export const metadata: Metadata = { title: "File a dispute" };

interface Props {
  params: Promise<{ publicId: string }>;
}

/**
 * Dispute-file subroute — server component shell.
 *
 * Mirrors the escrow page RSC shell: validates the route param, fetches
 * the auction so a bad id surfaces as a proper 404 before the client
 * mounts, and seeds {@code sellerId} into the client for role
 * derivation. Handoff goes to {@link DisputeFormClient} which owns the
 * authenticated-viewer gate, the escrow {@code useQuery} seed, the
 * terminal-state branch, and the dispute {@code useMutation}.
 *
 * Auth is intentionally NOT checked here. The frontend auth stack lives
 * in the browser (session cookie + refresh flow + React Query cache),
 * so the RSC shell has no synchronous access to the caller's identity —
 * the authenticated-viewer redirect runs in the client (mirror of the
 * {@code EscrowPageClient}). Server-side authz is enforced by the
 * backend on the dispute endpoint itself, which returns 403 to anyone
 * other than the seller or the winner.
 *
 * See sub-spec 2 §5 (composition) and §7.1 (authz gate).
 */
export default async function DisputePage({ params }: Props) {
  const { publicId } = await params;
  if (!publicId) notFound();

  // Fetch the auction so a stale link / wrong id surfaces as a proper
  // 404 before the client mounts. Other errors propagate to the Next.js
  // error boundary.
  let auction;
  try {
    auction = await getAuction(publicId);
  } catch (err) {
    if (isApiError(err) && err.status === 404) notFound();
    throw err;
  }
  if (!auction) notFound();

  return (
    <DisputeFormClient auctionPublicId={auction.publicId} sellerPublicId={auction.sellerPublicId} />
  );
}
