/**
 * Client-side photo staging. The Create/Edit listing form lets the seller
 * pick photos before the auction row exists (we don't have an auction id to
 * POST them to yet). Photos are held in an in-memory list with object URLs
 * for preview, then uploaded after the auction is created.
 *
 * Lifecycle:
 *   1. stagePhoto(file) → assigns a crypto uuid + creates an object URL.
 *      Callers must keep the returned object in state and call
 *      revokeStagedPhoto when the user removes it (or when the component
 *      unmounts) to avoid leaking Blob URLs.
 *   2. PhotoUploader calls validateFile(file) before staging and surfaces
 *      the returned message via the p.error slot on the thumbnail.
 *   3. After the auction is created, the parent component walks
 *      stagedPhotos, calls uploadPhoto() for each, and sets
 *      uploadedPhotoId so the UI can flip the "staged" chip to uploaded.
 */
export interface StagedPhoto {
  id: string;
  file: File;
  objectUrl: string;
  uploadedPhotoId: number | null;
  error: string | null;
}

export function stagePhoto(file: File): StagedPhoto {
  return {
    id: crypto.randomUUID(),
    file,
    objectUrl: URL.createObjectURL(file),
    uploadedPhotoId: null,
    error: null,
  };
}

export function revokeStagedPhoto(p: StagedPhoto): void {
  URL.revokeObjectURL(p.objectUrl);
}

/**
 * File-type guard for listing photos. Matches the backend
 * AuctionPhotoController contract (JPEG / PNG / WebP). No byte-size check
 * here — PhotoUploader resizes each picked file via
 * {@code browser-image-compression} (max 2048 px on the longest edge)
 * before staging, so the wire payload is always small regardless of the
 * source image. The server-side ListingPhotoProcessor still enforces a
 * 25 MB cap as DoS protection.
 */
export function validateFile(file: File): string | null {
  if (!["image/jpeg", "image/png", "image/webp"].includes(file.type)) {
    return "Only JPEG, PNG, or WebP images are accepted.";
  }
  return null;
}
