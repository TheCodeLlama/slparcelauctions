import type { Metadata } from "next";
import { AdminParcelTagsPage } from "@/components/admin/parcel-tags/AdminParcelTagsPage";

export const metadata: Metadata = { title: "Admin · Parcel tags" };

/**
 * Route entry for /admin/parcel-tags. The admin layout wrapping this route
 * (frontend/src/app/admin/layout.tsx) handles the ROLE_ADMIN gate; this
 * component just renders the client page.
 */
export default function AdminParcelTagsRoute() {
  return <AdminParcelTagsPage />;
}
