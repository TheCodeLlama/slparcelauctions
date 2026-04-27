"use client";
import { REASON_FAMILY, REASON_LABEL, FAMILY_TONE_CLASSES } from "@/lib/admin/reasonStyle";
import type { FraudFlagReason } from "@/lib/admin/types";

type Props = { reason: FraudFlagReason };

export function ReasonBadge({ reason }: Props) {
  const tone = FAMILY_TONE_CLASSES[REASON_FAMILY[reason]];
  return (
    <span className={`${tone} px-2 py-0.5 rounded-full text-[10px] font-medium`}>
      {REASON_LABEL[reason]}
    </span>
  );
}
