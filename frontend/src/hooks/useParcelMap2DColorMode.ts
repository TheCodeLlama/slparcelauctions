"use client";

import { useCallback, useEffect, useState } from "react";

export type ParcelMap2DColorMode = "elevation" | "landuse";

const STORAGE_KEY = "slpa:parcel-map:2d-color";
const DEFAULT_MODE: ParcelMap2DColorMode = "elevation";

function readStoredMode(): ParcelMap2DColorMode {
  if (typeof window === "undefined") return DEFAULT_MODE;
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (raw === "elevation" || raw === "landuse") return raw;
    return DEFAULT_MODE;
  } catch {
    // localStorage can throw in privacy modes or quota-exceeded scenarios.
    return DEFAULT_MODE;
  }
}

/**
 * localStorage-backed color-mode choice for the parcel-map 2D view. Returns
 * the current mode and a setter that mirrors writes to localStorage.
 *
 * SSR-safe via a two-phase mount: the initial render returns DEFAULT_MODE on
 * BOTH the server pass and the client hydration pass, so the markup matches
 * and React does not warn about a hydration mismatch. After hydration the
 * useEffect runs, reads the stored value, and re-renders if it differs.
 * Mirrors {@link useParcelMapColorMode} (which is the 3D-mode peer).
 */
export function useParcelMap2DColorMode(): [
  ParcelMap2DColorMode,
  (next: ParcelMap2DColorMode) => void,
] {
  const [mode, setMode] = useState<ParcelMap2DColorMode>(DEFAULT_MODE);

  // Deliberate two-phase mount: see JSDoc above. Lint rules that flag
  // setState-in-effect do not model SSR hydration, so the suppression is
  // load-bearing here.
  /* eslint-disable react-hooks/set-state-in-effect */
  useEffect(() => {
    setMode(readStoredMode());
  }, []);
  /* eslint-enable react-hooks/set-state-in-effect */

  const update = useCallback((next: ParcelMap2DColorMode) => {
    setMode(next);
    if (typeof window === "undefined") return;
    try {
      window.localStorage.setItem(STORAGE_KEY, next);
    } catch {
      // localStorage write can throw under quota or privacy modes; swallow
      // so the in-session mode change still takes effect.
    }
  }, []);

  return [mode, update];
}
