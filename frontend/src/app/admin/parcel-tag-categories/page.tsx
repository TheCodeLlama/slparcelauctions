import type { Metadata } from "next";
import { AdminParcelTagCategoriesPage } from "@/components/admin/parcel-tag-categories/AdminParcelTagCategoriesPage";

export const metadata: Metadata = { title: "Admin · Parcel categories" };

export default function AdminParcelTagCategoriesRoute() {
  return <AdminParcelTagCategoriesPage />;
}
