import { Code2, Bot, UserCheck } from "@/components/ui/icons";
import { StatusBadge } from "@/components/ui/StatusBadge";
import type { VerificationTier } from "@/types/auction";

/**
 * Chip badge indicating how ownership of the listed parcel was verified.
 *
 * Tier map (mirrors sub-spec 1 §4.4 verification outcomes):
 *   - SCRIPT              — terminal rez-box verification via LSL
 *   - BOT                 — sale-to-bot ownership transfer
 *   - OWNERSHIP_TRANSFER  — full agent-to-agent transfer (highest assurance)
 *
 * Returns null when the auction has no tier yet (pre-verification /
 * failed verification). Upstream callers (e.g. ParcelInfoPanel) render this
 * inside a flex row that tolerates empty children.
 */
interface Props {
  tier: VerificationTier | null;
}

const ICON_SIZE = "size-3.5" as const;

export function VerificationTierBadge({ tier }: Props) {
  if (tier === null) return null;

  switch (tier) {
    case "SCRIPT":
      return (
        <StatusBadge tone="default" data-testid="verification-tier-badge" data-tier={tier}>
          <Code2 className={ICON_SIZE} aria-hidden="true" />
          Script verified
        </StatusBadge>
      );
    case "BOT":
      return (
        <StatusBadge tone="success" data-testid="verification-tier-badge" data-tier={tier}>
          <Bot className={ICON_SIZE} aria-hidden="true" />
          Bot verified
        </StatusBadge>
      );
    case "OWNERSHIP_TRANSFER":
      return (
        <StatusBadge tone="warning" data-testid="verification-tier-badge" data-tier={tier}>
          <UserCheck className={ICON_SIZE} aria-hidden="true" />
          Ownership transfer verified
        </StatusBadge>
      );
  }
}
