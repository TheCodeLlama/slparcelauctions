"use client";

import { useState } from "react";
import { useFeatureListing } from "@/hooks/useFeatureListing";
import { isApiError } from "@/lib/api";

interface Props {
  auctionPublicId: string;
  priceLindens: number;
  isOpen: boolean;
  onClose: () => void;
  onSuccess: () => void;
}

export function FeatureListingModal({
  auctionPublicId,
  priceLindens,
  isOpen,
  onClose,
  onSuccess,
}: Props) {
  const { purchase, pending } = useFeatureListing();
  const [message, setMessage] = useState<string | null>(null);

  if (!isOpen) return null;

  async function handleConfirm() {
    setMessage(null);
    try {
      await purchase(auctionPublicId);
      onSuccess();
    } catch (e) {
      if (isApiError(e)) {
        const code = e.problem.code as string | undefined;
        if (code === "PROMOTION_ALREADY_ACTIVE") {
          setMessage("This auction is already featured.");
        } else if (code === "INSUFFICIENT_AVAILABLE_BALANCE") {
          setMessage("Not enough wallet balance to buy Featured.");
        } else {
          setMessage(e.problem.detail ?? "Purchase failed.");
        }
      } else {
        setMessage("Purchase failed. Try again.");
      }
    }
  }

  return (
    <div className="fixed inset-0 bg-black/60 flex items-center justify-center z-50">
      <div className="bg-slate-900 text-white p-6 rounded max-w-md w-full">
        <h2 className="text-xl font-semibold">Feature this listing</h2>
        <p className="mt-3 text-sm text-slate-300">
          Pay L${priceLindens.toLocaleString()} from your SLParcels wallet to place
          this auction in the homepage Featured carousel and on one of the in-world
          HQ boards until the auction ends.
        </p>
        {message && <p className="mt-3 text-sm text-rose-300">{message}</p>}
        <div className="mt-5 flex justify-end gap-3">
          <button onClick={onClose} className="px-4 py-2 rounded border border-slate-600">
            Cancel
          </button>
          <button
            onClick={handleConfirm}
            disabled={pending}
            className="px-4 py-2 rounded bg-amber-600 hover:bg-amber-500 disabled:opacity-50"
          >
            {pending ? "Purchasing..." : `Pay L$${priceLindens.toLocaleString()}`}
          </button>
        </div>
      </div>
    </div>
  );
}
