import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { ActivateClient } from "./ActivateClient";

export const metadata: Metadata = { title: "Activate listing" };

type Props = { params: Promise<{ id: string }> };

/**
 * Server entry for {@code /listings/[id]/activate}. Next.js 16 ships
 * {@code params} as a Promise — we await it here (per the v16 upgrade
 * guide) and hand the numeric id down to the client page. Non-integer
 * ids 404 so we don't waste a roundtrip to the backend with garbage.
 *
 * The actual flow lives in ActivateClient (client component) because
 * it polls with React Query and uses {@code useRouter} for redirects.
 */
export default async function ActivateListingPage({ params }: Props) {
  const { id } = await params;
  const auctionId = Number(id);
  if (!Number.isInteger(auctionId) || auctionId <= 0) notFound();
  return <ActivateClient auctionId={auctionId} />;
}
