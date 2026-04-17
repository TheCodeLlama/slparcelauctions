"use client";

import { useState } from "react";
import Link from "next/link";
import {
  AlertTriangle,
  Building2,
  Edit,
  ExternalLink,
  MoreHorizontal,
} from "@/components/ui/icons";
import { Button } from "@/components/ui/Button";
import { Dropdown, type DropdownItem } from "@/components/ui/Dropdown";
import { IconButton } from "@/components/ui/IconButton";
import { cn } from "@/lib/cn";
import type { SellerAuctionResponse } from "@/types/auction";
import { ListingStatusBadge } from "./ListingStatusBadge";
import { CancelListingModal } from "./CancelListingModal";

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
  const thumb = auction.photos[0]?.url ?? auction.parcel.snapshotUrl ?? null;
  const title = auction.parcel.description?.trim() || "(unnamed parcel)";
  const isSuspended = auction.status === "SUSPENDED";

  return (
    <li
      className={cn(
        "flex flex-col gap-2 rounded-default border border-outline-variant bg-surface-container-lowest p-3",
        className,
      )}
      data-testid={`listing-row-${auction.id}`}
    >
      <div className="flex items-start gap-3">
        <Thumbnail src={thumb} alt="" />
        <div className="flex min-w-0 flex-1 flex-col gap-1">
          <div className="flex flex-wrap items-center gap-2">
            <h3 className="text-title-sm text-on-surface truncate">{title}</h3>
            <ListingStatusBadge status={auction.status} />
          </div>
          <p className="text-body-sm text-on-surface-variant">
            {auction.parcel.regionName} · {auction.parcel.areaSqm} m²
          </p>
          <BidSummaryLine
            currentHighBid={auction.currentHighBid}
            bidderCount={auction.bidderCount}
          />
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
          className="flex items-start gap-2 rounded-default bg-error-container p-2.5 text-body-sm text-on-error-container"
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
  const box = "size-14 shrink-0 rounded-default";
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
        "flex items-center justify-center bg-surface-container-high text-on-surface-variant",
      )}
      aria-hidden="true"
    >
      <Building2 className="size-6" />
    </div>
  );
}

function BidSummaryLine({
  currentHighBid,
  bidderCount,
}: {
  currentHighBid: number | string | null;
  bidderCount: number;
}) {
  const highBid = normalizeBid(currentHighBid);
  const bidderText =
    bidderCount === 1 ? "1 bidder" : `${bidderCount} bidders`;
  return (
    <p className="text-body-sm text-on-surface-variant">
      <span className="font-medium text-on-surface">
        {highBid == null ? "—" : `L$${highBid}`}
      </span>
      <span> · {bidderText}</span>
    </p>
  );
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
  auction: SellerAuctionResponse;
  onOpenCancel: () => void;
}) {
  const id = auction.id;
  const publicHref = `/auction/${id}`;
  const editHref = `/listings/${id}/edit`;
  const activateHref = `/listings/${id}/activate`;

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
        <Link href={publicHref}>
          <Button
            variant="secondary"
            size="sm"
            leftIcon={<ExternalLink className="size-4" />}
          >
            View listing
          </Button>
        </Link>
      );
    case "CANCELLED":
    case "DISPUTED":
    case "SUSPENDED":
      // View-details link points at the public auction page (spec §6.3
      // footnote — may be a dead link until Epic 04 wires the public
      // listing page).
      return (
        <Link href={publicHref}>
          <Button variant="secondary" size="sm">
            View details
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
