import { MapPin, ExternalLink } from "@/components/ui/icons";
import { cn } from "@/lib/cn";
import { apiUrl } from "@/lib/api/url";
import type { ParcelDto, ParcelMaturityRating } from "@/types/parcel";

/**
 * Read-only summary of a looked-up parcel. Rendered by ParcelLookupField on
 * successful lookup, and also standalone on the Edit page where the parcel
 * is already resolved.
 *
 * Snapshot images come from the SL World API (absolute URL) — we use a
 * plain img tag to match the Avatar convention in this codebase (Next's
 * Image loader would require a remote host whitelist and can't be
 * persuaded to no-op on pure-relative URLs returned by the backend).
 */

/**
 * Maturity rating → M3 token mapping. Mirrors the convention used by
 * ListingStatusBadge (container/on-container pairs, never raw palette
 * classes) so light/dark themes stay consistent. GENERAL uses the
 * tertiary-container pair (the same "affirmative" token ACTIVE listings
 * use); MODERATE uses secondary-container (neutral emphasis); ADULT
 * uses error-container to flag the strongest content gate.
 */
const MATURITY_MAP: Record<
  ParcelMaturityRating,
  { label: string; cls: string }
> = {
  GENERAL: {
    label: "General",
    cls: "bg-info-bg text-info",
  },
  MODERATE: {
    label: "Moderate",
    cls: "bg-warning-bg text-warning",
  },
  ADULT: {
    label: "Adult",
    cls: "bg-danger-bg text-danger",
  },
};

export function ParcelLookupCard({
  parcel,
  className,
}: {
  parcel: ParcelDto;
  className?: string;
}) {
  const label = parcel.description?.trim() || "(unnamed parcel)";
  const maturity = MATURITY_MAP[parcel.maturityRating];
  return (
    <div
      data-testid="parcel-lookup-card"
      className={cn(
        "flex gap-4 rounded-lg border border-border-subtle bg-surface-raised p-4",
        className,
      )}
    >
      {parcel.snapshotUrl ? (
        /* eslint-disable-next-line @next/next/no-img-element */
        <img
          src={apiUrl(parcel.snapshotUrl) ?? undefined}
          alt=""
          className="h-20 w-20 flex-shrink-0 rounded-lg object-cover"
        />
      ) : (
        <div
          aria-hidden="true"
          className="h-20 w-20 flex-shrink-0 rounded-lg bg-bg-hover"
        />
      )}
      <div className="flex min-w-0 flex-1 flex-col gap-1">
        <div className="flex items-center gap-2">
          <p className="truncate text-sm font-semibold tracking-tight text-fg">{label}</p>
          <span
            data-testid="parcel-maturity-chip"
            data-maturity={parcel.maturityRating}
            className={cn(
              "inline-flex flex-shrink-0 items-center rounded-full px-2 py-0.5 text-[11px] font-medium",
              maturity.cls,
            )}
          >
            {maturity.label}
          </span>
        </div>
        <p className="flex items-center gap-1 text-xs text-fg-muted">
          <MapPin className="size-3.5" aria-hidden="true" />
          <span className="truncate">
            {parcel.regionName} ({parcel.gridX}, {parcel.gridY}) ·{" "}
            {parcel.areaSqm} m²
            {parcel.continentName ? ` · ${parcel.continentName}` : ""}
          </span>
        </p>
        <p className="truncate text-[11px] font-medium text-fg-muted">
          Owner UUID: {parcel.ownerUuid} ({parcel.ownerType})
        </p>
        <a
          className="inline-flex items-center gap-1 text-xs font-medium text-brand hover:underline"
          href={parcel.slurl}
          target="_blank"
          rel="noreferrer"
        >
          Visit in Second Life
          <ExternalLink className="size-3" aria-hidden="true" />
        </a>
      </div>
    </div>
  );
}
