"use client";

import { useMemo, useState, type FormEvent } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";
import { useToast } from "@/components/ui/Toast";
import { ApiError, isApiError } from "@/lib/api";
import { placeBid } from "@/lib/api/auctions";
import { minRequiredBid } from "@/lib/auction/bidIncrement";
import type {
  BidResponse,
  PublicAuctionResponse,
  SellerAuctionResponse,
} from "@/types/auction";
import type { ConnectionState } from "@/lib/ws/types";
import { auctionKey } from "@/hooks/useAuction";
import {
  ConfirmBidDialog,
  isConfirmDismissed,
} from "./ConfirmBidDialog";

const LARGE_BID_THRESHOLD = 10_000;
const LARGE_BID_DISMISS_KEY = "slpa:bid:confirm:dismissed";

export interface PlaceBidFormProps {
  auction: PublicAuctionResponse | SellerAuctionResponse;
  connectionState: ConnectionState;
}

type ConfirmKind =
  | { kind: "none" }
  | { kind: "buy-now"; amount: number }
  | { kind: "large"; amount: number };

/**
 * Manual bid entry. Sits inside the BidPanel bidder variant alongside
 * {@link ProxyBidSection}. The form is intentionally narrow in scope:
 *
 * <ul>
 *   <li>No optimistic update — the WS settlement envelope drives the UI
 *       update after the row-lock commit, per spec §5 / §9.</li>
 *   <li>Three confirm-dialog branches (buy-now overspend, buy-now match,
 *       large bid) all reuse {@link ConfirmBidDialog}.</li>
 *   <li>Server-error surface mirrors spec §9's eight-code table —
 *       inline for field errors, invalidation for status errors, toast
 *       for shouldn't-reach codes.</li>
 * </ul>
 */
export function PlaceBidForm({ auction, connectionState }: PlaceBidFormProps) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const [amount, setAmount] = useState<string>("");
  const [inlineError, setInlineError] = useState<string | null>(null);
  const [confirm, setConfirm] = useState<ConfirmKind>({ kind: "none" });

  const min = useMemo(
    () => minRequiredBid(auction.currentHighBid, auction.startingBid),
    [auction.currentHighBid, auction.startingBid],
  );

  const buyNow = auction.buyNowPrice;
  const parsed = amount === "" ? NaN : Number(amount);
  const hasValidAmount = Number.isFinite(parsed) && parsed >= min;
  const isConnected = connectionState.status === "connected";

  const mutation = useMutation<BidResponse, unknown, number>({
    mutationFn: (value) => placeBid(auction.id, value),
    onMutate: () => {
      setInlineError(null);
    },
    onSuccess: () => {
      // No optimistic update — the envelope carries the settled state.
      setAmount("");
    },
    onError: (err) => {
      handleBidError(err);
    },
  });

  const handleBidError = (err: unknown) => {
    if (err instanceof ApiError || isApiError(err)) {
      const code = err.problem.code as string | undefined;
      if (code === "BID_TOO_LOW") {
        const serverMin = asNumber(err.problem.minRequired) ?? min;
        setInlineError(`Minimum bid is L$${serverMin.toLocaleString()}.`);
        setAmount(String(serverMin));
        return;
      }
      if (code === "AUCTION_NOT_ACTIVE" || code === "AUCTION_ALREADY_ENDED") {
        // Page is out of date — let the refetch drive the correct panel
        // variant (bidder → ended) or an explanatory status.
        queryClient.invalidateQueries({ queryKey: auctionKey(auction.id) });
        toast.error("This auction is no longer accepting bids.");
        return;
      }
      if (code === "SELLER_CANNOT_BID" || code === "NOT_VERIFIED") {
        // Shouldn't reach — UI prevents these variants from rendering
        // the form. Fallback to a generic toast so something visible
        // happens if the cache is stale.
        toast.error(err.problem.detail ?? "Could not place bid.");
        return;
      }
      // Any other ApiError — network-ish / 5xx / unmapped code.
      toast.error("Something went wrong. Please try again.");
      return;
    }
    toast.error("Something went wrong. Please try again.");
  };

  const submitBid = (value: number) => {
    mutation.mutate(value);
  };

  const onSubmit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (!hasValidAmount || !isConnected || mutation.isPending) return;

    if (buyNow != null && parsed >= buyNow) {
      // Covers both the overspend (amount > buyNow) and the exact-match
      // (amount === buyNow) cases — spec §9 fires the same dialog for
      // both.
      setConfirm({ kind: "buy-now", amount: parsed });
      return;
    }

    if (parsed > LARGE_BID_THRESHOLD) {
      if (isConfirmDismissed(LARGE_BID_DISMISS_KEY)) {
        submitBid(parsed);
        return;
      }
      setConfirm({ kind: "large", amount: parsed });
      return;
    }

    submitBid(parsed);
  };

  const isBuyNowMatch =
    buyNow != null && Number.isFinite(parsed) && parsed === buyNow;
  const buttonLabel = isBuyNowMatch
    ? `Buy now · L$${buyNow!.toLocaleString()}`
    : "Place bid";

  const submitDisabled =
    !hasValidAmount || !isConnected || mutation.isPending;

  return (
    <form onSubmit={onSubmit} data-testid="place-bid-form" className="flex flex-col gap-3">
      <div>
        <label
          htmlFor="place-bid-amount"
          className="text-label-md text-on-surface-variant"
        >
          Your bid
        </label>
        <Input
          id="place-bid-amount"
          type="number"
          inputMode="numeric"
          min={min}
          step={1}
          value={amount}
          onChange={(e) => setAmount(e.target.value)}
          placeholder={`L$${min.toLocaleString()}`}
          leftIcon={<span className="text-label-md">L$</span>}
          className="text-right"
          data-testid="place-bid-amount-input"
          helperText={
            inlineError
              ? undefined
              : `Minimum bid: L$${min.toLocaleString()}`
          }
          error={inlineError ?? undefined}
        />
      </div>
      <Button
        type="submit"
        variant="primary"
        fullWidth
        disabled={submitDisabled}
        loading={mutation.isPending}
        data-testid="place-bid-submit"
      >
        {buttonLabel}
      </Button>
      {!isConnected ? (
        <p
          className="text-body-sm text-on-surface-variant"
          data-testid="place-bid-connection-helper"
        >
          Waiting for connection…
        </p>
      ) : null}

      {confirm.kind === "buy-now" && buyNow != null ? (
        <ConfirmBidDialog
          isOpen
          title={`Trigger buy-now at L$${buyNow.toLocaleString()}?`}
          message={`This will trigger buy-now at L$${buyNow.toLocaleString()}. You won't pay more than the buy-now price regardless of what you enter.`}
          confirmLabel={`Buy now · L$${buyNow.toLocaleString()}`}
          onConfirm={() => {
            setConfirm({ kind: "none" });
            submitBid(confirm.amount);
          }}
          onClose={() => setConfirm({ kind: "none" })}
        />
      ) : null}

      {confirm.kind === "large" ? (
        <ConfirmBidDialog
          isOpen
          title={`Confirm L$${confirm.amount.toLocaleString()}?`}
          message={`You're about to bid L$${confirm.amount.toLocaleString()}. This is a large bid — please confirm.`}
          confirmLabel="Place bid"
          dontAskAgainKey={LARGE_BID_DISMISS_KEY}
          onConfirm={() => {
            const amt = confirm.amount;
            setConfirm({ kind: "none" });
            submitBid(amt);
          }}
          onClose={() => setConfirm({ kind: "none" })}
        />
      ) : null}
    </form>
  );
}

/**
 * Coerces an RFC 7807 extension property ({@code minRequired} on the
 * bid-too-low problem detail) to a number. Jackson serializes Long as a
 * JSON number, but accept both for safety — the helper returns null on
 * anything that can't coerce so the caller can fall back to the
 * client-computed floor.
 */
function asNumber(value: unknown): number | null {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (typeof value === "string") {
    const n = Number(value);
    return Number.isFinite(n) ? n : null;
  }
  return null;
}
