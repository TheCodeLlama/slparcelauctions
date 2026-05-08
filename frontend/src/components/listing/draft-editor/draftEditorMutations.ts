"use client";
import { useQueryClient } from "@tanstack/react-query";
import { auctionKey } from "@/hooks/useAuction";
import { updateAuction } from "@/lib/api/auctions";
import type {
  AuctionDurationHours,
  AuctionSnipeWindowMin,
  AuctionUpdateRequest,
  SellerAuctionResponse,
} from "@/types/auction";

export interface DraftSettings {
  startingBid: number;
  reservePrice: number | null;
  buyNowPrice: number | null;
  durationHours: AuctionDurationHours;
  snipeProtect: boolean;
  snipeWindowMin: AuctionSnipeWindowMin | null;
}

/**
 * Per-field save helpers backed by the existing
 * {@code PUT /api/v1/auctions/{publicId}} endpoint. Each helper accepts
 * the new value, fires the update, replaces the auction cache with the
 * server response, and resolves. Errors propagate so {@link useInlineEdit}
 * can route them into its error slot.
 *
 * Parcel re-selection is intentionally absent — the backend's
 * {@code AuctionUpdateRequest} omits {@code slParcelUuid}, so a parcel
 * change requires deleting the draft and creating a new one.
 */
export function useDraftEditorMutations(auctionPublicId: string) {
  const qc = useQueryClient();

  const send = async (body: AuctionUpdateRequest) => {
    const updated = await updateAuction(auctionPublicId, body);
    qc.setQueryData(auctionKey(auctionPublicId), updated);
    return updated;
  };

  return {
    saveTitle: async (title: string): Promise<void> => {
      await send({ title });
    },
    saveDescription: async (sellerDesc: string): Promise<void> => {
      await send({ sellerDesc });
    },
    saveTags: async (tags: string[]): Promise<void> => {
      await send({ tags });
    },
    saveSettings: async (s: DraftSettings): Promise<void> => {
      await send({
        startingBid: s.startingBid,
        reservePrice: s.reservePrice,
        buyNowPrice: s.buyNowPrice,
        durationHours: s.durationHours,
        snipeProtect: s.snipeProtect,
        snipeWindowMin: s.snipeWindowMin,
      });
    },
  };
}

export type DraftEditorMutations = ReturnType<typeof useDraftEditorMutations>;

// Re-export for callers that only need the response shape.
export type { SellerAuctionResponse };
