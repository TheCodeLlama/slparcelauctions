"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import {
  requestManualReview,
  verifySellTo,
  verifyTransfer,
} from "@/lib/api/escrow";
import { escrowKey } from "@/app/auction/[publicId]/escrow/EscrowPageClient";
import type { EscrowStatusResponse } from "@/types/escrow";

/**
 * Manual-action mutations for the TRANSFER_PENDING escrow sub-phases
 * (spec 2026-05-17-escrow-transfer-split-verification §9).
 *
 * Every mutation POSTs to its Task 2.3 endpoint and, on success,
 * invalidates the shared escrow query (`escrowKey(auctionPublicId)`, the
 * same key {@link EscrowPageClient} seeds and the STOMP subscription
 * invalidates) so the card re-derives its sub-phase from the authoritative
 * server response. The query key is reused — never forked — so async bot
 * results delivered over the `ESCROW_SELL_TO_SET` envelope and these
 * synchronous mutation refetches converge on one cache entry.
 */

/** Manual "Set Sell To" verify (202 async; result arrives via STOMP). */
export function useVerifySellTo(auctionPublicId: string) {
  const queryClient = useQueryClient();
  return useMutation<EscrowStatusResponse, unknown, void>({
    mutationFn: () => verifySellTo(auctionPublicId),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: escrowKey(auctionPublicId),
      });
    },
  });
}

/** Manual buy/transfer verify (200 sync). */
export function useVerifyTransfer(auctionPublicId: string) {
  const queryClient = useQueryClient();
  return useMutation<EscrowStatusResponse, unknown, void>({
    mutationFn: () => verifyTransfer(auctionPublicId),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: escrowKey(auctionPublicId),
      });
    },
  });
}

/** Request human review of a stuck escrow; optional free-text note. */
export function useRequestManualReview(auctionPublicId: string) {
  const queryClient = useQueryClient();
  return useMutation<EscrowStatusResponse, unknown, string | undefined>({
    mutationFn: (note?: string) => requestManualReview(auctionPublicId, note),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: escrowKey(auctionPublicId),
      });
    },
  });
}
