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

/**
 * PATCH /api/v1/auctions/{auctionPublicId}/photos/order — atomic full-list
 * reorder. The body's photoPublicIds set must equal the auction's current
 * photo set; mismatch returns 400 PHOTO_SET_MISMATCH.
 */
export function reorderPhotos(
  auctionPublicId: string,
  photoPublicIds: string[],
): Promise<AuctionPhotoDto[]> {
  return api.patch<AuctionPhotoDto[]>(
    `/api/v1/auctions/${auctionPublicId}/photos/order`,
    { photoPublicIds },
  );
}

/**
 * POST /api/v1/auctions/{auctionPublicId}/photos/{photoPublicId}/dark —
 * multipart upload of the dark variant for a sort-0 default-cover photo.
 * Only {@code USER_DEFAULT_COVER} / {@code GROUP_DEFAULT_COVER} rows accept
 * a dark variant server-side. Returns the updated photo row.
 */
export function uploadPhotoDarkVariant(
  auctionPublicId: string,
  photoPublicId: string,
  file: File,
): Promise<AuctionPhotoDto> {
  const form = new FormData();
  form.append("file", file);
  return api.post<AuctionPhotoDto>(
    `/api/v1/auctions/${auctionPublicId}/photos/${photoPublicId}/dark`,
    form,
  );
}

/**
 * DELETE /api/v1/auctions/{auctionPublicId}/photos/{photoPublicId}/dark —
 * removes the dark variant, leaving the light slot intact. Returns the
 * updated photo row (now with {@code darkUrl: null}).
 */
export function deletePhotoDarkVariant(
  auctionPublicId: string,
  photoPublicId: string,
): Promise<AuctionPhotoDto> {
  return api.delete<AuctionPhotoDto>(
    `/api/v1/auctions/${auctionPublicId}/photos/${photoPublicId}/dark`,
  );
}
