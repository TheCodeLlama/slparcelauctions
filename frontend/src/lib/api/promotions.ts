import { api } from "@/lib/api";
import type { PurchaseFeaturedResponse } from "@/types/promotion";

export function purchaseFeatured(auctionPublicId: string): Promise<PurchaseFeaturedResponse> {
  return api.post<PurchaseFeaturedResponse>(
    "/api/v1/me/promotions/featured",
    { auctionPublicId },
  );
}
