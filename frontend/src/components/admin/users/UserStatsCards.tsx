import type { AdminUserDetail } from "@/lib/admin/types";

type Props = {
  stats: AdminUserDetail;
};

type StatCardProps = {
  label: string;
  value: string | number;
  variant?: "default" | "error" | "warning";
};

function StatCard({ label, value, variant = "default" }: StatCardProps) {
  const valueClass =
    variant === "error"
      ? "text-error"
      : variant === "warning"
      ? "text-tertiary"
      : "text-on-surface";

  return (
    <div className="bg-surface-container border border-outline-variant rounded-lg p-4">
      <div className="text-[11px] text-on-surface-variant/70">{label}</div>
      <div className={`text-2xl font-semibold mt-1.5 ${valueClass}`}>{value}</div>
    </div>
  );
}

export function UserStatsCards({ stats }: Props) {
  return (
    <div
      className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-3 mb-6"
      data-testid="user-stats-cards"
    >
      <StatCard label="Completed Sales" value={stats.completedSales} />
      <StatCard
        label="Cancelled w/ Bids"
        value={stats.cancelledWithBids}
        variant={stats.cancelledWithBids > 0 ? "error" : "default"}
      />
      <StatCard
        label="Escrow Expired"
        value={stats.escrowExpiredUnfulfilled}
        variant={stats.escrowExpiredUnfulfilled > 0 ? "error" : "default"}
      />
      <StatCard
        label="Dismissed Reports"
        value={stats.dismissedReportsCount}
      />
      <StatCard
        label="Penalty Owed (L$)"
        value={stats.penaltyBalanceOwed.toLocaleString()}
        variant={stats.penaltyBalanceOwed > 0 ? "warning" : "default"}
      />
    </div>
  );
}
