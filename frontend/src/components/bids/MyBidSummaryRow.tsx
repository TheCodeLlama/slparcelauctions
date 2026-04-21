"use client";

import Link from "next/link";
import { Building2 } from "@/components/ui/icons";
import { CountdownTimer } from "@/components/ui/CountdownTimer";
import { cn } from "@/lib/cn";
import type { MyBidStatus, MyBidSummary } from "@/types/auction";
import { MyBidStatusBadge } from "./MyBidStatusBadge";

export interface MyBidSummaryRowProps {
  bid: MyBidSummary;
  className?: string;
}

/**
 * 4px left-border color mapping for the seven {@link MyBidStatus} buckets
 * (spec §12). Picks from the project's Material-3 token palette; the chip
 * tones on {@link MyBidStatusBadge} share the same colours so the row reads
 * coherently at a glance.
 *
 * <ul>
 *   <li>{@code WINNING} — tertiary-container (blue) = "leading".</li>
 *   <li>{@code OUTBID} / {@code SUSPENDED} — error (red) = "act now".</li>
 *   <li>{@code WON} — primary (gold) = "celebrate".</li>
 *   <li>{@code RESERVE_NOT_MET} — secondary-container (orange) = "warning".</li>
 *   <li>{@code LOST} / {@code CANCELLED} — on-surface-variant (gray) =
 *       "terminal, neutral".</li>
 * </ul>
 */
const BORDER_CLASS_BY_STATUS: Record<MyBidStatus, string> = {
  WINNING: "border-l-tertiary-container",
  OUTBID: "border-l-error",
  WON: "border-l-primary",
  LOST: "border-l-on-surface-variant",
  RESERVE_NOT_MET: "border-l-secondary-container",
  CANCELLED: "border-l-on-surface-variant",
  SUSPENDED: "border-l-error",
};

/**
 * Parcel-name strikethrough is reserved for the two "invalidated" statuses.
 * CANCELLED and SUSPENDED listings no longer settle through escrow even if
 * the caller was the high bidder, so the name is struck to signal that
 * visually without the full-row gray-out of a LOST auction.
 */
const STRIKE_STATUSES: ReadonlySet<MyBidStatus> = new Set([
  "CANCELLED",
  "SUSPENDED",
]);

/**
 * Single row in the My Bids dashboard (spec §12). Composition:
 *   thumbnail + parcel meta (name / region / area / time-remaining)
 *   + status chip + right-aligned bid column (Your bid / Current / Proxy max).
 *
 * The 4px colored left border carries the seven-way
 * {@link MyBidStatus} distinction — the
 * {@link MyBidStatusBadge} chip repeats the status for screen-reader users
 * and low-vision viewers. Clicking anywhere on the row navigates to the
 * public auction page; action buttons sit outside this component.
 */
export function MyBidSummaryRow({ bid, className }: MyBidSummaryRowProps) {
  const { auction, myHighestBidAmount, myProxyMaxAmount, myBidStatus } = bid;
  const parcelLabel = auction.parcelName?.trim() || "(unnamed parcel)";
  const striked = STRIKE_STATUSES.has(myBidStatus);
  const thumb = auction.snapshotUrl;
  const endsAtDate = parseDate(auction.endsAt);
  const isActive = auction.status === "ACTIVE" && endsAtDate != null;
  const currentBid = auction.currentBid;

  return (
    <li
      className={cn(
        "rounded-default border border-outline-variant bg-surface-container-lowest",
        "border-l-4",
        BORDER_CLASS_BY_STATUS[myBidStatus],
        className,
      )}
      data-testid={`my-bid-row-${auction.id}`}
    >
      <Link
        href={`/auction/${auction.id}`}
        className="flex items-start gap-3 p-3 hover:bg-surface-container-low focus-visible:bg-surface-container-low focus-visible:outline-none"
      >
        <Thumbnail src={thumb} alt="" />
        <div className="flex min-w-0 flex-1 flex-col gap-1">
          <div className="flex flex-wrap items-center gap-2">
            <h3
              className={cn(
                "text-title-sm text-on-surface truncate",
                striked && "line-through text-on-surface-variant",
              )}
            >
              {parcelLabel}
            </h3>
            <MyBidStatusBadge status={myBidStatus} />
          </div>
          <p className="text-body-sm text-on-surface-variant">
            <span>
              {auction.parcelRegion ?? "—"}
              {auction.parcelAreaSqm != null
                ? ` · ${auction.parcelAreaSqm.toLocaleString()} m²`
                : ""}
              {isActive ? " · " : ""}
            </span>
            {isActive ? (
              <>
                <CountdownTimer
                  expiresAt={endsAtDate}
                  format="hh:mm:ss"
                  className="inline text-body-sm"
                />
                {" left"}
              </>
            ) : null}
          </p>
        </div>
        <div className="flex flex-col items-end gap-0.5 text-body-sm">
          <span className="text-on-surface-variant">
            Your bid{" "}
            <span className="font-semibold text-on-surface">
              {`L$${myHighestBidAmount.toLocaleString()}`}
            </span>
          </span>
          <span className="text-on-surface-variant">
            Current{" "}
            <span className="font-semibold text-on-surface">
              {currentBid == null ? "—" : `L$${currentBid.toLocaleString()}`}
            </span>
          </span>
          {myProxyMaxAmount != null ? (
            <span className="text-on-surface-variant">
              Proxy max{" "}
              <span className="font-semibold text-on-surface">
                {`L$${myProxyMaxAmount.toLocaleString()}`}
              </span>
            </span>
          ) : null}
        </div>
      </Link>
    </li>
  );
}

function Thumbnail({ src, alt }: { src: string | null; alt: string }) {
  const box = "size-14 shrink-0 rounded-default";
  if (src) {
    return (
      /* eslint-disable-next-line @next/next/no-img-element -- snapshot URL is remote */
      <img src={src} alt={alt} className={cn(box, "object-cover")} />
    );
  }
  return (
    <div
      className={cn(
        box,
        "flex items-center justify-center bg-surface-container-high text-on-surface-variant",
      )}
      aria-hidden="true"
    >
      <Building2 className="size-6" />
    </div>
  );
}

function parseDate(s: string | null | undefined): Date | null {
  if (!s) return null;
  const d = new Date(s);
  return Number.isNaN(d.getTime()) ? null : d;
}
