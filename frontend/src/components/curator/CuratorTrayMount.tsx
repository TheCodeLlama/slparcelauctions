"use client";
import { useEffect, useState } from "react";
import { createPortal } from "react-dom";
import { useAuth } from "@/lib/auth";
import { CuratorTrayTrigger } from "./CuratorTrayTrigger";
import { CuratorTray } from "./CuratorTray";

/**
 * Layout-level mount for the Curator Tray. Owns the open/closed state,
 * portals the {@link CuratorTrayTrigger} into the nav's
 * {@code #curator-tray-slot} placeholder, and renders the tray shell
 * itself at the root so focus management and the glass backdrop overlay
 * the whole page.
 *
 * <p>Renders {@code null} when the caller is unauthenticated — both the
 * trigger and the tray are logged-in-only surfaces.
 */
export function CuratorTrayMount() {
  const session = useAuth();
  const [open, setOpen] = useState(false);
  const [slot, setSlot] = useState<HTMLElement | null>(null);

  useEffect(() => {
    if (typeof document === "undefined") return;
    // The slot lives in Header.tsx; run the lookup after hydration so the
    // portal target exists.
    // eslint-disable-next-line react-hooks/set-state-in-effect -- portal target lives outside the React tree; must be read after mount.
    setSlot(document.getElementById("curator-tray-slot"));
  }, []);

  if (session.status !== "authenticated") return null;

  return (
    <>
      {slot &&
        createPortal(
          <CuratorTrayTrigger onOpen={() => setOpen(true)} />,
          slot,
        )}
      {open && <CuratorTray open={open} onClose={() => setOpen(false)} />}
    </>
  );
}
