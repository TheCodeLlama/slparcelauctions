import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { isApiError } from "@/lib/api";
import { getAuction, getBidHistory } from "@/lib/api/auctions";
import { AuctionDetailClient } from "./AuctionDetailClient";

export const metadata: Metadata = { title: "Auction" };

interface Props {
  params: Promise<{ id: string }>;
}

/**
 * Auction detail page — server component shell.
 *
 * Validates the route param, dual-fetches the auction + first bid-history
 * page (both in parallel because bid history is no-auth public), then hands
 * the seed data to {@link AuctionDetailClient} which owns the live WebSocket
 * subscription and cache merging. A 404 on either endpoint surfaces as the
 * Next.js not-found route — SUSPENDED hidden-from-non-sellers is enforced
 * in the backend DTO layer (Epic 03 sub-2), so the same public path is safe
 * for anonymous viewers.
 *
 * See spec §7 (server / client composition).
 */
export default async function AuctionPage({ params }: Props) {
  const { id } = await params;
  const auctionId = Number(id);
  if (!Number.isInteger(auctionId) || auctionId <= 0) notFound();

  // Dual-fetch with 404 normalization. Treat any 404 from either endpoint
  // as "auction does not exist" — the two endpoints share the same
  // ownership gate server-side, so a 404 on bid-history implies the auction
  // itself is gone too. Other errors propagate to Next.js' error boundary.
  //
  // ESLint's `react-hooks/error-boundaries` rule flags constructing JSX
  // inside a try/catch — React defers rendering past the catch point, so
  // thrown errors from the render path wouldn't be caught anyway. Keep the
  // await inside the try and the return outside it.
  let auction;
  let firstBidPage;
  try {
    [auction, firstBidPage] = await Promise.all([
      getAuction(auctionId),
      getBidHistory(auctionId, { page: 0, size: 20 }),
    ]);
  } catch (err) {
    if (isApiError(err) && err.status === 404) notFound();
    throw err;
  }

  // `getAuction` narrows to the DTO at runtime but TypeScript tolerates a
  // `null` escape — defensive re-check so the client component never has
  // to handle an undefined initial snapshot.
  if (!auction) notFound();

  return (
    <AuctionDetailClient
      initialAuction={auction}
      initialBidPage={firstBidPage}
    />
  );
}
