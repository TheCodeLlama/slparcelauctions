import { cache } from "react";
import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { isApiError } from "@/lib/api";
import { getAuction, getBidHistory } from "@/lib/api/auctions";
import { AuctionDetailClient } from "./AuctionDetailClient";

/**
 * Memoised auction fetch. React's {@code cache} dedups calls per-request so
 * {@link generateMetadata} and {@link AuctionPage} share a single network
 * round-trip during SSR. The wrapper is re-entered on every request; it is
 * NOT a cross-request cache (React's docs call this out explicitly).
 */
const getAuctionCached = cache((id: number) => getAuction(id));

interface Props {
  params: Promise<{ id: string }>;
}

/**
 * OpenGraph + Twitter card metadata for the auction detail page. Runs
 * during SSR before the page body renders — any failure here must not
 * abort the page render, so 404 / non-numeric IDs simply emit the generic
 * fallback title and let the page body handle the route param validation
 * (which in turn calls {@code notFound}). {@code cache()}-wrapped
 * {@link getAuctionCached} guarantees the body's fetch isn't duplicated.
 */
export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const { id } = await params;
  const auctionId = Number(id);
  if (!Number.isInteger(auctionId) || auctionId <= 0) {
    return { title: "Auction · SLPA" };
  }
  try {
    const a = await getAuctionCached(auctionId);
    // Prefer a seller-uploaded photo over the region snapshot for OG —
    // the detail DTO doesn't carry a primaryPhotoUrl, so we derive one
    // from photos[0] after sorting by sortOrder. The sort is cheap
    // (typical gallery is 1-5 photos) and keeps us aligned with the
    // AuctionHero's ordering.
    const primaryPhoto =
      [...a.photos].sort((p, q) => p.sortOrder - q.sortOrder)[0]?.url ?? null;
    const og = primaryPhoto ?? a.parcel.snapshotUrl ?? undefined;
    const currentBidDisplay =
      typeof a.currentBid === "number" ? a.currentBid.toLocaleString() : "—";
    return {
      title: `${a.title} · SLPA`,
      description: `${a.parcel.regionName} · ${a.parcel.areaSqm} sqm · L$ ${currentBidDisplay}`,
      openGraph: {
        title: a.title,
        description: `${a.parcel.regionName} · ${a.parcel.areaSqm} sqm`,
        images: og ? [og] : [],
        type: "website",
      },
      twitter: {
        card: og ? "summary_large_image" : "summary",
      },
    };
  } catch {
    return { title: "Auction · SLPA" };
  }
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
 * Uses {@link getAuctionCached} — shares the auction fetch with
 * {@link generateMetadata} for a single round-trip per SSR pass.
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
      getAuctionCached(auctionId),
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
