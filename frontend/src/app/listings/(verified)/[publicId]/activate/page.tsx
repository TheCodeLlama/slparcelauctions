import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { ActivateClient } from "./ActivateClient";

export const metadata: Metadata = { title: "Activate listing" };

type Props = { params: Promise<{ publicId: string }> };

/**
 * Server entry for {@code /listings/[publicId]/activate}. Next.js 16 ships
 * {@code params} as a Promise — we await it here (per the v16 upgrade
 * guide) and hand the publicId down to the client page.
 *
 * The actual flow lives in ActivateClient (client component) because
 * it polls with React Query and uses {@code useRouter} for redirects.
 */
export default async function ActivateListingPage({ params }: Props) {
  const { publicId } = await params;
  if (!publicId) notFound();
  return <ActivateClient auctionPublicId={publicId} />;
}
