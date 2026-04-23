"use client";

import { Loader2 } from "@/components/ui/icons";
import type { SellerAuctionResponse } from "@/types/auction";
import { VerificationMethodRezzable } from "./VerificationMethodRezzable";
import { VerificationMethodSaleToBot } from "./VerificationMethodSaleToBot";
import { VerificationMethodUuidEntry } from "./VerificationMethodUuidEntry";

export interface VerificationInProgressPanelProps {
  auction: SellerAuctionResponse;
}

/**
 * Dispatches to the per-method in-progress panel based on
 * {@code auction.verificationMethod}.
 *
 * Edge case: the auction can land in VERIFICATION_PENDING one poll tick
 * before the backend has persisted a {@code pendingVerification} object.
 * For methods that need the pending payload (REZZABLE, SALE_TO_BOT) we
 * show a generic "starting verification…" spinner in that window rather
 * than a blank.
 */
function StartingSpinner() {
  return (
    <section
      aria-live="polite"
      className="flex flex-col items-center gap-3 rounded-default bg-surface-container-low p-6 text-center"
    >
      <Loader2
        aria-hidden="true"
        className="size-7 animate-spin text-primary"
      />
      <p className="text-title-sm text-on-surface">Starting verification…</p>
    </section>
  );
}

export function VerificationInProgressPanel({
  auction,
}: VerificationInProgressPanelProps) {
  switch (auction.verificationMethod) {
    case "UUID_ENTRY":
      return <VerificationMethodUuidEntry />;
    case "REZZABLE":
      return auction.pendingVerification ? (
        <VerificationMethodRezzable
          auctionId={auction.id}
          pending={auction.pendingVerification}
        />
      ) : (
        <StartingSpinner />
      );
    case "SALE_TO_BOT":
      return auction.pendingVerification ? (
        <VerificationMethodSaleToBot pending={auction.pendingVerification} />
      ) : (
        <StartingSpinner />
      );
    default:
      return <StartingSpinner />;
  }
}
