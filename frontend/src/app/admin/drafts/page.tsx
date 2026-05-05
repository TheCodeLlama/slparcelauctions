import { Suspense } from "react";
import { AdminListingsPage } from "@/components/admin/listings/AdminListingsPage";
import type { Preset } from "@/components/admin/listings/PresetChips";
import type { AuctionStatus } from "@/lib/admin/types";

const DRAFT_STATUSES: AuctionStatus[] = [
  "DRAFT", "DRAFT_PAID", "VERIFICATION_PENDING", "VERIFICATION_FAILED",
];

const PRESETS: Preset[] = [
  {
    key: "verification-failed",
    label: "Failed verification",
    statuses: ["VERIFICATION_FAILED"],
    sort: { column: "createdAt", direction: "desc" },
  },
  {
    key: "awaiting-verification",
    label: "Awaiting verification",
    statuses: ["VERIFICATION_PENDING"],
    sort: { column: "createdAt", direction: "asc" },
  },
  {
    key: "unpaid-drafts",
    label: "Unpaid drafts",
    statuses: ["DRAFT"],
    sort: { column: "createdAt", direction: "desc" },
  },
];

export default function AdminDraftsRoute() {
  return (
    <Suspense fallback={<div className="text-sm text-fg-muted py-6">Loading…</div>}>
      <AdminListingsPage
        basePath="/admin/drafts"
        lockedStatuses={DRAFT_STATUSES}
        defaultStatuses={DRAFT_STATUSES}
        defaultSort={{ column: "createdAt", direction: "desc" }}
        heading="Drafts"
        subheading="Listings still in the pre-launch pipeline (unpaid, paid-but-unverified, awaiting bot verification, or verification failed)."
        presets={PRESETS}
      />
    </Suspense>
  );
}
