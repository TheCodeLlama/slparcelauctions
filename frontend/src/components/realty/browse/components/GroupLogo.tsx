"use client";

import { ThemedImage } from "@/components/ui/ThemedImage";
import { useThemedImage } from "@/lib/theme/useThemedImage";
import { initialsOf } from "../lib/format";

interface GroupLogoProps {
  name: string;
  /** Light logo variant (relative API path). */
  logoLightUrl?: string | null;
  /** Dark logo variant (relative API path). */
  logoDarkUrl?: string | null;
  size?: "sm" | "md" | "lg" | "xl";
  square?: boolean;
}

const SIZE: Record<NonNullable<GroupLogoProps["size"]>, string> = {
  sm: "w-11 h-11 text-base",
  md: "w-14 h-14 text-lg",
  lg: "w-[72px] h-[72px] text-2xl",
  xl: "w-[108px] h-[108px] text-4xl",
};

export function GroupLogo({ name, logoLightUrl, logoDarkUrl, size = "md", square }: GroupLogoProps) {
  // Mirror ThemedImage's resolution so the initials fallback fires when
  // neither variant is set, regardless of the active theme.
  const chosen = useThemedImage(logoLightUrl, logoDarkUrl);
  if (chosen) {
    return (
      <ThemedImage
        lightSrc={logoLightUrl}
        darkSrc={logoDarkUrl}
        alt={`${name} logo`}
        className={`${SIZE[size]} shrink-0 object-contain bg-surface-raised border border-border ${square ? "" : "rounded-lg"}`}
      />
    );
  }
  return (
    <div
      className={`${SIZE[size]} shrink-0 ${square ? "" : "rounded-lg"} bg-gradient-to-br from-brand to-amber-400 text-on-brand grid place-items-center font-bold tracking-tight`}
    >
      {initialsOf(name) || "G"}
    </div>
  );
}
