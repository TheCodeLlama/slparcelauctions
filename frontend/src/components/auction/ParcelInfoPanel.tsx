import { type ReactNode } from "react";
import { MapPin, Tag as TagIcon } from "@/components/ui/icons";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { cn } from "@/lib/cn";
import { resolveListingHeadline } from "@/lib/listing/resolveListingHeadline";
import type {
  PublicAuctionResponse,
  SellerAuctionResponse,
} from "@/types/auction";
import type { ParcelMaturityRating } from "@/types/parcel";
import { SnipeProtectionBadge } from "./SnipeProtectionBadge";
import { VerificationTierBadge } from "./VerificationTierBadge";
import { VisitInSecondLifeButton } from "./VisitInSecondLifeButton";

/**
 * Parcel-scoped metadata panel for the auction detail page.
 *
 * Renders the parcel title, region/area/maturity subline, badges row
 * (maturity + verification tier + snipe protection), tags row, and the
 * seller's free-form description. SELLER IDENTITY (avatar / display name /
 * rating) is intentionally excluded — that lives in a sibling
 * {@link SellerProfileCard}, which keeps the concerns cleanly split per
 * spec §8.
 *
 * Works with both {@link PublicAuctionResponse} and
 * {@link SellerAuctionResponse} — the read set (parcel, verificationTier,
 * snipeProtect, snipeWindowMin, tags, sellerDesc) is the union of what both
 * DTOs expose.
 *
 * The parcel title prefers {@code parcel.description}; falls back to the
 * region name when the seller hasn't supplied a description yet. The
 * {@code positionX/Y/Z} from the DTO aren't available today (only grid
 * coords + slurl) — we parse the region-local offset out of the slurl; if
 * that fails, we fall back to centre-of-region (128, 128, 25).
 */
interface Props {
  auction: PublicAuctionResponse | SellerAuctionResponse;
  className?: string;
  /** Optional action rendered in the title row (e.g. ReportListingButton). */
  reportButton?: ReactNode;
}

const MATURITY_MAP: Record<
  ParcelMaturityRating,
  { label: string; cls: string }
> = {
  GENERAL: {
    label: "General",
    cls: "bg-info-bg text-fg",
  },
  MODERATE: {
    label: "Moderate",
    cls: "bg-bg-muted text-fg",
  },
  ADULT: {
    label: "Adult",
    cls: "bg-danger-bg text-danger-flat",
  },
};

export function ParcelInfoPanel({ auction, className, reportButton }: Props) {
  const { parcel } = auction;
  // Seller-authored listing title wins the headline slot. Falls back to
  // parcel.description (legacy pre-sub-spec-2 listings) and ultimately
  // the region name when neither is set. Shared resolver keeps the same
  // fallback chain as ListingPreviewCard + ListingSummaryRow so the
  // three views stay consistent. No extra fallback needed —
  // regionName is always present server-side.
  const title = resolveListingHeadline({
    title: auction.title,
    parcelDescription: parcel.description,
    regionName: parcel.regionName,
  });
  const maturity = MATURITY_MAP[parcel.maturityRating];
  const showSnipe =
    auction.snipeProtect && auction.snipeWindowMin != null;
  const { x, y, z } = parseSlurlPosition(parcel.slurl);

  return (
    <section
      aria-label="Parcel details"
      className={cn("flex flex-col gap-5", className)}
      data-testid="parcel-info-panel"
    >
      <div className="flex items-start justify-between gap-4 flex-wrap">
        <div className="flex flex-col gap-2 min-w-0">
          <h1
            className="text-2xl font-bold tracking-tight font-display text-fg"
            data-testid="parcel-info-panel-title"
          >
            {title}
          </h1>
          <p
            className="flex flex-wrap items-center gap-2 text-sm text-fg-muted"
            data-testid="parcel-info-panel-subline"
          >
            <MapPin className="size-4" aria-hidden="true" />
            <span>{parcel.regionName}</span>
            <span aria-hidden="true">·</span>
            <span>{parcel.areaSqm.toLocaleString()} m²</span>
            <span aria-hidden="true">·</span>
            <span
              className={cn(
                "inline-flex items-center rounded-full px-2 py-0.5 text-[11px] font-medium",
                maturity.cls,
              )}
              data-testid="parcel-info-panel-maturity"
              data-maturity={parcel.maturityRating}
            >
              {maturity.label}
            </span>
          </p>
        </div>
        <div className="flex items-center gap-2 shrink-0">
          {reportButton}
          <VisitInSecondLifeButton
            regionName={parcel.regionName}
            positionX={x}
            positionY={y}
            positionZ={z}
          />
        </div>
      </div>

      <div
        className="flex flex-wrap items-center gap-2"
        data-testid="parcel-info-panel-badges"
      >
        <VerificationTierBadge tier={auction.verificationTier} />
        {showSnipe && (
          <SnipeProtectionBadge minutes={auction.snipeWindowMin!} />
        )}
      </div>

      {auction.tags.length > 0 && (
        <ul
          className="flex flex-wrap gap-1.5"
          data-testid="parcel-info-panel-tags"
        >
          {auction.tags.map((t) => (
            <li key={t.code}>
              <StatusBadge tone="default">
                <TagIcon className="size-3" aria-hidden="true" />
                {t.label}
              </StatusBadge>
            </li>
          ))}
        </ul>
      )}

      {auction.sellerDesc && (
        <p
          className="whitespace-pre-wrap text-base text-fg max-w-2xl"
          data-testid="parcel-info-panel-description"
        >
          {auction.sellerDesc}
        </p>
      )}
    </section>
  );
}

/**
 * Parses the region-local position (x, y, z) out of an SLURL. Accepts both
 * {@code secondlife://Region/x/y/z} and {@code https://maps.secondlife.com/secondlife/Region/x/y/z}
 * shapes — the backend can produce either depending on integration.
 * Falls back to centre-of-region (128, 128, 25) when the slurl is missing
 * or unparseable so the Visit-button still works.
 */
function parseSlurlPosition(slurl: string): {
  x: number;
  y: number;
  z: number;
} {
  const fallback = { x: 128, y: 128, z: 25 };
  if (!slurl) return fallback;

  try {
    // Last three numeric segments of the path — regardless of scheme.
    const parts = slurl.split(/[\/]/).filter(Boolean);
    const numeric = parts
      .slice(-3)
      .map((p) => Number(p))
      .filter((n) => Number.isFinite(n));
    if (numeric.length !== 3) return fallback;
    const [x, y, z] = numeric;
    return { x, y, z };
  } catch {
    return fallback;
  }
}
