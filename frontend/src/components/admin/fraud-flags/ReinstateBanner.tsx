"use client";
import { useMemo } from "react";
import type { AdminFraudFlagDetail } from "@/lib/admin/types";

type Props = { detail: AdminFraudFlagDetail };

function formatDuration(ms: number): string {
  const totalMinutes = Math.floor(ms / 60_000);
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  if (hours === 0) return `${minutes}m`;
  return `${hours}h ${minutes}m`;
}

export function ReinstateBanner({ detail }: Props) {
  const duration = useMemo(() => {
    if (detail.auction?.status !== "SUSPENDED") return null;
    const startIso = detail.auction.suspendedAt ?? detail.detectedAt;
    // eslint-disable-next-line react-hooks/purity
    const elapsedMs = Date.now() - new Date(startIso).getTime();
    return formatDuration(Math.max(0, elapsedMs));
  }, [detail]);

  if (!duration) return null;

  return (
    <div
      className="rounded-lg bg-danger-bg/30 border border-danger-flat/30 px-4 py-3 text-sm text-danger-flat"
      data-testid="reinstate-banner"
    >
      <span className="font-medium">Auction is SUSPENDED.</span> Reinstate will restore{" "}
      <span className="font-medium">ACTIVE</span> status and extend{" "}
      <span className="font-mono">endsAt</span> by the suspension duration (
      <span className="font-medium">{duration}</span> so far).
    </div>
  );
}
