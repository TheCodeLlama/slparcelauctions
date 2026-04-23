import type { Metadata } from "next";
import { ListingWizardForm } from "@/components/listing/ListingWizardForm";

export const metadata: Metadata = { title: "Create a listing" };

/**
 * Route entry for the Create listing wizard. Renders the shared
 * {@link ListingWizardForm} in create mode; the form owns the wizard
 * state and the first-save → navigate-to-activate handoff.
 *
 * This page is intentionally a thin client-tree wrapper. The wizard
 * itself is a client component (uses sessionStorage, useRouter,
 * useQuery) — no server-side data fetching happens here.
 */
export default function CreateListingPage() {
  return <ListingWizardForm mode="create" />;
}
