import { MapPin, ExternalLink } from "@/components/ui/icons";
import { cn } from "@/lib/cn";
import type { ParcelDto } from "@/types/parcel";

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
export function ParcelLookupCard({
  parcel,
  className,
}: {
  parcel: ParcelDto;
  className?: string;
}) {
  const label = parcel.description?.trim() || "(unnamed parcel)";
  return (
    <div
      data-testid="parcel-lookup-card"
      className={cn(
        "flex gap-4 rounded-default border border-outline-variant bg-surface-container-lowest p-4",
        className,
      )}
    >
      {parcel.snapshotUrl ? (
        /* eslint-disable-next-line @next/next/no-img-element */
        <img
          src={parcel.snapshotUrl}
          alt=""
          className="h-20 w-20 flex-shrink-0 rounded-default object-cover"
        />
      ) : (
        <div
          aria-hidden="true"
          className="h-20 w-20 flex-shrink-0 rounded-default bg-surface-container-high"
        />
      )}
      <div className="flex min-w-0 flex-1 flex-col gap-1">
        <p className="truncate text-title-md text-on-surface">{label}</p>
        <p className="flex items-center gap-1 text-body-sm text-on-surface-variant">
          <MapPin className="size-3.5" aria-hidden="true" />
          <span className="truncate">
            {parcel.regionName} · {parcel.areaSqm} m²
            {parcel.continentName ? ` · ${parcel.continentName}` : ""}
          </span>
        </p>
        <p className="truncate text-label-sm text-on-surface-variant">
          Owner UUID: {parcel.ownerUuid} ({parcel.ownerType})
        </p>
        <a
          className="inline-flex items-center gap-1 text-label-md text-primary hover:underline"
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
