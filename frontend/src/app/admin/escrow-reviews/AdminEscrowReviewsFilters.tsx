"use client";

import type {
  AdminEscrowReviewFilters,
  ManualReviewStatus,
} from "@/lib/admin/escrowReviews";

type Props = {
  filters: AdminEscrowReviewFilters;
  onChange: (next: AdminEscrowReviewFilters) => void;
};

const STATUSES: { value: ManualReviewStatus; label: string }[] = [
  { value: "OPEN", label: "Open" },
  { value: "RESOLVED", label: "Resolved" },
  { value: "DISMISSED", label: "Dismissed" },
];

export function AdminEscrowReviewsFilters({ filters, onChange }: Props) {
  const setStatus = (status: ManualReviewStatus | undefined) =>
    onChange({ ...filters, status });

  return (
    <div className="flex gap-2 flex-wrap items-center">
      {STATUSES.map((s) => {
        const active = filters.status === s.value;
        return (
          <button
            key={s.value}
            type="button"
            className={chipClass(active)}
            onClick={() => setStatus(active ? undefined : s.value)}
          >
            {s.label}
          </button>
        );
      })}
    </div>
  );
}

function chipClass(active: boolean) {
  return `px-2.5 py-1.5 text-xs rounded-full border ${
    active
      ? "bg-info-bg text-info border-info"
      : "bg-bg-subtle text-fg border-border-subtle"
  }`;
}
