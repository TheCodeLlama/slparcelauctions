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
import type { SellerAuctionResponse } from "@/types/auction";

export interface DeleteDraftModalProps {
  open: boolean;
  onClose: () => void;
  auction: SellerAuctionResponse;
  redirectTo?: string;
}

/**
 * DRAFT-specific confirm-delete modal. Reuses the {@code cancelAuction}
 * endpoint (which for a never-bid-on DRAFT is effectively a hard delete:
 * no L$ has moved, no buyers have seen it). Simpler copy than the live
 * {@link CancelListingModal} because the DRAFT case has none of the
 * offense-ladder consequences.
 */
export function DeleteDraftModal({
  open,
  onClose,
  auction,
  redirectTo = "/dashboard/listings",
}: DeleteDraftModalProps) {
  const [error, setError] = useState<string | null>(null);
  const qc = useQueryClient();
  const router = useRouter();
  const toast = useToast();

  const mutation = useMutation<SellerAuctionResponse, unknown, void>({
    mutationFn: () => cancelAuction(auction.publicId, {}),
    onMutate: () => setError(null),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["my-listings"] });
      qc.invalidateQueries({ queryKey: ["auction", auction.publicId] });
      toast.success("Draft deleted.");
      onClose();
      router.push(redirectTo);
    },
    onError: (e) => {
      if (e instanceof ApiError || isApiError(e)) {
        setError(
          e.problem.detail ?? e.problem.title ?? "Could not delete this draft.",
        );
        return;
      }
      setError(e instanceof Error ? e.message : "Could not delete this draft.");
    },
  });

  return (
    <Dialog open={open} onClose={onClose} className="relative z-50">
      <div
        className="fixed inset-0 bg-inverse-surface/40 backdrop-blur-sm"
        aria-hidden="true"
      />
      <div className="fixed inset-0 flex items-center justify-center p-4">
        <DialogPanel
          data-testid="delete-draft-modal"
          className="w-full max-w-md flex flex-col gap-4 rounded-lg bg-bg-subtle p-6"
        >
          <DialogTitle className="text-base font-bold tracking-tight text-fg">
            Delete this draft?
          </DialogTitle>
          <p className="text-sm text-fg-muted">
            This can&apos;t be undone. Your draft and any uploaded photos will be
            removed.
          </p>
          <FormError message={error ?? undefined} />
          <div className="flex justify-end gap-2">
            <Button
              variant="secondary"
              onClick={onClose}
              disabled={mutation.isPending}
            >
              Keep draft
            </Button>
            <Button
              variant="destructive"
              onClick={() => mutation.mutate()}
              disabled={mutation.isPending}
              loading={mutation.isPending}
              data-testid="delete-draft-modal-confirm"
            >
              Delete draft
            </Button>
          </div>
        </DialogPanel>
      </div>
    </Dialog>
  );
}
