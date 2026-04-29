"use client";
import { Drawer } from "@/components/ui/Drawer";
import { BottomSheet } from "@/components/ui/BottomSheet";
import { useMediaQuery } from "@/hooks/useMediaQuery";
import { CuratorTrayContent } from "./CuratorTrayContent";

export interface CuratorTrayProps {
  open: boolean;
  onClose: () => void;
}

const DESKTOP_QUERY = "(min-width: 768px)";

/**
 * Responsive Curator Tray shell. Desktop (>= md) renders a right-anchored
 * {@link Drawer}; mobile renders a {@link BottomSheet}. Both host the same
 * {@link CuratorTrayContent} child — the tray is ephemeral (no URL sync,
 * state resets on close).
 */
export function CuratorTray({ open, onClose }: CuratorTrayProps) {
  const isDesktop = useMediaQuery(DESKTOP_QUERY);
  // Dismiss the tray before navigating to /browse so the user lands on the
  // browse page with a clean viewport — the drawer/sheet lingering behind
  // the new page would leak stale context.
  const handleBrowse = () => onClose();
  if (isDesktop) {
    return (
      <Drawer open={open} onClose={onClose} title="Your Curator Tray">
        <CuratorTrayContent onBrowse={handleBrowse} />
      </Drawer>
    );
  }
  return (
    <BottomSheet open={open} onClose={onClose} title="Your Curator Tray">
      <CuratorTrayContent onBrowse={handleBrowse} />
    </BottomSheet>
  );
}
