"use client";

import Link from "next/link";
import type { AdminEscrowReviewRow } from "@/lib/admin/escrowReviews";

type Props = { rows: AdminEscrowReviewRow[] };

const STEP_LABEL: Record<AdminEscrowReviewRow["step"], string> = {
  SET_SELL_TO: "Set Sell To",
  BUY_PARCEL: "Buy Parcel",
};

const REASON_LABEL: Record<AdminEscrowReviewRow["reason"], string> = {
  USER_REQUESTED: "User requested",
  BOT_PERSISTENT_FAILURE: "Bot persistent failure",
  WORLD_API_PERSISTENT_FAILURE: "World API persistent failure",
};

export function AdminEscrowReviewsTable({ rows }: Props) {
  if (rows.length === 0) {
    return (
      <p className="text-sm text-fg-muted">No escrow reviews in this view.</p>
    );
  }
  return (
    <table className="w-full text-xs">
      <thead className="text-[10px] uppercase opacity-60 text-left">
        <tr className="border-b border-border-subtle">
          <th className="py-2 px-2">Status</th>
          <th className="py-2 px-2">Parcel</th>
          <th className="py-2 px-2">Step</th>
          <th className="py-2 px-2">Reason</th>
          <th className="py-2 px-2">Requested by</th>
          <th className="py-2 px-2">Created</th>
          <th className="py-2 px-2">Age</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr
            key={row.reviewPublicId}
            className="border-b border-border-subtle/40 hover:bg-bg-subtle"
          >
            <td className="py-2 px-2">
              <Link
                href={`/admin/escrow-reviews/${row.reviewPublicId}`}
                className={
                  row.status === "OPEN" ? "text-danger" : "text-fg-muted"
                }
              >
                {row.status}
              </Link>
            </td>
            <td className="py-2 px-2">
              <Link href={`/admin/escrow-reviews/${row.reviewPublicId}`}>
                {row.parcelName}
              </Link>
            </td>
            <td className="py-2 px-2">{STEP_LABEL[row.step]}</td>
            <td className="py-2 px-2">{REASON_LABEL[row.reason]}</td>
            <td className="py-2 px-2">{row.requestedRole}</td>
            <td className="py-2 px-2">
              {new Date(row.createdAt).toLocaleString()}
            </td>
            <td className="py-2 px-2">{formatAge(row.ageMinutes)}</td>
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
