"use client";

import { useListingFeeConfig } from "@/hooks/useListingFeeConfig";

export interface FeePaymentInstructionsProps {
  auctionId: number | string;
}

/**
 * DRAFT-state panel: "pay the listing fee at an in-world terminal".
 *
 * The fee amount is read from the public listing-fee config endpoint
 * via {@link useListingFeeConfig}. The reference code shown to the
 * seller is {@code LISTING-{shortId}} where shortId is the first 8
 * characters of the auction id (spec §5.2). Since backend auction ids
 * are numeric Longs today the "short id" is really the zero-padded id,
 * but we keep the first-8-chars shape so this still does the right
 * thing when/if the backend moves auctions to UUID identifiers.
 *
 * There is no action button — this is a polling waiting state; the
 * activate page pivots the UI automatically once the backend marks the
 * listing fee as paid.
 */
function shortIdFor(auctionId: number | string): string {
  return String(auctionId).padStart(8, "0").slice(0, 8);
}

export function FeePaymentInstructions({
  auctionId,
}: FeePaymentInstructionsProps) {
  const { data } = useListingFeeConfig();
  const fee = data?.amountLindens;
  const reference = `LISTING-${shortIdFor(auctionId)}`;

  return (
    <section
      aria-labelledby="fee-heading"
      className="rounded-default bg-surface-container-low p-6 flex flex-col gap-4"
    >
      <h2 id="fee-heading" className="text-title-lg text-on-surface">
        Pay the listing fee
      </h2>
      <p className="text-body-md text-on-surface">
        Head to an SLPA terminal in-world and pay{" "}
        <strong>L${fee ?? "…"}</strong> with reference code{" "}
        <code className="rounded bg-surface-container-high px-1.5 py-0.5 font-mono text-body-sm text-on-surface">
          {reference}
        </code>
        . Once the platform detects your payment, this page advances
        automatically.
      </p>
      <p className="text-body-sm text-on-surface-variant">
        In-world payment terminals roll out in a later epic. Dev environments
        can use the staging endpoint to advance this listing.
      </p>
    </section>
  );
}
