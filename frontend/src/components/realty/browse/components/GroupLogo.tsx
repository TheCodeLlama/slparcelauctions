"use client";

import { initialsOf } from "../lib/format";

interface GroupLogoProps {
  name: string;
  logoUrl?: string | null;
  size?: "sm" | "md" | "lg" | "xl";
  square?: boolean;
}

const SIZE: Record<NonNullable<GroupLogoProps["size"]>, string> = {
  sm: "w-11 h-11 text-base",
  md: "w-14 h-14 text-lg",
  lg: "w-[72px] h-[72px] text-2xl",
  xl: "w-[108px] h-[108px] text-4xl",
};

export function GroupLogo({ name, logoUrl, size = "md", square }: GroupLogoProps) {
  if (logoUrl) {
    return (
      <img
        src={logoUrl}
        alt={`${name} logo`}
        className={`${SIZE[size]} shrink-0 object-cover ${square ? "" : "rounded-lg"}`}
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
