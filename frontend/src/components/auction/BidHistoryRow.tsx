"use client";

import Link from "next/link";
import { cn } from "@/lib/cn";
import { Avatar } from "@/components/ui/Avatar";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { Shield } from "@/components/ui/icons";
import {
  formatAbsoluteTime,
  formatRelativeTime,
} from "@/lib/time/relativeTime";
import type { BidHistoryEntry } from "@/types/auction";

export interface BidHistoryRowProps {
  entry: BidHistoryEntry;
  /**
   * When {@code true}, the row renders with a brief "freshly-arrived"
   * pulse animation. Drive this from {@link BidHistoryList} — it tracks
   * which {@code bidId}s came from the envelope merger vs. the paged
   * fetch and flips the flag back to false after ~2s.
   */
  isAnimated?: boolean;
}

/**
 * Single entry in the bid-history list. Layout (LTR):
 *
 * <pre>
 *   [avatar] [name + chips]                       [amount]
 *                                                 [2m ago]
 * </pre>
 *
 * Type chips follow spec §10:
 * <ul>
 *   <li>{@code MANUAL}      — no chip (default rendering)</li>
 *   <li>{@code PROXY_AUTO}  — small gray "proxy"</li>
 *   <li>{@code BUY_NOW}     — gold "buy now"</li>
 * </ul>
 *
 * A snipe-extension chip appears alongside the type chip when
 * {@code entry.snipeExtensionMinutes != null} — the backend stamps
 * that field on the row that triggered a snipe-protection extension.
 */
export function BidHistoryRow({ entry, isAnimated }: BidHistoryRowProps) {
  const absolute = formatAbsoluteTime(entry.createdAt);
  const relative = formatRelativeTime(entry.createdAt);

  return (
    <li
      data-testid="bid-history-row"
      data-bid-id={entry.bidId}
      data-animated={isAnimated ? "true" : undefined}
      className={cn(
        "flex items-center gap-3 rounded-default bg-surface-container-lowest px-3 py-2",
        "transition-colors",
        isAnimated && "animate-pulse bg-primary-container/40",
      )}
    >
      <Link
        href={`/users/${entry.userId}`}
        className="shrink-0"
        aria-label={`View ${entry.bidderDisplayName}'s profile`}
      >
        <Avatar
          alt={entry.bidderDisplayName}
          name={entry.bidderDisplayName}
          size="sm"
        />
      </Link>

      <div className="flex min-w-0 flex-1 flex-col gap-1">
        <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
          <Link
            href={`/users/${entry.userId}`}
            className="text-title-sm font-semibold text-on-surface hover:underline underline-offset-2"
            data-testid="bid-history-row-name"
          >
            {entry.bidderDisplayName}
          </Link>
          <BidTypeChip type={entry.bidType} />
          {entry.snipeExtensionMinutes != null ? (
            <StatusBadge
              tone="warning"
              data-testid="bid-history-row-snipe-chip"
              className="text-label-sm"
            >
              <Shield className="size-3" aria-hidden="true" />
              Extended {entry.snipeExtensionMinutes}m
            </StatusBadge>
          ) : null}
        </div>
        <span
          className="text-label-sm text-on-surface-variant"
          title={absolute}
          data-testid="bid-history-row-time"
        >
          {relative}
        </span>
      </div>

      <span
        className="text-title-md font-bold text-on-surface tabular-nums"
        data-testid="bid-history-row-amount"
      >
        L${entry.amount.toLocaleString()}
      </span>
    </li>
  );
}

/**
 * Type-chip selector. Manual bids render nothing — they're the default,
 * and showing a chip for every row would just add visual noise.
 */
function BidTypeChip({
  type,
}: {
  type: BidHistoryEntry["bidType"];
}) {
  if (type === "MANUAL") return null;
  if (type === "PROXY_AUTO") {
    return (
      <StatusBadge
        tone="default"
        data-testid="bid-history-row-type-chip"
        data-type="PROXY_AUTO"
        className="text-label-sm"
      >
        proxy
      </StatusBadge>
    );
  }
  // BUY_NOW — gold styling via the warning tone (secondary-container gold
  // palette matches spec §10's "gold" cue).
  return (
    <StatusBadge
      tone="warning"
      data-testid="bid-history-row-type-chip"
      data-type="BUY_NOW"
      className="text-label-sm"
    >
      buy now
    </StatusBadge>
  );
}
