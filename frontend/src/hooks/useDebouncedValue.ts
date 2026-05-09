"use client";

import { useEffect, useState } from "react";

/**
 * Returns {@code value} after it has stayed unchanged for {@code
 * delayMs}. Used by the search overlay so React Query keys stabilize
 * per "settled" input rather than firing per keystroke.
 */
export function useDebouncedValue<T>(value: T, delayMs: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const id = setTimeout(() => setDebounced(value), delayMs);
    return () => clearTimeout(id);
  }, [value, delayMs]);
  return debounced;
}
