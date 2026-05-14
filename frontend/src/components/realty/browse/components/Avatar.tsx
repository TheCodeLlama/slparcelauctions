"use client";

import { initialsOf } from "../lib/format";

interface AvatarProps {
  name: string;
  size?: "xs" | "sm" | "md" | "lg" | "xl";
}

const SIZE_CLASS: Record<NonNullable<AvatarProps["size"]>, string> = {
  xs: "w-5 h-5 text-[8px]",
  sm: "w-7 h-7 text-[10px]",
  md: "w-9 h-9 text-xs",
  lg: "w-11 h-11 text-sm",
  xl: "w-16 h-16 text-base",
};

export function Avatar({ name, size = "md" }: AvatarProps) {
  return (
    <div
      className={`${SIZE_CLASS[size]} shrink-0 rounded-full bg-bg-muted text-fg-muted grid place-items-center font-semibold tracking-tight`}
    >
      {initialsOf(name) || "U"}
    </div>
  );
}
