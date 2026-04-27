"use client";
import type { BanType } from "@/lib/admin/types";

const TONE_CLASSES: Record<BanType, string> = {
  IP: "bg-error-container text-on-error-container",
  AVATAR: "bg-secondary-container text-on-secondary-container",
  BOTH: "bg-tertiary-container text-on-tertiary-container",
};

type Props = { banType: BanType };

export function BanTypeBadge({ banType }: Props) {
  return (
    <span
      className={`${TONE_CLASSES[banType]} px-2 py-0.5 rounded-full text-[10px] font-medium`}
      data-testid={`ban-type-badge-${banType}`}
    >
      {banType}
    </span>
  );
}
