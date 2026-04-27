"use client";
import Link from "next/link";
import type { AdminFraudFlagDetail } from "@/lib/admin/types";

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function truncateUuid(uuid: string): string {
  return `${uuid.slice(0, 6)}…${uuid.slice(-4)}`;
}

type EvidenceValueProps = {
  value: unknown;
  linkedUsers: AdminFraudFlagDetail["linkedUsers"];
};

function EvidenceValue({ value, linkedUsers }: EvidenceValueProps) {
  if (typeof value === "string" && UUID_RE.test(value)) {
    const linked = linkedUsers[value];
    if (linked) {
      return (
        <Link
          href={`/users/${linked.userId}`}
          title={`${value} — ${linked.displayName ?? "(no display name)"}`}
          className="font-mono text-primary underline underline-offset-2"
        >
          {truncateUuid(value)}
        </Link>
      );
    }
    return (
      <span
        className="font-mono"
        title={`${value} — (not a registered SLPA user)`}
      >
        {truncateUuid(value)}
      </span>
    );
  }
  return <span className="font-mono">{JSON.stringify(value)}</span>;
}

type Props = { detail: AdminFraudFlagDetail };

export function FraudFlagEvidence({ detail }: Props) {
  const entries = Object.entries(detail.evidenceJson);
  if (entries.length === 0) return null;

  return (
    <div className="flex flex-col gap-1" data-testid="fraud-flag-evidence">
      <div className="text-label-md text-on-surface font-medium mb-1">Evidence</div>
      <table className="w-full text-body-sm border-separate border-spacing-y-0.5">
        <tbody>
          {entries.map(([key, val]) => (
            <tr key={key} className="align-top">
              <td className="pr-3 py-1 text-on-surface-variant font-mono whitespace-nowrap w-[40%]">
                {key}
              </td>
              <td className="py-1 text-on-surface break-all">
                <EvidenceValue value={val} linkedUsers={detail.linkedUsers} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
