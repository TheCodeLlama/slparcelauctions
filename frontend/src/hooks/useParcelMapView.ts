"use client";

import { useCallback, useState } from "react";

export type ParcelMapView = "2d" | "3d";

const STORAGE_KEY = "slpa:parcel-map:view";
const DEFAULT_VIEW: ParcelMapView = "2d";

function readStoredView(): ParcelMapView {
  if (typeof window === "undefined") return DEFAULT_VIEW;
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (raw === "2d" || raw === "3d") return raw;
    return DEFAULT_VIEW;
  } catch {
    // localStorage can throw in privacy modes or quota-exceeded scenarios.
    return DEFAULT_VIEW;
  }
}

/**
 * localStorage-backed tab choice for the parcel-map view switcher. Returns
 * the current view and a setter that mirrors writes to localStorage.
 *
 * SSR-safe: the lazy initializer returns DEFAULT_VIEW on the server pass
 * (typeof window === "undefined") and reads from localStorage on the client
 * pass, avoiding a hydration mismatch while still restoring the user's
 * preference without a layout-effect flash. Junk values in storage (anything
 * other than "2d" or "3d") fall back to the default without throwing.
 */
export function useParcelMapView(): [
  ParcelMapView,
  (next: ParcelMapView) => void,
] {
  const [view, setView] = useState<ParcelMapView>(readStoredView);

  const update = useCallback((next: ParcelMapView) => {
    setView(next);
    if (typeof window === "undefined") return;
    try {
      window.localStorage.setItem(STORAGE_KEY, next);
    } catch {
      // localStorage write can throw under quota or privacy modes; swallow
      // so the in-session tab change still takes effect.
    }
  }, []);

  return [view, update];
}
