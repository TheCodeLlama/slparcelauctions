import type { AdminLedgerKind } from "@/lib/admin/types";

const CLASS_BY_KIND: Record<AdminLedgerKind, string> = {
  USER_LEDGER:    "bg-info-bg text-info",
  ESCROW_TXN:     "bg-bg-hover text-fg",
  TERMINAL_CMD:   "bg-warning-bg text-warning",
  WITHDRAWAL:     "bg-bg-subtle text-fg-muted ring-1 ring-border-subtle",
  BID_RESERVATION:"bg-success-bg text-success",
};

const LABEL_BY_KIND: Record<AdminLedgerKind, string> = {
  USER_LEDGER:    "USER",
  ESCROW_TXN:     "ESCROW",
  TERMINAL_CMD:   "TERMINAL",
  WITHDRAWAL:     "WITHDRAWAL",
  BID_RESERVATION:"BID HOLD",
};

export function AdminLedgerKindBadge({ kind }: { kind: AdminLedgerKind }) {
  return (
    <span
      className={`inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-semibold ${CLASS_BY_KIND[kind]}`}
      data-testid={`ledger-kind-${kind}`}
    >
      {LABEL_BY_KIND[kind]}
    </span>
  );
}
