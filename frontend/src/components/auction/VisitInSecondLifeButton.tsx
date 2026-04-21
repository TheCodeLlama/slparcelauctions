"use client";

import {
  ChevronDown,
  ExternalLink,
  Globe,
} from "@/components/ui/icons";
import { Button } from "@/components/ui/Button";
import { Dropdown } from "@/components/ui/Dropdown";

/**
 * Dropdown launcher offering two ways to visit the parcel in Second Life:
 *
 *   - "Open in Viewer" — {@code secondlife:///app/teleport/{region}/{x}/{y}/{z}}
 *     handed off to the OS protocol handler (launches the SL viewer if
 *     installed). Region name is URL-encoded so multi-word regions
 *     ("Bay City") survive the handoff.
 *   - "View on Map"    — {@code https://maps.secondlife.com/secondlife/{region}/{x}/{y}/{z}}
 *     opens the official web map in a new tab. Always works (no viewer
 *     required).
 *
 * Both options reuse the shared {@link Dropdown} primitive so keyboard /
 * focus semantics match the rest of the app.
 */
interface Props {
  regionName: string;
  positionX: number;
  positionY: number;
  positionZ: number;
}

export function VisitInSecondLifeButton({
  regionName,
  positionX,
  positionY,
  positionZ,
}: Props) {
  const encodedRegion = encodeURIComponent(regionName);
  const viewerHref = `secondlife:///app/teleport/${encodedRegion}/${positionX}/${positionY}/${positionZ}`;
  const mapHref = `https://maps.secondlife.com/secondlife/${encodedRegion}/${positionX}/${positionY}/${positionZ}`;

  return (
    <Dropdown
      trigger={
        <Button
          variant="secondary"
          size="sm"
          rightIcon={<ChevronDown className="size-4" aria-hidden="true" />}
        >
          Visit in Second Life
        </Button>
      }
      items={[
        {
          label: "Open in Viewer",
          icon: <Globe />,
          onSelect: () => {
            // Protocol-handler handoff — window.location works where anchor
            // clicks sometimes don't (e.g. behind a menu button that already
            // consumed the click event).
            window.location.href = viewerHref;
          },
        },
        {
          label: "View on Map",
          icon: <ExternalLink />,
          onSelect: () => {
            window.open(mapHref, "_blank", "noopener,noreferrer");
          },
        },
      ]}
    />
  );
}

// Export the URL builders so tests can assert them without dragging the
// dropdown-interaction machinery into each assertion.
export function buildViewerHref(
  regionName: string,
  x: number,
  y: number,
  z: number,
): string {
  return `secondlife:///app/teleport/${encodeURIComponent(regionName)}/${x}/${y}/${z}`;
}

export function buildMapHref(
  regionName: string,
  x: number,
  y: number,
  z: number,
): string {
  return `https://maps.secondlife.com/secondlife/${encodeURIComponent(regionName)}/${x}/${y}/${z}`;
}
