import type { EscrowState } from "@/types/escrow";
import type { EscrowChipRole } from "@/components/escrow/EscrowChip";

export type BannerTone = "action" | "waiting" | "done" | "problem" | "muted";

export interface BannerCopyInput {
  state: EscrowState;
  role: EscrowChipRole;
  transferConfirmedAt: string | null;
  /**
   * Reserved for future EXPIRED branching (pre-fund vs post-fund refund copy).
   * The current banner copy table does not branch on {@code fundedAt} — the
   * escrow page's {@code ExpiredStateCard} handles the pre/post-fund split.
   * Accepted here so callers can forward the DTO value uniformly once
   * backend enrichment surfaces it.
   */
  fundedAt: string | null;
}

export interface BannerCopyResult {
  headline: string;
  detail: string;
  tone: BannerTone;
}

/**
 * Produces the role-aware + sub-state-aware copy + tone for the
 * {@link AuctionEndedPanel} escrow banner. See sub-spec 2 §5.2 for the copy
 * table. {@code FUNDED} is treated identically to {@code TRANSFER_PENDING}
 * because sub-spec 1 atomically advances FUNDED within the same transaction,
 * so external observers rarely see it — mirroring the {@link EscrowChip}
 * convention.
 */
export function escrowBannerCopy(input: BannerCopyInput): BannerCopyResult {
  const { state, role, transferConfirmedAt } = input;

  switch (state) {
    case "ESCROW_PENDING":
      if (role === "winner") {
        return {
          headline: "Pay escrow",
          detail: "at an SLPA terminal in-world.",
          tone: "action",
        };
      }
      return {
        headline: "Escrow pending",
        detail: "waiting for buyer to pay.",
        tone: "waiting",
      };

    case "FUNDED":
    case "TRANSFER_PENDING":
      if (transferConfirmedAt) {
        return {
          headline: "Payout pending",
          detail: "finalizing the transaction.",
          tone: "waiting",
        };
      }
      if (role === "winner") {
        return {
          headline: "Awaiting transfer",
          detail: "seller is transferring the parcel.",
          tone: "waiting",
        };
      }
      return {
        headline: "Transfer parcel",
        detail: "set the land for sale to the winner at L$0.",
        tone: "action",
      };

    case "COMPLETED":
      return { headline: "Escrow complete", detail: "", tone: "done" };

    case "DISPUTED":
      return {
        headline: "Escrow disputed",
        detail: "SLPA staff is reviewing.",
        tone: "problem",
      };

    case "FROZEN":
      return {
        headline: "Escrow frozen",
        detail: "SLPA staff is investigating.",
        tone: "problem",
      };

    case "EXPIRED":
      return { headline: "Escrow expired", detail: "", tone: "muted" };
  }
}
