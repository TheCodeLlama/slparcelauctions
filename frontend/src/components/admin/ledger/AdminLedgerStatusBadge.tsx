/**
 * Status chip for the ledger row. Maps the kind-native status string into
 * one of five buckets — green (success), amber (in-progress), red (failure),
 * blue (active hold), neutral (released / settled). Renders a dash for null
 * (USER_LEDGER rows are always settled and have no status).
 */

const GREEN = new Set(["COMPLETED", "SUCCEEDED", "SETTLED", "CONFIRMED"]);
const AMBER = new Set(["IN_FLIGHT", "QUEUED", "PENDING", "DISPATCHING"]);
const RED = new Set(["FAILED", "REVERSED", "CANCELLED", "EXPIRED"]);
const BLUE = new Set(["RESERVED", "ACTIVE"]);

function chipClass(status: string): string {
  if (GREEN.has(status)) return "bg-success-bg text-success";
  if (AMBER.has(status)) return "bg-warning-bg text-warning";
  if (RED.has(status))   return "bg-danger text-white";
  if (BLUE.has(status))  return "bg-info-bg text-info";
  return "bg-bg-hover text-fg-muted";  // RELEASED, etc.
}

export function AdminLedgerStatusBadge({ status }: { status: string | null }) {
  if (!status) return <span className="text-fg-muted/50">—</span>;
  return (
    <span
      className={`inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-semibold ${chipClass(status)}`}
      data-testid={`ledger-status-${status}`}
    >
      {status}
    </span>
  );
}
