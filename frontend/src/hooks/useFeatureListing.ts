"use client";

import { useState } from "react";
import { purchaseFeatured } from "@/lib/api/promotions";
import type { PurchaseFeaturedResponse } from "@/types/promotion";

export function useFeatureListing() {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<Error | null>(null);

  async function purchase(auctionPublicId: string): Promise<PurchaseFeaturedResponse> {
    setPending(true);
    setError(null);
    try {
      return await purchaseFeatured(auctionPublicId);
    } catch (e) {
      setError(e as Error);
      throw e;
    } finally {
      setPending(false);
    }
  }

  return { purchase, pending, error };
}
