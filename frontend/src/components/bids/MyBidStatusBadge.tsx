import { StatusBadge } from "@/components/ui/StatusBadge";
import type { MyBidStatus } from "@/types/auction";

export interface MyBidStatusBadgeProps {
  status: MyBidStatus;
  className?: string;
}

type Tone = "default" | "success" | "warning" | "danger";

/**
 * Tone + label table for the seven derived bid statuses. The tones
 * intentionally collide with the four primitive buckets in
 * {@link StatusBadge} — the 4px left-border on {@code MyBidSummaryRow}
 * carries the finer distinctions (WINNING vs WON vs RESERVE_NOT_MET etc.).
 *
 * <ul>
 *   <li>{@code WINNING} / {@code WON} — success (green / gold via
 *       tertiary-container).</li>
 *   <li>{@code OUTBID} / {@code SUSPENDED} — danger (red).</li>
 *   <li>{@code RESERVE_NOT_MET} — warning (orange).</li>
 *   <li>{@code LOST} / {@code CANCELLED} — default (neutral).</li>
 * </ul>
 */
const STATUS_CONFIG: Record<MyBidStatus, { tone: Tone; label: string }> = {
  WINNING: { tone: "success", label: "Winning" },
  OUTBID: { tone: "danger", label: "Outbid" },
  WON: { tone: "success", label: "Won" },
  LOST: { tone: "default", label: "Lost" },
  RESERVE_NOT_MET: { tone: "warning", label: "Reserve not met" },
  CANCELLED: { tone: "default", label: "Cancelled" },
  SUSPENDED: { tone: "danger", label: "Suspended" },
};

/**
 * Thin wrapper around {@link StatusBadge} that maps a {@link MyBidStatus}
 * to the project's primitive tone buckets + a spec §12 label. The colored
 * 4px left border on {@code MyBidSummaryRow} carries the full seven-way
 * distinction.
 */
export function MyBidStatusBadge({ status, className }: MyBidStatusBadgeProps) {
  const config = STATUS_CONFIG[status];
  return (
    <StatusBadge tone={config.tone} className={className}>
      {config.label}
    </StatusBadge>
  );
}
