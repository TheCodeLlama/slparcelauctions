"use client";

import { useState } from "react";
import { AdminDisputesFilters } from "./AdminDisputesFilters";
import { AdminDisputesTable } from "./AdminDisputesTable";
import { useDisputesQueue } from "@/lib/admin/disputeHooks";
import type { AdminDisputeFilters as Filters } from "@/lib/admin/disputes";

export function AdminDisputesListPage() {
  const [filters, setFilters] = useState<Filters>({});
  const { data, isLoading, error } = useDisputesQueue(filters);

  if (isLoading) return <p>Loading…</p>;
  if (error) return <p className="text-error">Failed to load disputes</p>;

  return (
    <div>
      <h1 className="text-xl font-semibold mb-4">Disputes</h1>
      <AdminDisputesFilters filters={filters} onChange={setFilters} />
      <div className="mt-4">
        <AdminDisputesTable rows={data?.content ?? []} />
      </div>
    </div>
  );
}
