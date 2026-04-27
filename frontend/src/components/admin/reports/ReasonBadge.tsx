"use client";
import {
  REPORT_REASON_FAMILY,
  REPORT_REASON_LABEL,
  REPORT_FAMILY_TONE_CLASSES,
} from "@/lib/admin/reportReasonStyle";
import type { ListingReportReason } from "@/lib/admin/types";

type Props = { reason: ListingReportReason };

export function ReasonBadge({ reason }: Props) {
  const tone = REPORT_FAMILY_TONE_CLASSES[REPORT_REASON_FAMILY[reason]];
  return (
    <span className={`${tone} px-2 py-0.5 rounded-full text-[10px] font-medium`}>
      {REPORT_REASON_LABEL[reason]}
    </span>
  );
}
