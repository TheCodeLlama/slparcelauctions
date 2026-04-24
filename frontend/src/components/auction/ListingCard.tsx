"use client";
import Link from "next/link";
import { useToast } from "@/components/ui/Toast/useToast";
import { StatusChip } from "@/components/ui/StatusChip";
import { CountdownTimer } from "@/components/ui/CountdownTimer";
import { ShieldCheck, MapPin, Heart } from "@/components/ui/icons";
import { cn } from "@/lib/cn";
import { deriveStatusChip } from "@/lib/search/status-chip";
import type { AuctionSearchResultDto } from "@/types/search";

export type ListingCardVariant = "default" | "compact" | "featured";

export interface ListingCardProps {
  listing: AuctionSearchResultDto;
  variant: ListingCardVariant;
  className?: string;
}

/**
 * Auction statuses where the heart button MUST stay hidden. These rows can
 * appear in the Curator Tray (via the {@code status_filter=all} path) but
 * are not yet publicly listed — saving them would grant visibility into
 * pre-active listings the seller hasn't surfaced yet.
 */
const PRE_ACTIVE = new Set([
  "DRAFT",
  "DRAFT_PAID",
  "VERIFICATION_PENDING",
  "VERIFICATION_FAILED",
]);

/**
 * Tag pill overflow counts per variant. Tuned so the card foot doesn't
 * wrap to a second row in the default/compact densities, but a featured
 * card has room to surface the full tag set.
 */
const MAX_TAGS: Record<ListingCardVariant, number> = {
  default: 3,
  compact: 2,
  featured: 5,
};

/**
 * Canonical card rendered by every browse / featured / Curator Tray
 * surface. Three density variants:
 *
 *   - {@code default}   — grid card used by /browse.
 *   - {@code compact}   — denser card for the Curator Tray list and the
 *                         search autocomplete dropdown.
 *   - {@code featured}  — wide card (16/9 image, up to 5 tags) used by the
 *                         landing page's three featured rows.
 *
 * The entire card is a single {@link Link} to {@code /auction/{id}}. The
 * heart overlay intercepts its own click (preventDefault + stopPropagation)
 * so navigating away doesn't happen when the user means to save.
 *
 * Heart behavior in Task 2a: always surfaces a {@code toast.warning} with
 * an in-place "Sign in" action button — the full save/unsave mutation
 * lands in Task 5 with useSavedAuctions. Pre-active statuses never show a
 * heart at all.
 */
export function ListingCard({ listing, variant, className }: ListingCardProps) {
  const chip = deriveStatusChip({
    status: listing.status,
    endOutcome: listing.endOutcome,
    endsAt: listing.endsAt,
  });
  const imageSrc =
    listing.primaryPhotoUrl ?? listing.parcel.snapshotUrl ?? undefined;
  const maxTags = MAX_TAGS[variant];
  const visibleTags = listing.parcel.tags.slice(0, maxTags);
  const overflow = listing.parcel.tags.length - visibleTags.length;
  const showHeart = !PRE_ACTIVE.has(listing.status);

  return (
    <article
      className={cn(
        "relative flex flex-col rounded-default bg-surface-container-lowest shadow-sm overflow-hidden",
        "transition hover:shadow-elevated hover:scale-[1.01]",
        variant === "featured" && "md:col-span-2",
        className,
      )}
      data-variant={variant}
    >
      <Link
        href={`/auction/${listing.id}`}
        className="flex flex-col gap-3 focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary rounded-default"
        aria-label={`${listing.title} in ${listing.parcel.region}, current bid L$${listing.currentBid.toLocaleString()}`}
      >
        <div
          className={cn(
            "relative overflow-hidden",
            variant === "featured" ? "aspect-[16/9]" : "aspect-[4/3]",
          )}
        >
          {imageSrc && (
            // eslint-disable-next-line @next/next/no-img-element -- parcel snapshot URLs are external SL CDN paths; next/image requires remote-pattern config we keep deferred until Task 5.
            <img
              src={imageSrc}
              alt=""
              className="h-full w-full object-cover"
              loading="lazy"
            />
          )}
          <StatusChip
            label={chip.label}
            tone={chip.tone}
            className="absolute top-2 left-2"
          />
          <span className="absolute bottom-2 right-2 rounded-full bg-on-surface/70 px-2 py-0.5 text-label-sm text-on-primary">
            {listing.parcel.area}m²
          </span>
        </div>
        <div className="flex flex-col gap-1 px-4 pb-4">
          <h3
            className={cn(
              "font-display font-bold tracking-[-0.02em]",
              variant === "compact"
                ? "text-title-md line-clamp-1"
                : "text-title-lg line-clamp-2",
            )}
          >
            {listing.title}
          </h3>
          <p className="text-body-sm text-on-surface-variant flex items-center gap-1">
            <MapPin className="size-3.5" aria-hidden="true" />
            <span>
              {listing.parcel.name} · {listing.parcel.region}
            </span>
          </p>
          <div className="flex items-baseline justify-between gap-2">
            <span className="text-display-sm font-bold">
              L$ {listing.currentBid.toLocaleString()}
            </span>
            {listing.snipeProtect && (
              <span className="inline-flex items-center gap-1 text-body-sm text-on-surface-variant">
                <ShieldCheck className="size-4" aria-hidden="true" />
                {listing.snipeWindowMin ?? 5}min
              </span>
            )}
          </div>
          {variant !== "compact" && (
            <p className="text-body-sm text-on-surface-variant">
              {listing.bidCount} bid{listing.bidCount === 1 ? "" : "s"}
              {" · "}
              {listing.reserveMet ? "Reserve met" : "Reserve not met"}
            </p>
          )}
          <div className="text-body-sm text-on-surface-variant">
            <CountdownTimer
              expiresAt={new Date(listing.endsAt)}
              format="hh:mm:ss"
            />
          </div>
          {visibleTags.length > 0 && (
            <div className="flex flex-wrap gap-1">
              {visibleTags.map((t) => (
                <span
                  key={t}
                  className="rounded-full bg-surface-container-low px-2 py-0.5 text-label-sm"
                >
                  {t}
                </span>
              ))}
              {overflow > 0 && (
                <span className="rounded-full bg-surface-container-low px-2 py-0.5 text-label-sm text-on-surface-variant">
                  +{overflow}
                </span>
              )}
            </div>
          )}
          {listing.distanceRegions !== null && (
            <span className="text-label-sm text-on-surface-variant">
              {listing.distanceRegions.toFixed(1)} regions
            </span>
          )}
        </div>
      </Link>
      {showHeart && <HeartOverlay title={listing.title} />}
    </article>
  );
}

/**
 * Placeholder heart overlay. Full hook-backed implementation (optimistic
 * toggle + delete undo + rate-limit handling) lands in Task 5 when
 * useSavedAuctions ships. For Task 2a the button always behaves as if the
 * caller is anonymous: surfaces a warning toast with a "Sign in" action
 * button that navigates back to the current page post-login.
 */
function HeartOverlay({ title }: { title: string }) {
  const toast = useToast();
  return (
    <button
      type="button"
      aria-label={`Save ${title}`}
      onClick={(e) => {
        e.preventDefault();
        e.stopPropagation();
        toast.warning({
          title: "Sign in to save parcels",
          action: {
            label: "Sign in",
            onClick: () => {
              if (typeof window !== "undefined") {
                window.location.assign(
                  `/login?next=${encodeURIComponent(window.location.pathname)}`,
                );
              }
            },
          },
        });
      }}
      className="absolute top-2 right-2 rounded-full bg-surface-container-lowest/80 backdrop-blur p-2 hover:bg-surface-container-lowest focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary"
    >
      <Heart className="size-5 text-on-surface-variant" aria-hidden="true" />
    </button>
  );
}
