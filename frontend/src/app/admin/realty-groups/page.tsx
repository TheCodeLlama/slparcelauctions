import { Suspense } from "react";
import { AdminRealtyGroupsPage } from "@/components/admin/realty-groups/AdminRealtyGroupsPage";

export default function AdminRealtyGroupsRoute() {
  return (
    <Suspense
      fallback={<div className="text-sm text-fg-muted py-6">Loading...</div>}
    >
      <AdminRealtyGroupsPage />
    </Suspense>
  );
}
