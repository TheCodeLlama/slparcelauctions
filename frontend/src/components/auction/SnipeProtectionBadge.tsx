import { Shield } from "@/components/ui/icons";
import { StatusBadge } from "@/components/ui/StatusBadge";

/**
 * Chip indicating that the auction has snipe-protection enabled and what the
 * extension window is. Text format `"Snipe {minutes}m"` matches the copy in
 * spec §8. Caller is responsible for gating on {@code auction.snipeProtect};
 * this component always renders (no null-branch) so the layout slot stays
 * stable.
 */
interface Props {
  minutes: number;
}

export function SnipeProtectionBadge({ minutes }: Props) {
  return (
    <StatusBadge tone="default" data-testid="snipe-protection-badge">
      <Shield className="size-3.5" aria-hidden="true" />
      Snipe {minutes}m
    </StatusBadge>
  );
}
