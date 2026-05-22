"use client";
import type { ImgHTMLAttributes } from "react";
import { apiUrl } from "@/lib/api/url";
import { useThemedImage } from "@/lib/theme/useThemedImage";

type ThemedImageProps = {
  lightSrc: string | null | undefined;
  darkSrc: string | null | undefined;
  alt: string;
} & Omit<ImgHTMLAttributes<HTMLImageElement>, "src">;

/**
 * Theme-aware image renderer. Picks the right variant per
 * `useThemedImage`, wraps the chosen URL with `apiUrl()`, and renders
 * `<img>`. Returns null when both variants are null.
 */
export function ThemedImage({ lightSrc, darkSrc, alt, ...imgProps }: ThemedImageProps) {
  const chosen = useThemedImage(lightSrc, darkSrc);
  if (!chosen) return null;
  return <img src={apiUrl(chosen) ?? undefined} alt={alt} {...imgProps} />;
}
