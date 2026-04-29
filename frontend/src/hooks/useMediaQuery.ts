"use client";
import { useSyncExternalStore } from "react";

/**
 * Reactive `window.matchMedia` wrapper for responsive branching at the
 * component level (e.g. desktop sidebar vs. mobile BottomSheet in the
 * browse surface). Implemented via {@link useSyncExternalStore} so the
 * initial value is read synchronously on the client and the subscription
 * is guaranteed to tear down on unmount. Returns {@code false} during
 * server render (the {@code getServerSnapshot} return value) — the
 * components that consume this hook always have a safe
 * desktop-or-hidden fallback, so the transient SSR value is fine.
 */
export function useMediaQuery(query: string): boolean {
  return useSyncExternalStore(
    (onChange) => {
      if (typeof window === "undefined" || !window.matchMedia) {
        return () => {};
      }
      const mql = window.matchMedia(query);
      mql.addEventListener("change", onChange);
      return () => mql.removeEventListener("change", onChange);
    },
    () => {
      if (typeof window === "undefined" || !window.matchMedia) return false;
      return window.matchMedia(query).matches;
    },
    () => false,
  );
}
