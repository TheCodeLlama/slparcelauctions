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
  regionName: string;
  gridX: number;
  gridY: number;
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
