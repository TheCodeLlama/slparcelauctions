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
import type { LedgerEntry } from "@/types/wallet";

function formatLindens(amount: number): string {
  return `L$${amount.toLocaleString()}`;
}

function entryTypeLabel(t: LedgerEntry["entryType"]): string {
  switch (t) {
    case "DEPOSIT": return "Deposit";
    case "WITHDRAW_QUEUED": return "Withdraw queued";
    case "WITHDRAW_COMPLETED": return "Withdraw completed";
    case "WITHDRAW_REVERSED": return "Withdraw reversed";
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

/**
 * Maps ledger entry types to a lucide icon + a Material-3 colour token
 * pair. Inflows (deposit / refund) use {@code text-success}; outflows that
 * are user-initiated and final use the neutral {@code text-on-surface};
 * "in-progress" or warning-tinted entries (queued withdraw, reserved bid,
 * penalty) use {@code text-warning}.
 */
function entryVisual(t: LedgerEntry["entryType"]): EntryVisual {
  switch (t) {
    case "DEPOSIT":
      return { Icon: ArrowDownToLine, tone: "text-success" };
    case "WITHDRAW_QUEUED":
      return { Icon: Clock, tone: "text-on-surface-variant" };
    case "WITHDRAW_COMPLETED":
      return { Icon: ArrowUpFromLine, tone: "text-on-surface" };
    case "WITHDRAW_REVERSED":
      return { Icon: Undo2, tone: "text-warning" };
    case "BID_RESERVED":
      return { Icon: Lock, tone: "text-warning" };
    case "BID_RELEASED":
      return { Icon: Unlock, tone: "text-on-surface-variant" };
    case "ESCROW_DEBIT":
      return { Icon: ArrowUpFromLine, tone: "text-on-surface" };
    case "ESCROW_REFUND":
      return { Icon: ArrowDownToLine, tone: "text-success" };
    case "LISTING_FEE_DEBIT":
      return { Icon: Tag, tone: "text-on-surface" };
    case "LISTING_FEE_REFUND":
      return { Icon: ArrowDownToLine, tone: "text-success" };
    case "PENALTY_DEBIT":
      return { Icon: AlertTriangle, tone: "text-warning" };
    case "ADJUSTMENT":
      return { Icon: Pencil, tone: "text-on-surface-variant" };
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
        <Wallet className="h-12 w-12 text-on-surface-variant/40" />
        <h3 className="font-medium text-on-surface">No activity yet</h3>
        <p className="text-sm text-on-surface-variant max-w-sm">
          Visit any SLPA Terminal in-world to make your first deposit.
          Locations: SLPA HQ and partner auction venues.
        </p>
      </div>
    );
  }

  if (entries.length === 0 && isLoading) {
    return (
      <div className="text-sm text-on-surface-variant py-8 text-center">
        Loading activity...
      </div>
    );
  }

  return (
    <ul className="text-sm">
      {entries.map((e) => {
        const { Icon, tone } = entryVisual(e.entryType);
        return (
          <li
            key={e.id}
            className={cn(
              "flex justify-between items-start gap-3 px-2 py-2 rounded-md",
              "border-b border-outline-variant last:border-b-0",
              "hover:bg-surface-container-low",
            )}
          >
            <div className="flex items-start min-w-0">
              <Icon
                className={cn("h-4 w-4 mr-2 mt-0.5 shrink-0", tone)}
                aria-hidden="true"
              />
              <div className="min-w-0">
                <div className="font-medium text-on-surface truncate">
                  {entryTypeLabel(e.entryType)}
                </div>
                <div
                  className="text-xs text-on-surface-variant"
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
              <div className="text-xs text-on-surface-variant tabular-nums">
                Bal {formatLindens(e.balanceAfter)} / Res {formatLindens(e.reservedAfter)}
              </div>
            </div>
          </li>
        );
      })}
    </ul>
  );
}
