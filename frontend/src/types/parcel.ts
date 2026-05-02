// Mirrors backend ParcelResponse (com.slparcelauctions.backend.parcel.dto.ParcelResponse).
// Field shapes:
//   - id: Long (number)
//   - slParcelUuid/ownerUuid: UUID (serialized as string)
//   - ownerType: backend stores "agent" or "group" (confirmed by
//     SlParcelVerifyService and ParcelMetadata)
//   - maturityRating: "GENERAL" | "MODERATE" | "ADULT" per spec §4

/**
 * Owner type reported by the SL World API. {@code "agent"} = individual
 * avatar, {@code "group"} = group-owned land (requires SALE_TO_BOT method).
 */
export type ParcelOwnerType = "agent" | "group";

export type ParcelMaturityRating = "GENERAL" | "MODERATE" | "ADULT";

export interface ParcelDto {
  id: number;
  slParcelUuid: string;
  ownerUuid: string;
  ownerType: ParcelOwnerType;
  /** SL display name for the avatar/group that owns the parcel. */
  ownerName: string | null;
  /** SL-side parcel display name (`<meta name="parcel">`). Used by the
   *  create-listing wizard to pre-fill the listing title. */
  parcelName: string | null;
  regionName: string;
  gridX: number;
  gridY: number;
  // In-region coordinates of the parcel (World API-derived). Nullable to
  // match backend legacy rows where positions were not yet ingested; the
  // frontend detail page's VisitInSecondLifeBlock falls back to the
  // region-centre 128/128/0 when null.
  positionX: number | null;
  positionY: number | null;
  positionZ: number | null;
  continentName: string | null;
  areaSqm: number;
  description: string | null;
  snapshotUrl: string | null;
  slurl: string;
  maturityRating: ParcelMaturityRating;
  verified: boolean;
  verifiedAt: string | null;
  lastChecked: string | null;
  createdAt: string;
}
