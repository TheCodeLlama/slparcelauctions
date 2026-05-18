import type { EscrowState } from "@/types/escrow";
import type { EscrowChipRole } from "@/components/escrow/EscrowChip";

export type BannerTone = "action" | "waiting" | "done" | "problem" | "muted";

export interface BannerCopyInput {
  state: EscrowState;
  role: EscrowChipRole;
  transferConfirmedAt: string | null;
  /**
   * Splits the TRANSFER_PENDING phase into the two verification sub-phases
   * (spec 2026-05-17 §9). Null → "Set Sell To" sub-phase (seller is
   * configuring the parcel's "Sell to:" field); set → "Buy Parcel" sub-phase
   * (winner buys the now-L$0 parcel). Like {@code fundedAt}, this is only
   * surfaced on the escrow DTO — the auction-DTO call site
   * ({@link EscrowBannerForPanel}) passes {@code null}, which keeps it in the
   * Set-Sell-To copy until the escrow page itself supplies the real value.
   */
  sellToConfirmedAt: string | null;
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
  const { state, role, transferConfirmedAt, sellToConfirmedAt } = input;

  switch (state) {
    case "ESCROW_PENDING":
      // Post wallet-only-escrow spec (2026-05-16) this state is a
      // transactional intermediate that never persists past commit. Any
      // row observed in this state is a legacy historical row.
      return {
        headline: "Escrow pending",
        detail: "funding in progress.",
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
      if (sellToConfirmedAt == null) {
        // Set-Sell-To sub-phase: seller configures the parcel's "Sell to:"
        // field to the winner at L$0; winner waits for verification.
        if (role === "winner") {
          return {
            headline: "Awaiting sell-to",
            detail: "seller is listing the parcel for sale to you.",
            tone: "waiting",
          };
        }
        return {
          headline: "Set parcel for sale",
          detail: "set the land for sale to the winner at L$0.",
          tone: "action",
        };
      }
      // Buy-Parcel sub-phase: winner buys the now-L$0 parcel.
      if (role === "winner") {
        return {
          headline: "Buy the parcel",
          detail: "purchase the parcel, only if it shows L$0.",
          tone: "action",
        };
      }
      return {
        headline: "Awaiting purchase",
        detail: "winner is buying the parcel.",
        tone: "waiting",
      };

    case "COMPLETED":
      return { headline: "Escrow complete", detail: "", tone: "done" };

    case "DISPUTED":
      return {
        headline: "Escrow disputed",
        detail: "SLParcels staff is reviewing.",
        tone: "problem",
      };

    case "FROZEN":
      return {
        headline: "Escrow frozen",
        detail: "SLParcels staff is investigating.",
        tone: "problem",
      };

    case "EXPIRED":
      return { headline: "Escrow expired", detail: "", tone: "muted" };
  }
}
