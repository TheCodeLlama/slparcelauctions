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
  if (isDesktop) {
    return (
      <Drawer open={open} onClose={onClose} title="Your Curator Tray">
        <CuratorTrayContent />
      </Drawer>
    );
  }
  return (
    <BottomSheet open={open} onClose={onClose} title="Your Curator Tray">
      <CuratorTrayContent />
    </BottomSheet>
  );
}
