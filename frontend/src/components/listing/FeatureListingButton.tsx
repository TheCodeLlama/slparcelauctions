"use client";

import { useState } from "react";
import { BadgeCheck } from "@/components/ui/icons";
import { FeatureListingModal } from "./FeatureListingModal";

interface Props {
  auctionPublicId: string;
  priceLindens: number;
  alreadyFeatured: boolean;
  onPurchased?: () => void;
}

export function FeatureListingButton({
  auctionPublicId,
  priceLindens,
  alreadyFeatured,
  onPurchased,
}: Props) {
  const [open, setOpen] = useState(false);

  if (alreadyFeatured) {
    return (
      <span className="inline-flex items-center gap-1 px-3 py-2 text-sm text-amber-400 font-semibold">
        <BadgeCheck className="h-4 w-4" /> Featured
      </span>
    );
  }

  return (
    <>
      <button
        onClick={() => setOpen(true)}
        className="px-4 py-2 rounded bg-amber-600 hover:bg-amber-500 text-white font-semibold"
      >
        Feature this listing for L${priceLindens.toLocaleString()}
      </button>
      <FeatureListingModal
        auctionPublicId={auctionPublicId}
        priceLindens={priceLindens}
        isOpen={open}
        onClose={() => setOpen(false)}
        onSuccess={() => { setOpen(false); onPurchased?.(); }}
      />
    </>
  );
}
