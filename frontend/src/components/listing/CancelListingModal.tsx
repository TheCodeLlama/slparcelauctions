"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { Dialog, DialogPanel, DialogTitle } from "@headlessui/react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/Button";
import { FormError } from "@/components/ui/FormError";
import { useToast } from "@/components/ui/Toast";
import { ApiError, isApiError } from "@/lib/api";
import { useCurrentUser } from "@/lib/user";
import { cancelAuction } from "@/lib/api/auctions";
import { computeRefund } from "@/lib/listing/refundCalculation";
import { useCancellationStatus } from "@/hooks/useCancellationStatus";
import type { SellerAuctionResponse } from "@/types/auction";
import { ListingStatusBadge } from "./ListingStatusBadge";

export interface CancelListingModalProps {
  open: boolean;
  onClose: () => void;
  auction: SellerAuctionResponse;
  /**
   * Optional override for where to navigate on success. Defaults to
   * {@code /dashboard/listings} (the My Listings tab).
   */
  redirectTo?: string;
}

/**
 * Discriminator for the consequence-aware copy table (Epic 08 sub-spec 2
 * §8.1). The order matches the spec's top-down precedence: ban first
 * (overrides every ladder rung), then no-bids (no penalty regardless of
 * priors), then ladder rungs by prior count.
 */
type CopyVariant =
  | "BANNED"
  | "NO_BIDS"
  | "FIRST_OFFENSE"
  | "SECOND_OFFENSE"
  | "THIRD_OFFENSE"
  | "FOURTH_PLUS_OFFENSE";

/**
 * Sub-spec 2 §8.1 copy table, keyed by variant. The strings are inlined
 * here (rather than imported from a constants module) because they are
 * the test contract — every spec change should be a textual diff in this
 * map, not a remote-edited import.
 */
const COPY_MAP: Record<CopyVariant, string> = {
  BANNED:
    "You are permanently banned from creating new listings. This cancellation will be recorded.",
  NO_BIDS:
    "No penalty will apply. Listing fee is non-refundable for already-paid auctions.",
  FIRST_OFFENSE:
    "This is a cancellation with active bids. Your first such cancellation is recorded as a warning — no L$ penalty.",
  SECOND_OFFENSE:
    "This will be your 2nd cancellation with active bids. You will be suspended from new listings until you pay a L$1000 penalty at any SLPA terminal.",
  THIRD_OFFENSE:
    "This will be your 3rd cancellation with active bids. You will be suspended from new listings for 30 days AND must pay a L$2500 penalty before listing again. One more cancellation will result in a permanent ban.",
  FOURTH_PLUS_OFFENSE:
    "This will be your 4th cancellation with active bids. This will result in a permanent ban from new listings.",
};

/**
 * Resolves the copy variant from the user's ban state and the
 * cancellation-status fetch. Pure function — exported on the side so the
 * unit tests can drive every branch without rendering the modal.
 *
 * Precedence (top-down, first match wins):
 * <ol>
 *   <li>Banned seller → {@code BANNED} (overrides ladder).</li>
 *   <li>Auction has no bids → {@code NO_BIDS} (regardless of prior count).</li>
 *   <li>0 prior offenses → {@code FIRST_OFFENSE} (warning).</li>
 *   <li>1 prior offense → {@code SECOND_OFFENSE} (L$1000 penalty).</li>
 *   <li>2 prior offenses → {@code THIRD_OFFENSE} (L$2500 + 30d suspension).</li>
 *   <li>3+ prior offenses (unbanned) → {@code FOURTH_PLUS_OFFENSE}
 *       (permanent ban).</li>
 * </ol>
 *
 * <p>While the cancellation-status fetch is in flight (status is undefined)
 * and bids are present, the modal falls through to FIRST_OFFENSE — the
 * least-alarming default — so the copy never flashes the wrong rung
 * mid-load. Once the fetch resolves, the right copy snaps into place.
 */
export function resolveCopyVariant(args: {
  bannedFromListing: boolean;
  bidCount: number;
  priorOffensesWithBids: number | undefined;
}): CopyVariant {
  if (args.bannedFromListing) return "BANNED";
  if (args.bidCount <= 0) return "NO_BIDS";
  const prior = args.priorOffensesWithBids ?? 0;
  if (prior <= 0) return "FIRST_OFFENSE";
  if (prior === 1) return "SECOND_OFFENSE";
  if (prior === 2) return "THIRD_OFFENSE";
  return "FOURTH_PLUS_OFFENSE";
}

/**
 * "Cancel this listing?" confirmation modal.
 *
 * Refund copy is derived locally via {@link computeRefund} (spec §5.6):
 * the backend {@code CancellationService} is the authoritative owner
 * of the refund amount, but the seller deserves to see the correct
 * amount before clicking "Cancel listing". DRAFT → no refund,
 * DRAFT_PAID / VERIFICATION_PENDING / VERIFICATION_FAILED → full
 * refund, ACTIVE → forfeit.
 *
 * Sub-spec 2 §8.1 layers consequence-aware copy on top of the refund row:
 * the cancel-modal preview reads {@code /me/cancellation-status} on open
 * and renders one of six variants per the {@link COPY_MAP}, with the
 * permanent-ban variant taking precedence over every ladder rung.
 *
 * The reason textarea is optional. An empty/whitespace reason is sent
 * as {@code {}} (no reason key) rather than an empty string so the
 * backend doesn't persist meaningless whitespace.
 */
export function CancelListingModal({
  open,
  onClose,
  auction,
  redirectTo = "/dashboard/listings",
}: CancelListingModalProps) {
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const qc = useQueryClient();
  const router = useRouter();
  const toast = useToast();
  const { data: currentUser } = useCurrentUser();
  const { data: status } = useCancellationStatus();

  const refund = computeRefund(auction.status, auction.listingFeeAmt);

  const copyVariant = resolveCopyVariant({
    bannedFromListing: currentUser?.bannedFromListing ?? false,
    bidCount: auction.bidCount ?? 0,
    priorOffensesWithBids: status?.priorOffensesWithBids,
  });
  const consequenceCopy = COPY_MAP[copyVariant];

  const mutation = useMutation<SellerAuctionResponse, unknown, void>({
    mutationFn: () =>
      cancelAuction(auction.id, {
        reason: reason.trim() || undefined,
      }),
    onMutate: () => setError(null),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["my-listings"] });
      qc.invalidateQueries({ queryKey: ["auction", String(auction.id)] });
      // The status preview depends on the seller's offense history; a
      // successful cancel-with-bids changes priorOffensesWithBids and
      // possibly the suspension state, so invalidate both the status
      // and history queries to force a refetch on next open.
      qc.invalidateQueries({ queryKey: ["me", "cancellation-status"] });
      qc.invalidateQueries({ queryKey: ["me", "cancellation-history"] });
      qc.invalidateQueries({ queryKey: ["currentUser"] });
      toast.success("Listing cancelled.");
      onClose();
      router.push(redirectTo);
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
    <Dialog open={open} onClose={onClose} className="relative z-50">
      <div
        className="fixed inset-0 bg-inverse-surface/40 backdrop-blur-sm"
        aria-hidden="true"
      />
      <div className="fixed inset-0 flex items-center justify-center p-4">
        <DialogPanel className="w-full max-w-md flex flex-col gap-4 rounded-default bg-surface-container-low p-6">
          <DialogTitle className="text-title-lg text-on-surface">
            Cancel this listing?
          </DialogTitle>
          <div className="flex flex-col gap-2 text-body-md text-on-surface">
            <p>
              <span className="font-medium">
                {auction.parcel.description || "(unnamed parcel)"}
              </span>
            </p>
            <div>
              <ListingStatusBadge status={auction.status} />
            </div>
            <p
              className="text-body-sm text-on-surface-variant"
              data-testid="cancel-modal-consequence-copy"
              data-variant={copyVariant}
            >
              {consequenceCopy}
            </p>
            <p className="text-body-sm text-on-surface-variant">
              {refund.copy}
            </p>
          </div>
          <FormError message={error ?? undefined} />
          <label className="flex flex-col gap-1">
            <span className="sr-only">Reason for cancelling (optional)</span>
            <textarea
              rows={3}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="Reason (optional)"
              className="w-full resize-y rounded-default bg-surface-container-low px-4 py-3 text-on-surface placeholder:text-on-surface-variant ring-1 ring-outline-variant transition-all focus:outline-none focus:ring-primary"
            />
          </label>
          <div className="flex justify-end gap-2">
            <Button
              variant="secondary"
              onClick={onClose}
              disabled={mutation.isPending}
            >
              Keep listing
            </Button>
            <Button
              variant="destructive"
              onClick={() => mutation.mutate()}
              disabled={mutation.isPending}
              loading={mutation.isPending}
            >
              Cancel listing
            </Button>
          </div>
        </DialogPanel>
      </div>
    </Dialog>
  );
}
