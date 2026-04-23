"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { Dialog, DialogPanel, DialogTitle } from "@headlessui/react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/Button";
import { FormError } from "@/components/ui/FormError";
import { useToast } from "@/components/ui/Toast";
import { ApiError, isApiError } from "@/lib/api";
import { cancelAuction } from "@/lib/api/auctions";
import { computeRefund } from "@/lib/listing/refundCalculation";
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
 * "Cancel this listing?" confirmation modal.
 *
 * Refund copy is derived locally via {@link computeRefund} (spec §5.6):
 * the backend {@code CancellationService} is the authoritative owner
 * of the refund amount, but the seller deserves to see the correct
 * amount before clicking "Cancel listing". DRAFT → no refund,
 * DRAFT_PAID / VERIFICATION_PENDING / VERIFICATION_FAILED → full
 * refund, ACTIVE → forfeit.
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

  const refund = computeRefund(auction.status, auction.listingFeeAmt);

  const mutation = useMutation<SellerAuctionResponse, unknown, void>({
    mutationFn: () =>
      cancelAuction(auction.id, {
        reason: reason.trim() || undefined,
      }),
    onMutate: () => setError(null),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["my-listings"] });
      qc.invalidateQueries({ queryKey: ["auction", String(auction.id)] });
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
