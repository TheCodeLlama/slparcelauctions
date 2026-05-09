"use client";

import { useState } from "react";
import { IconButton } from "@/components/ui";
import { Search } from "@/components/ui/icons";
import { SearchOverlay } from "./SearchOverlay";

/**
 * Header-mounted button + overlay pair. Owns the open/close state so
 * the overlay can stay outside the surrounding flex layout (it portals
 * either to body via Dialog on mobile, or absolutely positions on
 * desktop).
 */
export function SearchOverlayTrigger() {
  const [open, setOpen] = useState(false);
  return (
    <>
      <IconButton
        aria-label="Search"
        variant="tertiary"
        onClick={() => setOpen(true)}
      >
        <Search className="h-[18px] w-[18px]" />
      </IconButton>
      <SearchOverlay open={open} onClose={() => setOpen(false)} />
    </>
  );
}
