"use client";

import { useCallback, useEffect, useState } from "react";

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
 * SSR-safe via a two-phase mount: the initial render returns DEFAULT_VIEW on
 * BOTH the server pass and the client hydration pass, so the markup matches
 * and React does not warn about a hydration mismatch. After hydration the
 * useEffect runs, reads the stored value, and re-renders if it differs. A
 * naive useState lazy initializer would read localStorage during the client
 * hydration pass and produce different HTML than the server, causing a
 * mismatch warning whenever a returning visitor has "3d" stored.
 */
export function useParcelMapView(): [
  ParcelMapView,
  (next: ParcelMapView) => void,
] {
  const [view, setView] = useState<ParcelMapView>(DEFAULT_VIEW);

  // Deliberate two-phase mount: see JSDoc above. Lint rules that flag
  // setState-in-effect do not model SSR hydration, so the suppression is
  // load-bearing here.
  /* eslint-disable react-hooks/set-state-in-effect */
  useEffect(() => {
    setView(readStoredView());
  }, []);
  /* eslint-enable react-hooks/set-state-in-effect */

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
