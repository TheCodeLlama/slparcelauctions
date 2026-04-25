import { StatusBadge } from "@/components/ui/StatusBadge";
import type { CancellationOffenseKind } from "@/types/cancellation";

export interface CancellationConsequenceBadgeProps {
  /**
   * The snapshotted offense kind from the cancellation log row.
   * {@code null} indicates the row had no penalty payload (the log's
   * {@code penaltyKind} was {@code NONE}, e.g. a pre-active or
   * active-without-bids cancellation), which the UI renders as the same
   * "No penalty" badge as an explicit {@code NONE}.
   */
  kind: CancellationOffenseKind | null;
  /**
   * L$ amount snapshotted on the log row at the time of cancellation.
   * Only read for {@code PENALTY} / {@code PENALTY_AND_30D} where the
   * label includes the figure; ignored otherwise.
   */
  amountL: number | null;
}

/**
 * Maps a {@link CancellationOffenseKind} to a {@link StatusBadge} on the
 * existing tone palette. Sub-spec 2 §8.3 requires:
 *
 * <ul>
 *   <li>{@code null} / {@code NONE} → "No penalty" (default tone)</li>
 *   <li>{@code WARNING} → "Warning" (warning tone)</li>
 *   <li>{@code PENALTY} → "L${amount} penalty" (danger tone)</li>
 *   <li>{@code PENALTY_AND_30D} → "L${amount} + 30-day suspension"
 *       (danger tone)</li>
 *   <li>{@code PERMANENT_BAN} → "Permanent ban" (danger tone)</li>
 * </ul>
 *
 * <p>The amount is rendered with the standard browser locale formatter
 * so a value of {@code 2500} reads as {@code 2,500} in en-US.
 */
export function CancellationConsequenceBadge({
  kind,
  amountL,
}: CancellationConsequenceBadgeProps) {
  if (!kind || kind === "NONE") {
    return (
      <StatusBadge tone="default" data-testid="cancellation-consequence-badge">
        No penalty
      </StatusBadge>
    );
  }
  switch (kind) {
    case "WARNING":
      return (
        <StatusBadge
          tone="warning"
          data-testid="cancellation-consequence-badge"
        >
          Warning
        </StatusBadge>
      );
    case "PENALTY":
      return (
        <StatusBadge
          tone="danger"
          data-testid="cancellation-consequence-badge"
        >
          L${(amountL ?? 0).toLocaleString()} penalty
        </StatusBadge>
      );
    case "PENALTY_AND_30D":
      return (
        <StatusBadge
          tone="danger"
          data-testid="cancellation-consequence-badge"
        >
          L${(amountL ?? 0).toLocaleString()} + 30-day suspension
        </StatusBadge>
      );
    case "PERMANENT_BAN":
      return (
        <StatusBadge
          tone="danger"
          data-testid="cancellation-consequence-badge"
        >
          Permanent ban
        </StatusBadge>
      );
    default:
      return null;
  }
}
