"use client";
import { Dialog, DialogPanel, DialogTitle } from "@headlessui/react";
import { Button } from "@/components/ui/Button";

export interface ConfirmListParcelModalProps {
  open: boolean;
  onClose: () => void;
  /** Fires the listing-fee mutation. Modal stays open until parent flips `open`. */
  onConfirm: () => void;
  listingFee: number;
  walletBalance: number;
  /** Pending = button shows spinner + close is disabled. */
  isListing?: boolean;
}

/**
 * Pre-debit confirmation for the "List parcel" action. Shows the seller
 * the listing fee, their current wallet balance, and the resulting
 * post-debit balance so they can verify the math before committing.
 *
 * Distinct from {@link DeleteDraftModal} — this is an L$-spending
 * confirmation, not a destructive one.
 */
export function ConfirmListParcelModal({
  open,
  onClose,
  onConfirm,
  listingFee,
  walletBalance,
  isListing = false,
}: ConfirmListParcelModalProps) {
  const balanceAfter = walletBalance - listingFee;

  return (
    <Dialog
      open={open}
      onClose={() => (isListing ? null : onClose())}
      className="relative z-50"
    >
      <div
        className="fixed inset-0 bg-inverse-surface/40 backdrop-blur-sm"
        aria-hidden="true"
      />
      <div className="fixed inset-0 flex items-center justify-center p-4">
        <DialogPanel
          data-testid="confirm-list-parcel-modal"
          className="w-full max-w-md flex flex-col gap-4 rounded-lg bg-bg-subtle p-6"
        >
          <DialogTitle className="text-base font-bold tracking-tight text-fg">
            List this parcel?
          </DialogTitle>
          <p className="text-sm text-fg-muted">
            Listing your parcel debits the listing fee from your SLParcels
            wallet and starts the verification flow. The fee is non-refundable
            once the listing reaches buyers.
          </p>
          <dl className="flex flex-col gap-2 rounded-lg bg-surface-raised p-4 text-sm">
            <div className="flex items-baseline justify-between">
              <dt className="text-fg-muted">Listing fee</dt>
              <dd className="font-medium text-fg">
                L${listingFee.toLocaleString()}
              </dd>
            </div>
            <div className="flex items-baseline justify-between">
              <dt className="text-fg-muted">Wallet balance</dt>
              <dd className="font-medium text-fg">
                L${walletBalance.toLocaleString()}
              </dd>
            </div>
            <div className="flex items-baseline justify-between border-t border-border-subtle pt-2">
              <dt className="text-fg-muted">Balance after</dt>
              <dd className="font-medium text-fg">
                L${balanceAfter.toLocaleString()}
              </dd>
            </div>
          </dl>
          <div className="flex justify-end gap-2">
            <Button
              variant="secondary"
              onClick={onClose}
              disabled={isListing}
              data-testid="confirm-list-parcel-cancel"
            >
              Cancel
            </Button>
            <Button
              variant="primary"
              onClick={onConfirm}
              disabled={isListing}
              loading={isListing}
              data-testid="confirm-list-parcel-confirm"
            >
              Confirm and list
            </Button>
          </div>
        </DialogPanel>
      </div>
    </Dialog>
  );
}
