"use client";

import dynamic from "next/dynamic";
import { useCallback, useState, type KeyboardEvent } from "react";

import { cn } from "@/lib/cn";
import { useParcelMapView, type ParcelMapView } from "@/hooks/useParcelMapView";
import { ParcelMap } from "./ParcelMap";
import { ParcelMap3DSkeleton } from "./ParcelMap3DSkeleton";

const ParcelMap3D = dynamic(() => import("./ParcelMap3D"), {
  ssr: false,
  loading: () => <ParcelMap3DSkeleton />,
});

interface Props {
  publicId: string;
  className?: string;
}

const PANEL_ID = "parcel-map-panel";
const TAB_2D_ID = "parcel-map-tab-2d";
const TAB_3D_ID = "parcel-map-tab-3d";

/**
 * Tab wrapper that swaps between the 2D and 3D parcel-map views. Tab choice
 * persists in localStorage via {@link useParcelMapView} so visitors who
 * prefer 3D see it on every auction they open. Three.js + R3F load lazily on
 * first 3D tab activation via {@code next/dynamic({ ssr: false })}.
 *
 * Spec: docs/superpowers/specs/2026-05-24-parcel-map-3d-design.md
 */
export function ParcelMapTabs({ publicId, className }: Props) {
  const [storedView, setStoredView] = useParcelMapView();
  // webglFallback forces the display to 2D without overwriting the stored
  // preference -- the visitor still prefers 3D, their browser just can't do it.
  const [webglUnavailable, setWebglUnavailable] = useState(false);

  // Derived: show 2D whenever WebGL is unavailable, otherwise follow stored pref.
  const view: ParcelMapView = webglUnavailable ? "2d" : storedView;
  const setView = setStoredView;

  const handleWebglUnavailable = useCallback(() => {
    // Only flip the session-level flag; do NOT call setStoredView so
    // localStorage keeps the visitor's "3d" preference intact.
    setWebglUnavailable(true);
  }, []);

  const handleTabKeyDown = (e: KeyboardEvent<HTMLButtonElement>) => {
    if (e.key !== "ArrowLeft" && e.key !== "ArrowRight") return;
    e.preventDefault();
    const tablist = e.currentTarget.parentElement;
    if (!tablist) return;
    const buttons = Array.from(
      tablist.querySelectorAll<HTMLButtonElement>('[role="tab"]'),
    );
    const idx = buttons.findIndex((b) => b === e.currentTarget);
    if (idx === -1) return;
    const delta = e.key === "ArrowRight" ? 1 : -1;
    const next = (idx + delta + buttons.length) % buttons.length;
    buttons[next].focus();
  };

  return (
    <div className={cn("flex flex-col gap-3", className)}>
      <div
        role="tablist"
        aria-label="Parcel map view"
        className="flex gap-1 border-b border-border-subtle"
      >
        <button
          id={TAB_2D_ID}
          type="button"
          role="tab"
          aria-selected={view === "2d"}
          aria-controls={PANEL_ID}
          tabIndex={view === "2d" ? 0 : -1}
          onClick={() => setView("2d")}
          onKeyDown={handleTabKeyDown}
          className={tabClassName(view === "2d")}
        >
          2D Map
        </button>
        <button
          id={TAB_3D_ID}
          type="button"
          role="tab"
          aria-selected={view === "3d"}
          aria-controls={PANEL_ID}
          tabIndex={view === "3d" ? 0 : -1}
          onClick={() => setView("3d")}
          onKeyDown={handleTabKeyDown}
          className={tabClassName(view === "3d")}
        >
          3D View
        </button>
      </div>
      {webglUnavailable && (
        <p className="text-xs text-fg-muted">
          3D view requires WebGL, which your browser does not support. Showing
          2D view instead.
        </p>
      )}
      <div
        id={PANEL_ID}
        role="tabpanel"
        aria-labelledby={view === "2d" ? TAB_2D_ID : TAB_3D_ID}
        tabIndex={0}
      >
        {view === "2d" ? (
          <ParcelMap publicId={publicId} />
        ) : (
          <ParcelMap3D
            publicId={publicId}
            onWebGLUnavailable={handleWebglUnavailable}
          />
        )}
      </div>
    </div>
  );
}

function tabClassName(active: boolean): string {
  return cn(
    "px-4 py-2 text-sm font-medium transition-colors",
    active
      ? "text-brand border-b-2 border-brand"
      : "text-fg-muted hover:text-fg",
  );
}
