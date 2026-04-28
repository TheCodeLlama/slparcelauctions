"use client";

import type { AdminDisputeFilters, EscrowState, EscrowDisputeReasonCategory } from "@/lib/admin/disputes";

type Props = {
  filters: AdminDisputeFilters;
  onChange: (next: AdminDisputeFilters) => void;
};

export function AdminDisputesFilters({ filters, onChange }: Props) {
  const setStatus = (status: EscrowState | undefined) =>
    onChange({ ...filters, status });

  return (
    <div className="flex gap-2 flex-wrap items-center">
      <button
        className={chipClass(filters.status === "DISPUTED")}
        onClick={() => setStatus(filters.status === "DISPUTED" ? undefined : "DISPUTED")}
      >
        ⚐ Disputed
      </button>
      <button
        className={chipClass(filters.status === "FROZEN")}
        onClick={() => setStatus(filters.status === "FROZEN" ? undefined : "FROZEN")}
      >
        ❄ Frozen
      </button>
      <select
        className="px-2 py-1 bg-surface-container-low rounded text-xs"
        value={filters.reasonCategory ?? ""}
        onChange={(e) =>
          onChange({
            ...filters,
            reasonCategory: (e.target.value || undefined) as EscrowDisputeReasonCategory | undefined,
          })
        }
      >
        <option value="">All reasons</option>
        <option value="PAYMENT_NOT_CREDITED">Payment not credited</option>
        <option value="WRONG_PARCEL_TRANSFERRED">Wrong parcel</option>
        <option value="SELLER_NOT_RESPONSIVE">Seller not responsive</option>
        <option value="FRAUD_SUSPECTED">Fraud suspected</option>
        <option value="OTHER">Other</option>
      </select>
    </div>
  );
}

function chipClass(active: boolean) {
  return `px-2.5 py-1.5 text-xs rounded-full border ${
    active
      ? "bg-error-container text-on-error-container border-error"
      : "bg-surface-container-low text-on-surface border-outline-variant"
  }`;
}
