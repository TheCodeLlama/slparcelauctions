// Mirrors backend ParcelResponse (com.slparcelauctions.backend.parcel.dto.ParcelResponse).
// Field shapes:
//   - id: omitted from the parcel block embedded in auction responses
//     (SellerAuctionResponse / PublicAuctionResponse) since the snapshot is
//     now auction-scoped. Still present on the standalone parcel-lookup
//     response (POST /api/v1/parcels/lookup) so it is kept optional here.
//   - slParcelUuid/ownerUuid: UUID (serialized as string)
//   - ownerType: backend stores "agent" or "group" (confirmed by
//     SlParcelVerifyService and ParcelMetadata)
//   - regionMaturityRating: "GENERAL" | "MODERATE" | "ADULT" per spec §4

/**
 * Owner type reported by the SL World API. {@code "agent"} = individual
 * avatar, {@code "group"} = group-owned land (requires SALE_TO_BOT method).
 */
export type ParcelOwnerType = "agent" | "group";

export type ParcelMaturityRating = "GENERAL" | "MODERATE" | "ADULT";

export interface ParcelDto {
  /** Legacy field present in old test fixtures. The backend dropped parcel.id
   *  from the wire shape — keep optional here so existing tests can still
   *  declare it without a type error. */
  id?: number;
  /** Legacy continent label from the pre-snapshot Parcel entity. Backend no
   *  longer emits this; kept optional for fixture compatibility. */
  continentName?: string | null;
  /** Backwards-compat alias for {@link regionMaturityRating}. New backend
   *  responses use the `regionMaturityRating` key; old fixtures still pass
   *  this. Components should prefer regionMaturityRating. */
  maturityRating?: ParcelMaturityRating;
  /** Legacy createdAt from the parcels row. Backend no longer emits this on
   *  the snapshot wire; kept optional for fixture compatibility. */
  createdAt?: string;
  slParcelUuid: string;
  ownerUuid: string;
  ownerType: ParcelOwnerType;
  /** SL display name for the avatar/group that owns the parcel. */
  ownerName: string | null;
  /** SL-side parcel display name (`<meta name="parcel">`). Used by the
   *  create-listing wizard to pre-fill the listing title. */
  parcelName: string | null;
  /** Backend region row id. Frontend doesn't surface it directly today
   *  but the field is on the wire — keep it optional so test fixtures and
   *  legacy code paths don't break by omitting it. */
  regionId?: number;
  regionName: string;
  /** SL maturity rating (snapshotted off the region row). Optional because
   *  legacy fixtures still pass {@link maturityRating} instead. */
  regionMaturityRating?: ParcelMaturityRating;
  gridX: number;
  gridY: number;
  // In-region coordinates of the parcel (World API-derived). Nullable to
  // match backend legacy rows where positions were not yet ingested; the
  // frontend detail page's VisitInSecondLifeBlock falls back to the
  // region-centre 128/128/0 when null.
  positionX: number | null;
  positionY: number | null;
  positionZ: number | null;
  areaSqm: number;
  description: string | null;
  snapshotUrl: string | null;
  slurl: string;
  verified: boolean;
  verifiedAt: string | null;
  lastChecked: string | null;
}
