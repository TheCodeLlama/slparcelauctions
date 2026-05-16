"use client";

import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/Button";
import { ApiError, isApiError } from "@/lib/api";
import { triggerVerify } from "@/lib/api/auctions";
import type { SellerAuctionResponse } from "@/types/auction";
import { activateAuctionKey } from "@/hooks/useActivateAuction";

export interface SaleToBotSetupPanelProps {
  auctionPublicId: number | string;
  /**
   * Returns the seller to the method picker so they can pick a different
   * verification method without firing the SALE_TO_BOT mutation.
   */
  onBack: () => void;
}

/**
 * Step-3 setup panel for the Sale-to-bot verification method. Renders the
 * in-world steps the seller must complete (Set Land for Sale to
 * SLPAEscrow Resident at L$999,999,999) and a Verify button that fires
 * {@code PUT /auctions/{id}/verify} with method=SALE_TO_BOT only after
 * the seller has had a chance to set up the sale.
 *
 * Spec context: previously the picker fired verify immediately on click,
 * which raced the bot ahead of the seller setting the parcel for sale.
 * Splitting picker → setup → in-progress gives the seller a stable view
 * to follow the steps on before the bot starts watching.
 */
export function SaleToBotSetupPanel({
  auctionPublicId,
  onBack,
}: SaleToBotSetupPanelProps) {
  const qc = useQueryClient();
  const [error, setError] = useState<string | null>(null);

  const mutation = useMutation<SellerAuctionResponse, unknown, void>({
    mutationFn: () => triggerVerify(auctionPublicId, { method: "SALE_TO_BOT" }),
    onMutate: () => setError(null),
    onSuccess: (auction) => {
      qc.setQueryData(activateAuctionKey(auctionPublicId), auction);
    },
    onError: (e) => {
      if (e instanceof ApiError || isApiError(e)) {
        setError(
          e.problem.detail ??
            e.problem.title ??
            "Verification could not be started.",
        );
        return;
      }
      setError(
        e instanceof Error ? e.message : "Verification could not be started.",
      );
    },
  });

  return (
    <section
      aria-labelledby="sale-to-bot-setup-heading"
      data-testid="sale-to-bot-setup-panel"
      className="flex flex-col gap-4 rounded-lg bg-bg-subtle p-6"
    >
      <h3
        id="sale-to-bot-setup-heading"
        className="text-sm font-semibold tracking-tight text-fg"
      >
        Set your land for sale to SLPAEscrow Resident
      </h3>
      <p className="text-xs text-fg-muted">
        Complete these steps in-world first. Then click Verify so our bot
        starts watching for the sale event.
      </p>
      <ol className="list-decimal list-inside flex flex-col gap-1 text-sm text-fg">
        <li>Open the SL Land menu on the parcel.</li>
        <li>
          Choose <em>Set Land for Sale&hellip;</em>
        </li>
        <li>
          Buyer: <strong>SLPAEscrow Resident</strong>
        </li>
        <li>
          Price: <strong>L$999,999,999</strong>
        </li>
        <li>
          Click <em>Sell</em> to confirm in-world.
        </li>
      </ol>
      {error && (
        <div
          role="alert"
          className="rounded-lg bg-danger-bg px-4 py-3 text-xs font-medium text-danger"
        >
          {error}
        </div>
      )}
      <div className="flex flex-wrap items-center justify-end gap-2">
        <Button
          variant="secondary"
          onClick={onBack}
          disabled={mutation.isPending}
          data-testid="sale-to-bot-setup-back"
        >
          Choose a different method
        </Button>
        <Button
          onClick={() => mutation.mutate()}
          disabled={mutation.isPending}
          loading={mutation.isPending}
          data-testid="sale-to-bot-setup-verify"
        >
          {mutation.isPending ? "Starting…" : "Verify"}
        </Button>
      </div>
    </section>
  );
}
