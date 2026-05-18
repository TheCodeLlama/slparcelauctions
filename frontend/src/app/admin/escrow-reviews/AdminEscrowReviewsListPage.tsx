"use client";

import { useState } from "react";
import { AdminEscrowReviewsFilters } from "./AdminEscrowReviewsFilters";
import { AdminEscrowReviewsTable } from "./AdminEscrowReviewsTable";
import { useEscrowReviewsQueue } from "@/lib/admin/escrowReviewHooks";
import type { AdminEscrowReviewFilters as Filters } from "@/lib/admin/escrowReviews";

export function AdminEscrowReviewsListPage() {
  const [filters, setFilters] = useState<Filters>({ status: "OPEN" });
  const { data, isLoading, error } = useEscrowReviewsQueue(filters);

  if (isLoading) return <p>Loading…</p>;
  if (error) return <p className="text-danger">Failed to load escrow reviews</p>;

  return (
    <div>
      <h1 className="text-xl font-semibold mb-4">Escrow Reviews</h1>
      <AdminEscrowReviewsFilters filters={filters} onChange={setFilters} />
      <div className="mt-4">
        <AdminEscrowReviewsTable rows={data?.content ?? []} />
      </div>
    </div>
  );
}
