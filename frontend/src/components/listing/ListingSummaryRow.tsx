"use client";

import { useState } from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import {
  AlertTriangle,
  Building2,
  Edit,
  ExternalLink,
  MoreHorizontal,
} from "@/components/ui/icons";
import { Button } from "@/components/ui/Button";
import { CountdownTimer } from "@/components/ui/CountdownTimer";
import { Dropdown, type DropdownItem } from "@/components/ui/Dropdown";
import { IconButton } from "@/components/ui/IconButton";
import { EscrowChip } from "@/components/escrow/EscrowChip";
import { cn } from "@/lib/cn";
import { apiUrl } from "@/lib/api/url";
import { resolveListingHeadline } from "@/lib/listing/resolveListingHeadline";
import { userApi, type PublicUserProfile } from "@/lib/user/api";
import type {
  AuctionEndOutcome,
  SellerAuctionResponse,
} from "@/types/auction";
import { ListingStatusBadge } from "./ListingStatusBadge";
import { CancelListingModal } from "./CancelListingModal";

/**
 * Shape of the extended fields that may land on a seller auction DTO once
 * Epic 04's auction-end pipeline stamps terminal-outcome metadata. Treated
 * as a soft-widening of {@link SellerAuctionResponse} so
 * {@link ListingSummaryRow} reads them defensively — the backend DTO
 * enrichment lands in a follow-up (see DEFERRED_WORK.md "SellerAuctionResponse
 * end-outcome fields" once Epic 05 broadens the response shape).
 */
type ListingRowAuction = SellerAuctionResponse & {
  endOutcome?: AuctionEndOutcome | null;
  finalBidAmount?: number | null;
  winnerDisplayName?: string | null;
};

export interface ListingSummaryRowProps {
  auction: SellerAuctionResponse;
  className?: string;
}

/**
 * One row in the My Listings dashboard table.
 *
 * Composition (spec §6.3):
 *   thumbnail + parcel name + status badge + region/area + bid summary
 *   + per-status action group
 *
 * Per-status actions (spec §6.3 matrix):
 *   DRAFT / DRAFT_PAID                               → Edit + Continue + cancel
 *   VERIFICATION_PENDING / VERIFICATION_FAILED       → Continue + cancel
 *   ACTIVE                                           → View listing + cancel
 *   ENDED / ESCROW_* / TRANSFER_PENDING / COMPLETED /
 *   EXPIRED                                          → View listing
 *   CANCELLED / DISPUTED / SUSPENDED                 → View details
 *
 * SUSPENDED rows also render a full-width red inline callout below the
 * row directing the seller to contact support (spec §6.3 footnote).
 *
 * Thumbnail fallback chain: first uploaded photo → parcel.snapshotUrl →
 * generic building icon. Matches the spec §6.3 fallback rules.
 */
export function ListingSummaryRow({
  auction,
  className,
}: ListingSummaryRowProps) {
  const [cancelOpen, setCancelOpen] = useState(false);
  const thumb = apiUrl(auction.photos[0]?.url ?? auction.parcel.snapshotUrl);
  // Seller-authored title is the primary row label. Parcel name +
  // region fall through as the secondary line (spec §6.3 post sub-spec 2).
  // Legacy rows with no title fall back to parcel.description so pre-
  // Epic 07 listings keep a meaningful label. Shared resolver keeps the
  // chain in sync with ListingPreviewCard + ParcelInfoPanel; the
  // "(unnamed parcel)" tail is a defensive guard — regionName should
  // always be present server-side.
  const primaryLabel =
    resolveListingHeadline({
      title: auction.title,
      parcelDescription: auction.parcel.description,
      regionName: auction.parcel.regionName,
    }) || "(unnamed parcel)";
  const parcelLabel = auction.parcel.description?.trim();
  const secondaryParts = [
    parcelLabel && parcelLabel !== primaryLabel ? parcelLabel : null,
    auction.parcel.regionName,
    `${auction.parcel.areaSqm} m²`,
  ].filter((s): s is string => typeof s === "string" && s.length > 0);
  const secondaryLabel = secondaryParts.join(" · ");
  const isSuspended = auction.status === "SUSPENDED";

  return (
    <li
      className={cn(
        "flex flex-col gap-2 rounded-lg border border-border-subtle bg-surface-raised p-3",
        className,
      )}
      data-testid={`listing-row-${auction.publicId}`}
    >
      <div className="flex items-start gap-3">
        <Thumbnail src={thumb} alt="" />
        <div className="flex min-w-0 flex-1 flex-col gap-1">
          <div className="flex flex-wrap items-center gap-2">
            <h3
              className="text-sm font-semibold text-fg truncate"
              data-testid="listing-summary-primary"
            >
              {primaryLabel}
            </h3>
            <ListingStatusBadge status={auction.status} />
            {auction.escrowState != null && (
              <EscrowChip
                state={auction.escrowState}
                transferConfirmedAt={auction.transferConfirmedAt}
                role="seller"
                size="sm"
              />
            )}
          </div>
          <p
            className="text-xs text-fg-muted"
            data-testid="listing-summary-secondary"
          >
            {secondaryLabel}
          </p>
          <BidSummaryLine auction={auction} />
        </div>
        <div className="flex items-center gap-2">
          <PrimaryActions
            auction={auction}
            onOpenCancel={() => setCancelOpen(true)}
          />
        </div>
      </div>
      {isSuspended && (
        <div
          role="alert"
          className="flex items-start gap-2 rounded-lg bg-danger-bg p-2.5 text-xs text-danger"
        >
          <AlertTriangle
            className="mt-0.5 size-4 shrink-0"
            aria-hidden="true"
          />
          <span>
            Listing suspended. Contact support if you believe this is a mistake.
          </span>
        </div>
      )}
      <CancelListingModal
        open={cancelOpen}
        onClose={() => setCancelOpen(false)}
        auction={auction}
      />
    </li>
  );
}

function Thumbnail({ src, alt }: { src: string | null; alt: string }) {
  const box = "size-14 shrink-0 rounded-lg";
  if (src) {
    return (
      /* eslint-disable-next-line @next/next/no-img-element -- MinIO-served bytes */
      <img
        src={src}
        alt={alt}
        className={cn(box, "object-cover")}
      />
    );
  }
  return (
    <div
      className={cn(
        box,
        "flex items-center justify-center bg-bg-hover text-fg-muted",
      )}
      aria-hidden="true"
    >
      <Building2 className="size-6" />
    </div>
  );
}

/**
 * Renders the status-conditional sub-line beneath the region/area row (spec
 * §13).
 *
 * <ul>
 *   <li>{@code ACTIVE}: {@code L$42,500 current · 12 bids · 2h 14m left}</li>
 *   <li>{@code ENDED} + SOLD/BOUGHT_NOW: {@code Sold for L$X to @winner}</li>
 *   <li>{@code ENDED} + RESERVE_NOT_MET: {@code Ended — reserve not met
 *       (highest bid L$X)}</li>
 *   <li>{@code ENDED} + NO_BIDS: {@code Ended with no bids}</li>
 *   <li>All other statuses (DRAFT, VERIFICATION_*, CANCELLED, SUSPENDED, escrow
 *       in-flight, etc.): no sub-line — keeps the row short for the seller
 *       who is mid-setup or post-terminal and doesn't benefit from a
 *       bid-summary repeat.</li>
 * </ul>
 *
 * ENDED rows read {@code endOutcome} directly from the DTO. Backend always
 * projects this field post Epic 05 sub-spec 1; a null value on an ENDED
 * auction is a backend invariant violation and surfaces as an error rather
 * than being papered over with a heuristic.
 */
function BidSummaryLine({ auction }: { auction: ListingRowAuction }) {
  const highBid = normalizeBid(auction.currentHighBid);

  if (auction.status === "ACTIVE") {
    return <ActiveBidSummary auction={auction} highBid={highBid} />;
  }
  if (auction.status === "ENDED") {
    return <EndedBidSummary auction={auction} highBid={highBid} />;
  }
  return null;
}

function ActiveBidSummary({
  auction,
  highBid,
}: {
  auction: ListingRowAuction;
  highBid: number | null;
}) {
  const bidCount = auction.bidCount ?? 0;
  const bidsText = bidCount === 1 ? "1 bid" : `${bidCount} bids`;
  const endsAtDate = parseDate(auction.endsAt);
  return (
    <p className="text-xs text-fg-muted">
      <span className="font-medium text-fg">
        {highBid == null ? "-" : `L$${highBid.toLocaleString()}`}
      </span>
      <span>{` current · ${bidsText}`}</span>
      {endsAtDate != null ? (
        <>
          {" · "}
          <CountdownTimer
            expiresAt={endsAtDate}
            format="hh:mm:ss"
            className="inline text-xs"
          />
          {" left"}
        </>
      ) : null}
    </p>
  );
}

function EndedBidSummary({
  auction,
  highBid,
}: {
  auction: ListingRowAuction;
  highBid: number | null;
}) {
  // Backend always projects endOutcome on ENDED auctions post Epic 05
  // sub-spec 1. If this field is ever null on an ENDED auction, it's a
  // backend invariant violation — let it surface rather than papering
  // over it with a heuristic.
  if (auction.endOutcome == null) {
    throw new Error(
      `ListingSummaryRow rendered ENDED auction ${auction.publicId} with null endOutcome — ` +
        "backend enrichment invariant violated (Epic 05 sub-spec 1).",
    );
  }
  const outcome: AuctionEndOutcome = auction.endOutcome;

  // Winner display-name fallback mirrors AuctionEndedPanel. The query runs
  // only when we have a winnerId but no inline display name. Same cache key
  // as AuctionEndedPanel so the two share any pre-fetched profile data.
  const winnerPublicId = auction.winnerPublicId;
  const needWinnerFetch =
    (outcome === "SOLD" || outcome === "BOUGHT_NOW") &&
    winnerPublicId != null &&
    (auction.winnerDisplayName == null || auction.winnerDisplayName === "");
  const winnerQuery = useQuery<PublicUserProfile>({
    queryKey: ["publicProfile", winnerPublicId],
    queryFn: () => userApi.publicProfile(winnerPublicId as string),
    enabled: needWinnerFetch,
    staleTime: 60_000,
  });
  const winnerDisplayName =
    auction.winnerDisplayName ??
    winnerQuery.data?.displayName ??
    (winnerPublicId != null ? `User ${winnerPublicId.slice(0, 8)}` : null);

  if (outcome === "SOLD" || outcome === "BOUGHT_NOW") {
    const finalAmount = auction.finalBidAmount ?? highBid;
    const formattedAmount = finalAmount == null ? "-" : `L$${finalAmount.toLocaleString()}`;
    return (
      <p className="text-xs text-fg-muted">
        Sold for{" "}
        <span className="font-medium text-fg">{formattedAmount}</span>
        {winnerDisplayName ? (
          <>
            {" to "}
            <span className="font-medium text-fg">{`@${winnerDisplayName}`}</span>
          </>
        ) : null}
      </p>
    );
  }
  if (outcome === "RESERVE_NOT_MET") {
    const formattedBid = highBid == null ? "-" : `L$${highBid.toLocaleString()}`;
    return (
      <p className="text-xs text-fg-muted">
        {"Ended: reserve not met (highest bid "}
        <span className="font-medium text-fg">{formattedBid}</span>
        {")"}
      </p>
    );
  }
  // NO_BIDS (default for ENDED without bids)
  return (
    <p className="text-xs text-fg-muted">
      Ended with no bids
    </p>
  );
}

function parseDate(s: string | null | undefined): Date | null {
  if (!s) return null;
  const d = new Date(s);
  return Number.isNaN(d.getTime()) ? null : d;
}

function normalizeBid(raw: number | string | null): number | null {
  if (raw == null) return null;
  if (typeof raw === "number") {
    return raw > 0 ? raw : null;
  }
  const n = Number(raw);
  if (Number.isNaN(n) || n <= 0) return null;
  return n;
}

function PrimaryActions({
  auction,
  onOpenCancel,
}: {
  auction: ListingRowAuction;
  onOpenCancel: () => void;
}) {
  const id = auction.publicId;
  const publicHref = `/auction/${id}`;
  const escrowHref = `/auction/${id}/escrow`;
  const editHref = `/listings/${id}/edit`;
  const activateHref = `/listings/${id}/activate`;
  const hasEscrow = auction.escrowState != null;

  const cancelItem: DropdownItem = {
    label: "Cancel listing",
    onSelect: onOpenCancel,
    danger: true,
  };

  switch (auction.status) {
    case "DRAFT":
    case "DRAFT_PAID":
      return (
        <>
          <Link href={editHref}>
            <Button variant="secondary" size="sm" leftIcon={<Edit className="size-4" />}>
              Edit
            </Button>
          </Link>
          <Link href={activateHref}>
            <Button size="sm">Continue</Button>
          </Link>
          <OverflowMenu items={[cancelItem]} />
        </>
      );
    case "VERIFICATION_PENDING":
    case "VERIFICATION_FAILED":
      return (
        <>
          <Link href={activateHref}>
            <Button size="sm">Continue</Button>
          </Link>
          <OverflowMenu items={[cancelItem]} />
        </>
      );
    case "ACTIVE":
      return (
        <>
          <Link href={publicHref}>
            <Button
              variant="secondary"
              size="sm"
              leftIcon={<ExternalLink className="size-4" />}
            >
              View listing
            </Button>
          </Link>
          <OverflowMenu items={[cancelItem]} />
        </>
      );
    case "ENDED":
    case "ESCROW_PENDING":
    case "ESCROW_FUNDED":
    case "TRANSFER_PENDING":
    case "COMPLETED":
    case "EXPIRED":
      return (
        <Link href={hasEscrow ? escrowHref : publicHref}>
          <Button
            variant="secondary"
            size="sm"
            leftIcon={<ExternalLink className="size-4" />}
          >
            {hasEscrow ? "View escrow" : "View listing"}
          </Button>
        </Link>
      );
    case "CANCELLED":
    case "DISPUTED":
    case "SUSPENDED":
      // View-details link points at the public auction page (spec §6.3
      // footnote — may be a dead link until Epic 04 wires the public
      // listing page). When an escrow exists (typically DISPUTED) we route
      // the seller directly to the escrow page instead.
      return (
        <Link href={hasEscrow ? escrowHref : publicHref}>
          <Button variant="secondary" size="sm">
            {hasEscrow ? "View escrow" : "View details"}
          </Button>
        </Link>
      );
  }
}

function OverflowMenu({ items }: { items: DropdownItem[] }) {
  return (
    <Dropdown
      trigger={
        <IconButton
          variant="secondary"
          size="sm"
          aria-label="More actions"
        >
          <MoreHorizontal className="size-4" />
        </IconButton>
      }
      items={items}
    />
  );
}
