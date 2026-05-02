"use client";

import { type ComponentType } from "react";
import {
  AlertTriangle,
  ArrowDownToLine,
  ArrowUpFromLine,
  Clock,
  Lock,
  Pencil,
  Tag,
  Undo2,
  Unlock,
  Wallet,
} from "@/components/ui/icons";
import { cn } from "@/lib/cn";
import { formatRelativeTime } from "@/lib/time/relativeTime";
import type { LedgerEntry, WithdrawalStatus } from "@/types/wallet";

function formatLindens(amount: number): string {
  return `L$${amount.toLocaleString()}`;
}

/**
 * Label for a ledger row. WITHDRAW_QUEUED is special-cased so the user
 * sees a single "Withdrawal" row that flips state in place: the
 * (Pending) suffix appears while the dispatcher / in-world transfer is
 * still in flight; once a paired completion / reversal lands the
 * collapsed-view backend stamps the status and this label drops the
 * suffix (or shows "Reversed" for the rare reversal case).
 */
function entryTypeLabel(e: LedgerEntry): string {
  switch (e.entryType) {
    case "DEPOSIT": return "Deposit";
    case "WITHDRAW_QUEUED": {
      switch (e.withdrawalStatus) {
        case "COMPLETED": return "Withdrawal";
        case "REVERSED": return "Withdrawal (Reversed)";
        case "PENDING":
        default: return "Withdrawal (Pending)";
      }
    }
    // Defensive — the backend filters these out of /me/wallet/ledger,
    // but historical responses or admin views might still contain them.
    case "WITHDRAW_COMPLETED": return "Withdrawal";
    case "WITHDRAW_REVERSED": return "Withdrawal (Reversed)";
    case "BID_RESERVED": return "Bid reserved";
    case "BID_RELEASED": return "Bid released";
    case "ESCROW_DEBIT": return "Escrow funded";
    case "ESCROW_REFUND": return "Escrow refund";
    case "LISTING_FEE_DEBIT": return "Listing fee paid";
    case "LISTING_FEE_REFUND": return "Listing fee refund";
    case "PENALTY_DEBIT": return "Penalty paid";
    case "ADJUSTMENT": return "Adjustment";
  }
}

type EntryVisual = {
  Icon: ComponentType<{ className?: string }>;
  tone: string;
};

const WITHDRAW_PENDING_VISUAL: EntryVisual = { Icon: Clock, tone: "text-warning-flat" };
const WITHDRAW_COMPLETED_VISUAL: EntryVisual = { Icon: ArrowUpFromLine, tone: "text-success-flat" };
const WITHDRAW_REVERSED_VISUAL: EntryVisual = { Icon: Undo2, tone: "text-danger-flat" };

function withdrawalVisual(status: WithdrawalStatus | null): EntryVisual {
  switch (status) {
    case "COMPLETED": return WITHDRAW_COMPLETED_VISUAL;
    case "REVERSED":  return WITHDRAW_REVERSED_VISUAL;
    case "PENDING":
    default:          return WITHDRAW_PENDING_VISUAL;
  }
}

/**
 * Maps ledger entries to a lucide icon + a Material-3 colour token. The
 * single "Withdrawal" row is colour-coded by `withdrawalStatus`:
 * yellow Clock while pending, green ArrowUpFromLine when completed,
 * red Undo2 if reversed.
 */
function entryVisual(e: LedgerEntry): EntryVisual {
  switch (e.entryType) {
    case "DEPOSIT":
      return { Icon: ArrowDownToLine, tone: "text-success-flat" };
    case "WITHDRAW_QUEUED":
      return withdrawalVisual(e.withdrawalStatus);
    case "WITHDRAW_COMPLETED":
      return WITHDRAW_COMPLETED_VISUAL;
    case "WITHDRAW_REVERSED":
      return WITHDRAW_REVERSED_VISUAL;
    case "BID_RESERVED":
      return { Icon: Lock, tone: "text-warning-flat" };
    case "BID_RELEASED":
      return { Icon: Unlock, tone: "text-fg-muted" };
    case "ESCROW_DEBIT":
      return { Icon: ArrowUpFromLine, tone: "text-fg" };
    case "ESCROW_REFUND":
      return { Icon: ArrowDownToLine, tone: "text-success-flat" };
    case "LISTING_FEE_DEBIT":
      return { Icon: Tag, tone: "text-fg" };
    case "LISTING_FEE_REFUND":
      return { Icon: ArrowDownToLine, tone: "text-success-flat" };
    case "PENALTY_DEBIT":
      return { Icon: AlertTriangle, tone: "text-warning-flat" };
    case "ADJUSTMENT":
      return { Icon: Pencil, tone: "text-fg-muted" };
  }
}

/**
 * Recent-ledger row date label. Within the last 24 hours we render
 * a relative string ({@code "3m ago"}); older entries fall back to a
 * compact absolute date-time. Title attribute always holds the
 * absolute-locale string so a hover surfaces the precise instant.
 */
function formatLedgerDate(iso: string): string {
  const d = new Date(iso);
  if (!Number.isFinite(d.getTime())) return iso;
  const hoursAgo = (Date.now() - d.getTime()) / (1000 * 60 * 60);
  if (hoursAgo < 24) {
    return formatRelativeTime(d);
  }
  return d.toLocaleString(undefined, {
    year: "numeric",
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

export interface LedgerTableProps {
  entries: LedgerEntry[];
  isLoading?: boolean;
}

/**
 * Renders the wallet activity list — one row per {@link LedgerEntry}, with
 * type icon + colour tone, label, relative-then-absolute date, signed
 * amount, and the post-row balance / reserved snapshot. Empty state owns
 * its "No activity yet" card so the parent panel only has to render this
 * component.
 *
 * Loading is signalled by {@code isLoading} — the component currently
 * suppresses the empty card while loading, leaving the parent's spinner /
 * skeleton to do the talking. When {@code entries.length === 0 &&
 * !isLoading}, the empty state appears.
 */
export function LedgerTable({ entries, isLoading = false }: LedgerTableProps) {
  if (entries.length === 0 && !isLoading) {
    return (
      <div className="flex flex-col items-center text-center py-8 gap-3">
        <Wallet className="h-12 w-12 text-fg-muted/40" />
        <h3 className="font-medium text-fg">No activity yet</h3>
        <p className="text-sm text-fg-muted max-w-sm">
          Visit any SLPA Terminal in-world to make your first deposit.
          Locations: SLPA HQ and partner auction venues.
        </p>
      </div>
    );
  }

  if (entries.length === 0 && isLoading) {
    return (
      <div className="text-sm text-fg-muted py-8 text-center">
        Loading activity...
      </div>
    );
  }

  return (
    <ul className="text-sm">
      {entries.map((e) => {
        const { Icon, tone } = entryVisual(e);
        return (
          <li
            key={e.id}
            className={cn(
              "flex justify-between items-start gap-3 px-2 py-2 rounded-md",
              "border-b border-border-subtle last:border-b-0",
              "hover:bg-bg-subtle",
            )}
          >
            <div className="flex items-start min-w-0">
              <Icon
                className={cn("h-4 w-4 mr-2 mt-0.5 shrink-0", tone)}
                aria-hidden="true"
              />
              <div className="min-w-0">
                <div className="font-medium text-fg truncate">
                  {entryTypeLabel(e)}
                </div>
                <div
                  className="text-xs text-fg-muted"
                  title={new Date(e.createdAt).toLocaleString()}
                >
                  {formatLedgerDate(e.createdAt)}
                </div>
              </div>
            </div>
            <div className="text-right shrink-0">
              <div className={cn("tabular-nums", tone)}>
                {formatLindens(e.amount)}
              </div>
              <div className="text-xs text-fg-muted tabular-nums">
                Bal {formatLindens(e.balanceAfter)} / Res {formatLindens(e.reservedAfter)}
              </div>
            </div>
          </li>
        );
      })}
    </ul>
  );
}
