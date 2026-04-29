"use client";
import Link from "next/link";
import { Heart } from "@/components/ui/icons";
import { EmptyState } from "@/components/ui/EmptyState";
import { Button } from "@/components/ui/Button";

export interface CuratorTrayEmptyProps {
  onBrowse?: () => void;
}

/**
 * Rendered when the caller is authenticated but has not saved any
 * auctions yet. Surfaces a browse CTA so the user has a clear next step.
 * When an {@code onBrowse} callback is provided (e.g. the drawer host
 * wants to close the tray before navigating), the CTA is a button;
 * otherwise it's a plain Link to /browse.
 */
export function CuratorTrayEmpty({ onBrowse }: CuratorTrayEmptyProps) {
  return (
    <EmptyState
      icon={Heart}
      headline="Save parcels to review them here."
      description="Tap the heart on any listing to stash it for later comparison."
    >
      {onBrowse ? (
        <Button variant="primary" onClick={onBrowse}>
          Browse listings
        </Button>
      ) : (
        <Link href="/browse">
          <Button variant="primary">Browse listings</Button>
        </Link>
      )}
    </EmptyState>
  );
}
