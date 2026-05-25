import { cn } from "@/lib/cn";

interface Props {
  className?: string;
}

/**
 * Loading placeholder shown while {@code next/dynamic} downloads the
 * three.js bundle and while {@code useParcelScan} fetches the raster. Mirrors
 * the 2D map's loading shape (320x320, animate-pulse, bg-bg-subtle) so the
 * panel doesn't visually jump when the visitor switches tabs.
 */
export function ParcelMap3DSkeleton({ className }: Props) {
  return (
    <div
      aria-hidden="true"
      data-testid="parcel-map-3d-skeleton"
      className={cn(
        "aspect-square w-full max-w-[320px] animate-pulse bg-bg-subtle",
        className,
      )}
    />
  );
}
