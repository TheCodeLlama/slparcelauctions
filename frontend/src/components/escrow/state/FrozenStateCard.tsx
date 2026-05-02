import type { EscrowFreezeReason } from "@/types/escrow";
import type { StateCardProps } from "./types";

const REASON_LABEL: Record<EscrowFreezeReason, string> = {
  UNKNOWN_OWNER: "Unknown owner",
  PARCEL_DELETED: "Parcel deleted",
  WORLD_API_PERSISTENT_FAILURE: "World API persistent failure",
};

/**
 * FROZEN state card. Terminal state — the backend halted the escrow
 * because an invariant was violated (ownership flipped mid-flight,
 * parcel was deleted, or the SL World API is persistently unreachable).
 * Copy softens for `WORLD_API_PERSISTENT_FAILURE` because that reason
 * is likely transient and shouldn't alarm the user; other reasons
 * imply seller/parcel misbehavior and warrant a stronger note.
 */
export function FrozenStateCard({ escrow, role }: StateCardProps) {
  const isTransient = escrow.freezeReason === "WORLD_API_PERSISTENT_FAILURE";
  const reasonLabel = escrow.freezeReason
    ? REASON_LABEL[escrow.freezeReason]
    : "Unspecified";

  return (
    <section
      data-testid="escrow-state-card"
      data-state="FROZEN"
      data-role={role}
      className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-surface-raised p-5 shadow-sm"
    >
      <h2 className="text-sm font-semibold tracking-tight text-fg">
        Escrow frozen
        {escrow.frozenAt ? ` ${formatTimestamp(escrow.frozenAt)}` : ""}
      </h2>
      <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
        <dt className="text-fg-muted">Reason:</dt>
        <dd className="text-fg">{reasonLabel}</dd>
      </dl>
      {isTransient ? (
        <p className="text-sm text-fg-muted">
          We couldn&apos;t verify parcel ownership repeatedly; this is likely
          a transient issue and SLPA will re-check manually.
        </p>
      ) : (
        <p className="text-sm text-fg-muted">
          Your L$ will be refunded automatically. SLPA has flagged this
          auction for review.
        </p>
      )}
    </section>
  );
}

function formatTimestamp(iso: string): string {
  return new Date(iso).toLocaleString(undefined, {
    dateStyle: "medium",
    timeStyle: "short",
  });
}
