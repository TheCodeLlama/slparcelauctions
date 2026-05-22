"use client";

import { ThemedImage } from "@/components/ui/ThemedImage";
import { useThemedImage } from "@/lib/theme/useThemedImage";

interface GroupCoverProps {
  /** Light cover variant (relative API path). */
  coverLightUrl?: string | null;
  /** Dark cover variant (relative API path). */
  coverDarkUrl?: string | null;
  size?: "card" | "hero";
}

export function GroupCover({ coverLightUrl, coverDarkUrl, size = "card" }: GroupCoverProps) {
  const heightClass = size === "hero" ? "h-[200px]" : "h-[92px]";
  // Mirror ThemedImage's resolution so the gradient fallback fires when
  // neither variant is set, regardless of the active theme.
  const chosen = useThemedImage(coverLightUrl, coverDarkUrl);

  if (chosen) {
    return (
      <div className={`relative w-full ${heightClass} overflow-hidden bg-bg-hover`}>
        <ThemedImage
          lightSrc={coverLightUrl}
          darkSrc={coverDarkUrl}
          alt=""
          className="w-full h-full object-contain"
        />
      </div>
    );
  }
  return (
    <div
      className={`relative w-full ${heightClass} overflow-hidden bg-gradient-to-br from-brand via-amber-400 to-orange-200`}
    >
      <svg
        viewBox="0 0 400 100"
        preserveAspectRatio="none"
        className="absolute inset-0 w-full h-full opacity-25 mix-blend-screen"
      >
        <path
          d="M 0 70 Q 60 50 120 60 T 240 55 T 400 50 L 400 100 L 0 100 Z"
          fill="white"
        />
        <path
          d="M 0 85 Q 80 70 160 80 T 320 75 T 400 78 L 400 100 L 0 100 Z"
          fill="rgba(255,255,255,.7)"
        />
      </svg>
    </div>
  );
}
