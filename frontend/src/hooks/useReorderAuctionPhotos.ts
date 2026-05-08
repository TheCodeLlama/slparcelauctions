"use client";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { reorderPhotos } from "@/lib/api/auctionPhotos";
import { auctionKey } from "@/hooks/useAuction";
import type { AuctionPhotoDto } from "@/types/auction";

/**
 * Mutation wrapper for {@code PATCH /api/v1/auctions/{auctionPublicId}/photos/order}.
 *
 * The mutation variable is the new ordered array of photo publicIds. On
 * settle the auction cache is invalidated so the server response becomes
 * the canonical source. Optimistic cache writes happen in the calling
 * component (EditablePhotoGallery) where the auction shape is already in
 * scope, not here.
 */
export function useReorderAuctionPhotos(auctionPublicId: string) {
  const qc = useQueryClient();
  return useMutation<AuctionPhotoDto[], unknown, string[]>({
    mutationFn: (orderedPublicIds: string[]) =>
      reorderPhotos(auctionPublicId, orderedPublicIds),
    onSettled: () => {
      qc.invalidateQueries({ queryKey: auctionKey(auctionPublicId) });
    },
  });
}
