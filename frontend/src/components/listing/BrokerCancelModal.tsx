"use client";

import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/Button";
import { FormError } from "@/components/ui/FormError";
import { Modal } from "@/components/ui/Modal";
import { useToast } from "@/components/ui/Toast/useToast";
import { ApiError, isApiError } from "@/lib/api";
import { brokerCancelAuction } from "@/lib/api/auctions";
import { auctionKey } from "@/hooks/useAuction";
import type {
  PublicAuctionResponse,
  SellerAuctionResponse,
} from "@/types/auction";

export interface BrokerCancelModalProps {
  open: boolean;
  onClose: () => void;
  auction: PublicAuctionResponse | SellerAuctionResponse;
}

/**
 * Confirmation modal for the group-sale broker-cancel path. Surfaces the
 * auction title, active-bid state, and the listing-fee refund
 * destination (group wallet — never the seller's personal wallet, since
 * the wallet paid the fee). A non-empty reason is required so the
 * group ledger keeps the rationale for audit; the Confirm button is
 * disabled until the textarea has content.
 *
 * On confirm: POST {@code /api/v1/auctions/{publicId}/broker-cancel},
 * invalidate the auction + listing queries, show a success toast, and
 * close. Server errors surface inline via {@link FormError} so the
 * broker can retry without losing their typed reason.
 *
 * Realty Groups: E §6.5.
 */
export function BrokerCancelModal({
  open,
  onClose,
  auction,
}: BrokerCancelModalProps) {
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const qc = useQueryClient();
  const toast = useToast();

  const trimmedReason = reason.trim();
  const reasonValid = trimmedReason.length > 0;

  const bidCount = auction.bidCount ?? 0;
  const highestBidRaw = auction.currentHighBid;
  const highestBid =
    typeof highestBidRaw === "string"
      ? Number(highestBidRaw)
      : highestBidRaw ?? null;

  const mutation = useMutation<SellerAuctionResponse, unknown, void>({
    mutationFn: () =>
      brokerCancelAuction(auction.publicId, { reason: trimmedReason }),
    onMutate: () => setError(null),
    onSuccess: () => {
      // Wide invalidation — the auction's status flips to CANCELLED, the
      // group wallet receives a LISTING_FEE_REFUND ledger entry, and any
      // "my listings" / broker-visible listing tables need to refresh.
      qc.invalidateQueries({ queryKey: auctionKey(auction.publicId) });
      qc.invalidateQueries({ queryKey: ["my-listings"] });
      qc.invalidateQueries({ queryKey: ["auction", auction.publicId] });
      if (auction.realtyGroup?.publicId) {
        qc.invalidateQueries({
          queryKey: ["realty", "group", auction.realtyGroup.publicId, "wallet"],
        });
        qc.invalidateQueries({
          queryKey: ["realty", "group", auction.realtyGroup.publicId, "ledger"],
        });
      }
      toast.success("Listing cancelled.");
      setReason("");
      onClose();
    },
    onError: (e) => {
      if (e instanceof ApiError || isApiError(e)) {
        setError(
          e.problem.detail ??
            e.problem.title ??
            "Could not cancel this listing.",
        );
        return;
      }
      setError(
        e instanceof Error ? e.message : "Could not cancel this listing.",
      );
    },
  });

  return (
    <Modal
      open={open}
      title="Cancel this listing?"
      onClose={() => {
        if (mutation.isPending) return;
        onClose();
      }}
      footer={
        <>
          <Button
            variant="secondary"
            onClick={onClose}
            disabled={mutation.isPending}
            data-testid="broker-cancel-keep"
          >
            Keep listing
          </Button>
          <Button
            variant="destructive"
            onClick={() => mutation.mutate()}
            disabled={!reasonValid || mutation.isPending}
            loading={mutation.isPending}
            data-testid="broker-cancel-confirm"
          >
            Cancel listing
          </Button>
        </>
      }
    >
      <p className="text-sm text-fg">
        <span className="font-medium">{auction.title}</span>
      </p>
      <p className="text-xs text-fg-muted" data-testid="broker-cancel-bids">
        {bidCount > 0 && highestBid != null
          ? `${bidCount} bid${bidCount === 1 ? "" : "s"}. Current highest L$${highestBid.toLocaleString()}.`
          : "No bids on this listing yet."}
      </p>
      <p className="text-xs text-fg-muted">
        The listing fee refund will be credited to the group wallet.
      </p>
      <FormError message={error ?? undefined} />
      <label className="flex flex-col gap-1">
        <span className="text-xs font-medium text-fg-muted">
          Reason for cancelling (required)
        </span>
        <textarea
          rows={3}
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          placeholder="Why is this listing being cancelled?"
          aria-required="true"
          data-testid="broker-cancel-reason"
          className="w-full resize-y rounded-lg bg-bg-subtle px-4 py-3 text-fg placeholder:text-fg-muted ring-1 ring-border-subtle transition-all focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
        />
      </label>
    </Modal>
  );
}
