"use client";

import { useAdminStats } from "@/hooks/admin/useAdminStats";
import { QueueCard } from "./QueueCard";
import { StatCard } from "./StatCard";

function formatLindens(n: number): string {
  return `L$ ${n.toLocaleString("en-US")}`;
}

export function AdminDashboardPage() {
  const { data, isLoading, isError } = useAdminStats();

  if (isLoading) {
    return <div className="text-sm opacity-60">Loading dashboard…</div>;
  }
  if (isError || !data) {
    return (
      <div className="text-sm text-error">Could not load admin stats. Refresh to retry.</div>
    );
  }

  return (
    <>
      <h1 className="text-2xl font-semibold">Dashboard</h1>
      <div className="text-xs opacity-60 mt-0.5 mb-6">Platform overview · lifetime</div>

      <div className="text-[11px] uppercase tracking-wide opacity-60 mb-2">Needs attention</div>
      <div className="grid grid-cols-3 gap-3 mb-7">
        <QueueCard
          label="Open fraud flags"
          value={data.queues.openFraudFlags}
          tone="fraud"
          subtext="Click to triage"
          href="/admin/fraud-flags"
        />
        <QueueCard
          label="Pending payments"
          value={data.queues.pendingPayments}
          tone="warning"
          subtext="Awaiting winner L$"
        />
        <QueueCard
          label="Active disputes"
          value={data.queues.activeDisputes}
          tone="warning"
          subtext="Escrow disputed"
        />
      </div>

      <div className="text-[11px] uppercase tracking-wide opacity-60 mb-2">Platform · lifetime</div>
      <div className="grid grid-cols-3 gap-3">
        <StatCard label="Active listings" value={data.platform.activeListings} />
        <StatCard label="Total users" value={data.platform.totalUsers} />
        <StatCard label="Active escrows" value={data.platform.activeEscrows} />
        <StatCard label="Completed sales" value={data.platform.completedSales} />
        <StatCard label="L$ gross volume" value={formatLindens(data.platform.lindenGrossVolume)} />
        <StatCard
          label="L$ commission"
          value={formatLindens(data.platform.lindenCommissionEarned)}
          accent
        />
      </div>
    </>
  );
}
