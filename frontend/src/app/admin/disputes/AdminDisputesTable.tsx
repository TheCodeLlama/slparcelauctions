"use client";

import Link from "next/link";
import type { AdminDisputeQueueRow } from "@/lib/admin/disputes";

type Props = { rows: AdminDisputeQueueRow[] };

export function AdminDisputesTable({ rows }: Props) {
  if (rows.length === 0) {
    return <p className="text-sm text-fg-muted">No disputes in this view.</p>;
  }
  return (
    <table className="w-full text-xs">
      <thead className="text-[10px] uppercase opacity-60 text-left">
        <tr className="border-b border-border-subtle">
          <th className="py-2 px-2">Status</th>
          <th className="py-2 px-2">Listing</th>
          <th className="py-2 px-2">Reason</th>
          <th className="py-2 px-2">Parties</th>
          <th className="py-2 px-2">Sale L$</th>
          <th className="py-2 px-2">Opened</th>
          <th className="py-2 px-2">Age</th>
          <th className="py-2 px-2">Evidence</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr key={row.escrowId} className="border-b border-border-subtle/40 hover:bg-bg-subtle">
            <td className="py-2 px-2">
              <Link href={`/admin/disputes/${row.escrowId}`} className="text-danger">
                {row.status === "DISPUTED" ? "⚐ Disputed" : "❄ Frozen"}
              </Link>
            </td>
            <td className="py-2 px-2">
              <Link href={`/admin/disputes/${row.escrowId}`}>{row.auctionTitle}</Link>
            </td>
            <td className="py-2 px-2">{row.reasonCategory ?? "—"}</td>
            <td className="py-2 px-2">{row.sellerEmail} → {row.winnerEmail}</td>
            <td className="py-2 px-2">L$ {row.salePriceL.toLocaleString()}</td>
            <td className="py-2 px-2">{new Date(row.openedAt).toLocaleString()}</td>
            <td className="py-2 px-2">{formatAge(row.ageMinutes)}</td>
            <td className="py-2 px-2">W:{row.winnerEvidenceCount}img S:{row.sellerEvidenceCount}img</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function formatAge(minutes: number) {
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ${minutes % 60}m`;
  const days = Math.floor(hours / 24);
  return `${days}d ${hours % 24}h`;
}
