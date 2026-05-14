import { Suspense } from "react";
import { AdminGroupReportsQueuePage } from "@/components/admin/realty-groups/AdminGroupReportsQueuePage";

/**
 * Admin reports queue under the new /admin/groups/reports namespace.
 * Reports remain publicId-keyed (the publicId is the report's, not the
 * group's). The component's internal BASE_PATH is retargeted in Task 29.
 *
 * Force-dynamic because the report list changes per visit and the row
 * counts drive admin triage decisions — static prerender would couple
 * the Amplify build to whatever the prod API happens to be serving.
 */
export const dynamic = "force-dynamic";

export default function AdminGroupReportsQueueRoute() {
  return (
    <Suspense
      fallback={<div className="text-sm text-fg-muted py-6">Loading...</div>}
    >
      <AdminGroupReportsQueuePage />
    </Suspense>
  );
}
