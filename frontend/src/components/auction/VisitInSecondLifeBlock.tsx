import { ExternalLink, Globe } from "@/components/ui/icons";
import { mapUrl, viewerProtocolUrl } from "@/lib/sl/slurl";
import { cn } from "@/lib/cn";

/**
 * Two-button "visit this parcel" block on the auction detail page.
 * Replaces the old {@link VisitInSecondLifeButton} dropdown for the
 * detail surface — the list page still uses a compact dropdown, the
 * detail page prefers a visually prominent block with an explanatory
 * sentence.
 *
 * Button encoding differs by target:
 *   - "Open in Viewer"  — {@code secondlife:///app/teleport/…}. Region
 *     kept raw so the SL viewer's URL parser sees the literal string it
 *     expects (see {@link viewerProtocolUrl}).
 *   - "View on Map"     — {@code https://maps.secondlife.com/…}. Region
 *     is {@code encodeURIComponent}-ed for the HTTPS parser (see
 *     {@link mapUrl}).
 *
 * Null / zero positions fall back to the region-centre convention
 * 128/128/0.
 */
interface Props {
  regionName: string;
  positionX: number | null;
  positionY: number | null;
  positionZ: number | null;
  className?: string;
}

export function VisitInSecondLifeBlock({
  regionName,
  positionX,
  positionY,
  positionZ,
  className,
}: Props) {
  const viewerHref = viewerProtocolUrl(
    regionName,
    positionX,
    positionY,
    positionZ,
  );
  const mapHref = mapUrl(regionName, positionX, positionY, positionZ);

  return (
    <section
      aria-label="Visit in Second Life"
      className={cn(
        "rounded-default bg-surface-container-lowest p-6 flex flex-col gap-4",
        className,
      )}
      data-testid="visit-in-sl-block"
    >
      <div className="flex flex-col gap-1">
        <h2 className="text-title-lg font-bold text-on-surface">
          Visit in Second Life
        </h2>
        <p className="text-body-md text-on-surface-variant">
          Preview the parcel in-world before you bid. Open in the Second Life
          viewer if it&apos;s installed, or view the location on the web map
          without leaving your browser.
        </p>
      </div>
      <div className="flex flex-wrap gap-3">
        <a
          href={viewerHref}
          className="inline-flex items-center gap-2 rounded-default bg-primary text-on-primary px-4 py-2 text-label-lg font-medium transition-colors hover:bg-primary/90 focus:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2"
          data-testid="visit-in-sl-viewer"
        >
          <Globe className="size-4" aria-hidden="true" />
          Open in Viewer
        </a>
        <a
          href={mapHref}
          target="_blank"
          rel="noopener noreferrer"
          className="inline-flex items-center gap-2 rounded-default bg-surface-container-high text-on-surface px-4 py-2 text-label-lg font-medium transition-colors hover:bg-surface-container-highest focus:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2"
          data-testid="visit-in-sl-map"
        >
          <ExternalLink className="size-4" aria-hidden="true" />
          View on Map
        </a>
      </div>
    </section>
  );
}
