import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { getAuction } from "@/lib/api/auctions";
import { isApiError } from "@/lib/api";
import { EscrowPageClient } from "./EscrowPageClient";

export const metadata: Metadata = { title: "Escrow" };

interface Props {
  params: Promise<{ publicId: string }>;
}

/**
 * Escrow status page — server component shell.
 *
 * Validates the route param, fetches the auction so we can 404 on
 * bad ids and seed {@code sellerId} into the client for role
 * derivation, then hands off to {@link EscrowPageClient} which owns
 * the authenticated viewer gate, the {@code useQuery} seed, the WS
 * subscription, and the reconnect reconcile.
 *
 * Auth is intentionally NOT checked here. The frontend auth stack
 * lives in the browser (session cookie + refresh flow + React Query
 * cache), so the RSC shell has no synchronous access to the caller's
 * identity — the authenticated-viewer redirect runs in the client
 * (mirror of the dashboard and verified-listings layouts). Server-
 * side authz is enforced by the backend on the escrow endpoint
 * itself, which returns 403 to anyone other than the seller or the
 * winner.
 *
 * See sub-spec 2 §5 (composition) and §7.1 (authz gate).
 */
export default async function EscrowStatusPage({ params }: Props) {
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
    <EscrowPageClient auctionPublicId={auction.publicId} sellerPublicId={auction.sellerPublicId} />
  );
}
