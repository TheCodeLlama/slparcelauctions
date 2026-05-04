import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { ListingWizardForm } from "@/components/listing/ListingWizardForm";

export const metadata: Metadata = { title: "Edit listing" };

type Props = { params: Promise<{ publicId: string }> };

/**
 * Route entry for the Edit listing wizard. Next.js 16 ships params as
 * a Promise — we await it at the page level (per the v16 upgrade guide)
 * and hand the publicId down to the shared wizard. Authorization
 * (seller owns this auction) is enforced server-side on every auction
 * endpoint; the wizard surfaces the resulting 403 through the form
 * error slot.
 */
export default async function EditListingPage({ params }: Props) {
  const { publicId } = await params;
  if (!publicId) notFound();
  return <ListingWizardForm mode="edit" id={publicId} />;
}
