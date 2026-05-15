"use client";

import type { ComponentType } from "react";
import {
  ArrowDownToLine,
  ArrowUpFromLine,
  Clock,
  Pencil,
  Tag,
  Undo2,
  Wallet,
} from "@/components/ui/icons";
import { Button } from "@/components/ui/Button";
import { cn } from "@/lib/cn";
import { formatRelativeTime } from "@/lib/time/relativeTime";
import { useGroupLedger } from "@/hooks/realty/useGroupLedger";
import type { GroupLedgerEntry, GroupLedgerEntryType } from "@/types/realty";

// ─── Formatting helpers ─────────────────────────────────────────────────────

function formatLindens(amount: number): string {
  return `L$${amount.toLocaleString()}`;
}

function formatLedgerDate(iso: string): string {
  const d = new Date(iso);
  if (!Number.isFinite(d.getTime())) return iso;
  const hoursAgo = (Date.now() - d.getTime()) / (1000 * 60 * 60);
  if (hoursAgo < 24) return formatRelativeTime(d);
  return d.toLocaleString(undefined, {
    year: "numeric",
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

/** Human-readable label for each ledger entry type. */
function entryTypeLabel(entryType: GroupLedgerEntryType): string {
  switch (entryType) {
    case "LISTING_FEE_DEBIT": return "Listing fee paid";
    case "LISTING_FEE_REFUND": return "Listing fee refund";
    case "AGENT_FEE_CREDIT": return "Agent fee earned";
    case "WITHDRAW_QUEUED": return "Withdrawal (Pending)";
    case "WITHDRAW_COMPLETED": return "Withdrawal";
    case "WITHDRAW_REVERSED": return "Withdrawal (Reversed)";
    case "DORMANCY_AUTO_RETURN": return "Dormancy return";
    case "ADJUSTMENT": return "Adjustment";
    case "MEMBER_DEPOSIT": return "Member deposit";
  }
}

type EntryVisual = {
  Icon: ComponentType<{ className?: string }>;
  tone: string;
};

/**
 * Icon and colour tone for each entry type. Debits use neutral/muted tones;
 * credits use success green.
 */
function entryVisual(entryType: GroupLedgerEntryType): EntryVisual {
  switch (entryType) {
    case "LISTING_FEE_DEBIT":
      return { Icon: Tag, tone: "text-fg" };
    case "LISTING_FEE_REFUND":
      return { Icon: ArrowDownToLine, tone: "text-success" };
    case "AGENT_FEE_CREDIT":
      return { Icon: ArrowDownToLine, tone: "text-success" };
    case "WITHDRAW_QUEUED":
      return { Icon: Clock, tone: "text-warning" };
    case "WITHDRAW_COMPLETED":
      return { Icon: ArrowUpFromLine, tone: "text-fg-muted" };
    case "WITHDRAW_REVERSED":
      return { Icon: Undo2, tone: "text-danger" };
    case "DORMANCY_AUTO_RETURN":
      return { Icon: ArrowUpFromLine, tone: "text-fg-muted" };
    case "ADJUSTMENT":
      return { Icon: Pencil, tone: "text-fg-muted" };
    case "MEMBER_DEPOSIT":
      return { Icon: ArrowDownToLine, tone: "text-success" };
  }
}

/**
 * Signed amount display: credits (AGENT_FEE_CREDIT, LISTING_FEE_REFUND,
 * WITHDRAW_REVERSED, DORMANCY_AUTO_RETURN) appear as positive; debits
 * (LISTING_FEE_DEBIT, WITHDRAW_QUEUED, WITHDRAW_COMPLETED) appear as negative.
 */
function signedAmount(entry: GroupLedgerEntry): { label: string; tone: string } {
  const isCredit =
    entry.entryType === "AGENT_FEE_CREDIT" ||
    entry.entryType === "LISTING_FEE_REFUND" ||
    entry.entryType === "WITHDRAW_REVERSED" ||
    entry.entryType === "DORMANCY_AUTO_RETURN" ||
    entry.entryType === "MEMBER_DEPOSIT";

  const label = isCredit
    ? `+${formatLindens(entry.amount)}`
    : `-${formatLindens(entry.amount)}`;
  const tone = isCredit ? "text-success" : "text-fg";

  return { label, tone };
}

// ─── LedgerRow ──────────────────────────────────────────────────────────────

interface LedgerRowProps {
  entry: GroupLedgerEntry;
}

function LedgerRow({ entry }: LedgerRowProps) {
  const { Icon, tone } = entryVisual(entry.entryType);
  const { label: amountLabel, tone: amountTone } = signedAmount(entry);

  const refLink =
    entry.refType === "AUCTION" && entry.refPublicId
      ? `/auction/${entry.refPublicId}`
      : null;

  return (
    <tr
      className="border-b border-border-subtle last:border-b-0 hover:bg-bg-subtle"
      data-testid="ledger-row"
    >
      {/* When */}
      <td
        className="py-2 px-3 text-xs text-fg-muted whitespace-nowrap"
        title={new Date(entry.createdAt).toLocaleString()}
      >
        {formatLedgerDate(entry.createdAt)}
      </td>

      {/* Type */}
      <td className="py-2 px-3">
        <div className="flex items-center gap-2">
          <Icon className={cn("h-4 w-4 shrink-0", tone)} aria-hidden="true" />
          <span className="text-sm text-fg">{entryTypeLabel(entry.entryType)}</span>
        </div>
      </td>

      {/* Amount */}
      <td
        className={cn("py-2 px-3 text-sm tabular-nums text-right", amountTone)}
        data-testid="ledger-amount"
      >
        {amountLabel}
      </td>

      {/* Balance after */}
      <td
        className="py-2 px-3 text-sm tabular-nums text-right text-fg-muted"
        data-testid="ledger-balance-after"
      >
        {formatLindens(entry.balanceAfter)}
      </td>

      {/* Reference */}
      <td className="py-2 px-3 text-sm">
        {refLink ? (
          <a
            href={refLink}
            className="text-brand underline underline-offset-2 hover:text-brand-hover text-xs"
          >
            Auction
          </a>
        ) : (
          <span className="text-fg-muted text-xs">—</span>
        )}
      </td>

      {/* Actor */}
      <td className="py-2 px-3 text-sm text-fg-muted">
        {entry.actor ? (
          <span className="text-xs">{entry.actor.displayName}</span>
        ) : (
          <span className="text-xs">—</span>
        )}
      </td>
    </tr>
  );
}

// ─── GroupWalletLedgerTable ──────────────────────────────────────────────────

export interface GroupWalletLedgerTableProps {
  publicId: string;
}

/**
 * Cursor-paginated ledger table for the group wallet. Uses
 * {@link useGroupLedger} for infinite-scroll pagination. Columns:
 * When, Type, Amount (signed), Balance after, Reference, Actor.
 *
 * "Load more" appears at the bottom when the backend indicates additional
 * pages exist (i.e. the last page was full).
 */
export function GroupWalletLedgerTable({
  publicId,
}: GroupWalletLedgerTableProps) {
  const {
    data,
    isPending,
    isFetchingNextPage,
    hasNextPage,
    fetchNextPage,
  } = useGroupLedger(publicId);

  const entries =
    data?.pages.flatMap((page) => page) ?? [];

  if (isPending) {
    return (
      <div className="text-sm text-fg-muted py-8 text-center" data-testid="ledger-loading">
        Loading activity...
      </div>
    );
  }

  if (entries.length === 0) {
    return (
      <div
        className="flex flex-col items-center text-center py-8 gap-3"
        data-testid="ledger-empty"
      >
        <Wallet className="h-12 w-12 text-fg-muted/40" aria-hidden="true" />
        <h3 className="font-medium text-fg">No activity yet</h3>
        <p className="text-sm text-fg-muted max-w-sm">
          Group wallet activity appears here once the first deposit, listing fee,
          or agent fee credit posts.
        </p>
      </div>
    );
  }

  return (
    <div>
      <div className="overflow-x-auto">
        <table className="w-full text-left" data-testid="ledger-table">
          <thead>
            <tr className="text-xs uppercase tracking-wide text-fg-muted border-b border-border">
              <th className="py-2 px-3 font-medium">When</th>
              <th className="py-2 px-3 font-medium">Type</th>
              <th className="py-2 px-3 font-medium text-right">Amount</th>
              <th className="py-2 px-3 font-medium text-right">Balance after</th>
              <th className="py-2 px-3 font-medium">Reference</th>
              <th className="py-2 px-3 font-medium">Actor</th>
            </tr>
          </thead>
          <tbody>
            {entries.map((entry) => (
              <LedgerRow
                key={entry.publicId}
                entry={entry}
              />
            ))}
          </tbody>
        </table>
      </div>

      {hasNextPage && (
        <div className="mt-4 flex justify-center">
          <Button
            variant="secondary"
            size="sm"
            onClick={() => fetchNextPage()}
            loading={isFetchingNextPage}
            data-testid="load-more-button"
          >
            Load more
          </Button>
        </div>
      )}
    </div>
  );
}
