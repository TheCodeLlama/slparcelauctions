"use client";
import { Button } from "@/components/ui/Button";

export interface DraftActionBarProps {
  listingFee: number;
  walletBalance: number;
  isListing?: boolean;
  insufficientFunds?: boolean;
  onListParcel: () => void;
  onDeleteDraft: () => void;
}

/**
 * Sticky top action bar for the DRAFT editor on /listings/[id]/activate.
 * Carries the listing-fee summary and the two terminal actions: List
 * Parcel (debits the fee, transitions to DRAFT_PAID) and Delete Draft
 * (cancels the auction, redirects to /dashboard/listings).
 */
export function DraftActionBar({
  listingFee,
  walletBalance,
  isListing = false,
  insufficientFunds = false,
  onListParcel,
  onDeleteDraft,
}: DraftActionBarProps) {
  return (
    <div
      data-testid="draft-action-bar"
      className="sticky top-0 z-40 flex flex-wrap items-center justify-between gap-3 bg-surface-raised px-4 py-3 ring-1 ring-border-subtle"
    >
      <div className="flex items-center gap-3 text-sm text-fg-muted">
        <span>
          Listing fee:{" "}
          <span className="font-medium text-fg">
            L${listingFee.toLocaleString()}
          </span>
        </span>
        <span aria-hidden="true">·</span>
        <span>
          Wallet:{" "}
          <span className="font-medium text-fg">
            L${walletBalance.toLocaleString()}
          </span>
        </span>
        {insufficientFunds && (
          <span className="text-xs text-danger" data-testid="draft-action-bar-low-funds">
            Top up wallet to list
          </span>
        )}
      </div>
      <div className="flex items-center gap-2">
        <Button
          variant="ghost"
          onClick={onDeleteDraft}
          disabled={isListing}
          data-testid="draft-action-bar-delete"
        >
          Delete draft
        </Button>
        <Button
          variant="primary"
          onClick={onListParcel}
          disabled={isListing || insufficientFunds}
          loading={isListing}
          data-testid="draft-action-bar-list"
        >
          List parcel
        </Button>
      </div>
    </div>
  );
}
