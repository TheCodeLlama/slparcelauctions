import { api } from "@/lib/api";
import type { AuctionPhotoDto } from "@/types/auction";

/**
 * POST /api/v1/auctions/{id}/photos — multipart upload. The shared api
 * helper detects FormData and omits the Content-Type header so the browser
 * can set the multipart boundary.
 */
export function uploadPhoto(
  auctionId: number | string,
  file: File,
): Promise<AuctionPhotoDto> {
  const form = new FormData();
  form.append("file", file);
  return api.post<AuctionPhotoDto>(
    `/api/v1/auctions/${auctionId}/photos`,
    form,
  );
}

/**
 * DELETE /api/v1/auctions/{id}/photos/{photoId}. Returns 204 — the shared
 * api helper maps that to void so consumers can just await the promise.
 */
export function deletePhoto(
  auctionId: number | string,
  photoId: number | string,
): Promise<void> {
  return api.delete<void>(`/api/v1/auctions/${auctionId}/photos/${photoId}`);
}
