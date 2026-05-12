import { Suspense } from "react";
import { AdminGroupReportsQueuePage } from "@/components/admin/realty-groups/AdminGroupReportsQueuePage";

/**
 * Standalone admin reports queue for realty groups (Sub-project F, §7 / §12.2).
 *
 * Force-dynamic because the report list changes per visit and the row
 * counts drive admin triage decisions — static prerender would couple the
 * Amplify build to whatever the prod API happens to be serving.
 */
export const dynamic = "force-dynamic";

export default function AdminGroupReportsRoute() {
  return (
    <Suspense
      fallback={<div className="text-sm text-fg-muted py-6">Loading...</div>}
    >
      <AdminGroupReportsQueuePage />
    </Suspense>
  );
}
