"use client";
import { useTheme } from "next-themes";

/**
 * Resolves a paired light/dark image URL to the variant appropriate for the
 * user's current theme. Returns the fallback variant if the primary is null
 * (so a single uploaded image works as both light and dark).
 *
 * `next-themes` `resolvedTheme` is "light" | "dark" even when the user's
 * preference is "system" - the library resolves it for us. Before mount,
 * `resolvedTheme` is undefined, in which case this hook returns the
 * SSR-safe light fallback.
 */
export function useThemedImage(
  lightUrl: string | null | undefined,
  darkUrl: string | null | undefined,
): string | null {
  const { resolvedTheme } = useTheme();
  const primary = resolvedTheme === "dark" ? darkUrl : lightUrl;
  const fallback = resolvedTheme === "dark" ? lightUrl : darkUrl;
  return primary ?? fallback ?? null;
}
