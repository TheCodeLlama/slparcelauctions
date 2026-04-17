import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { ListingWizardForm } from "@/components/listing/ListingWizardForm";

export const metadata: Metadata = { title: "Edit listing" };

type Props = { params: Promise<{ id: string }> };

/**
 * Route entry for the Edit listing wizard. Next.js 16 ships params as
 * a Promise — we await it at the page level (per the v16 upgrade guide)
 * and hand the numeric id down to the shared wizard.
 *
 * 404s on a non-integer id so we don't pass garbage to the backend
 * (which would return a 400 and waste a roundtrip). Authorization
 * (seller owns this auction) is enforced server-side on every auction
 * endpoint; the wizard surfaces the resulting 403 through the form
 * error slot.
 */
export default async function EditListingPage({ params }: Props) {
  const { id } = await params;
  const auctionId = Number(id);
  if (!Number.isInteger(auctionId) || auctionId <= 0) notFound();
  return <ListingWizardForm mode="edit" id={auctionId} />;
}
