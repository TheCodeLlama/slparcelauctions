"use client";
import type { BanType } from "@/lib/admin/types";

const TONE_CLASSES: Record<BanType, string> = {
  IP: "bg-danger-bg text-danger-flat",
  AVATAR: "bg-info-bg text-info-flat",
  BOTH: "bg-info-bg text-info-flat",
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
