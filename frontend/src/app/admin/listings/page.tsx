import { Suspense } from "react";
import { AdminListingsPage } from "@/components/admin/listings/AdminListingsPage";
import type { Preset } from "@/components/admin/listings/PresetChips";
import type { AuctionStatus } from "@/lib/admin/types";

const LIVE_STATUSES: AuctionStatus[] = [
  "ACTIVE", "TRANSFER_PENDING", "DISPUTED",
  "COMPLETED", "CANCELLED", "EXPIRED", "FROZEN", "SUSPENDED",
];

const PRESETS: Preset[] = [
  {
    key: "ending-soon",
    label: "Ending soon",
    statuses: ["ACTIVE"],
    sort: { column: "endsAt", direction: "asc" },
  },
  {
    key: "featured",
    label: "Featured",
    statuses: ["ACTIVE"],
    sort: { column: "endsAt", direction: "asc" },
    featured: true,
  },
  {
    key: "live-escrow",
    label: "Live escrow",
    // Post the auction-status state-machine rewire (spec 2026-05-17) the
    // mid-flight settlement set is just TRANSFER_PENDING + DISPUTED; the
    // legacy {@code ESCROW_PENDING}/{@code ESCROW_FUNDED} stops collapse
    // into TRANSFER_PENDING.
    statuses: ["TRANSFER_PENDING", "DISPUTED"],
    sort: { column: "createdAt", direction: "asc" },
  },
  {
    key: "suspended",
    label: "Suspended",
    statuses: ["SUSPENDED"],
    sort: { column: "createdAt", direction: "desc" },
  },
  {
    key: "recently-ended",
    label: "Recently ended",
    // {@code ENDED} no longer exists as a status; recently-closed cycles
    // sit at COMPLETED / EXPIRED / FROZEN / CANCELLED.
    statuses: ["COMPLETED", "EXPIRED", "FROZEN", "CANCELLED"],
    sort: { column: "endsAt", direction: "desc" },
  },
];

export default function AdminListingsRoute() {
  return (
    <Suspense fallback={<div className="text-sm text-fg-muted py-6">Loading…</div>}>
      <AdminListingsPage
        basePath="/admin/listings"
        defaultStatuses={LIVE_STATUSES}
        defaultSort={{ column: "createdAt", direction: "desc" }}
        heading="Listings"
        subheading="All non-draft listings. Use the Drafts tab for the pre-launch pipeline."
        presets={PRESETS}
      />
    </Suspense>
  );
}
