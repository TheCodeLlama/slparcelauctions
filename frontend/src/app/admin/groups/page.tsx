import { Suspense } from "react";
import { AdminRealtyGroupsPage } from "@/components/admin/realty-groups/AdminRealtyGroupsPage";

/**
 * Admin list route under the new /admin/groups namespace. Mirrors the
 * previous /admin/realty-groups route; the underlying component is
 * unchanged. Row click targets are retargeted to /admin/groups/[slug] in
 * Task 29 (sweep).
 */
export default function AdminGroupsListRoute() {
  return (
    <Suspense
      fallback={<div className="text-sm text-fg-muted py-6">Loading...</div>}
    >
      <AdminRealtyGroupsPage />
    </Suspense>
  );
}
