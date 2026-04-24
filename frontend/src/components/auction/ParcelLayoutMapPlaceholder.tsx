import { MapPin } from "@/components/ui/icons";
import { cn } from "@/lib/cn";

/**
 * Reserves the card slot where a minimap / region layout visualisation
 * will eventually live. Hidden on mobile ({@code hidden md:block}) because
 * the placeholder is explicitly non-functional — there's no sense occupying
 * precious vertical space on narrow viewports for a "coming soon" notice.
 *
 * Phase 2 will swap this for a real interactive map (SL grid tile render
 * or equivalent). The placeholder is kept deliberately plain so the
 * eventual replacement doesn't need to match an aesthetic it would
 * immediately break.
 */
interface Props {
  className?: string;
}

export function ParcelLayoutMapPlaceholder({ className }: Props) {
  return (
    <section
      aria-label="Parcel map"
      data-testid="parcel-layout-map-placeholder"
      className={cn(
        "hidden md:block rounded-default bg-surface-container-lowest p-6",
        className,
      )}
    >
      <div className="flex flex-col items-center gap-2 text-on-surface-variant text-center">
        <MapPin className="size-8" aria-hidden="true" />
        <p className="text-body-md">Parcel map coming soon</p>
      </div>
    </section>
  );
}
