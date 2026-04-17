// Mirrors backend SellerAuctionResponse (com.slparcelauctions.backend.
// auction.dto.SellerAuctionResponse) and its sibling request DTOs.
// Key quirks vs. the plan skeleton:
//   - Numeric IDs, Long money fields (not BigDecimal-as-string), except
//     currentHighBid which is BigDecimal (serialized as number or string).
//   - sellerId is a Long — the backend DTO does not embed a
//     UserPublicProfile, so the frontend fetches it separately when needed.
//   - photos[].url (not bytesUrl) + contentType + sizeBytes + uploadedAt.
//   - bidCount: Integer, bidderCount: Long (both are `number` in TS).

import type { ParcelDto } from "./parcel";
import type { ParcelTagDto } from "./parcelTag";

/**
 * Full auction lifecycle, sub-spec 2 §6. All 14 statuses are represented so
 * UI primitives (e.g., ListingStatusBadge) can compile-time check coverage.
 */
export type AuctionStatus =
  | "DRAFT"
  | "DRAFT_PAID"
  | "VERIFICATION_PENDING"
  | "VERIFICATION_FAILED"
  | "ACTIVE"
  | "ENDED"
  | "ESCROW_PENDING"
  | "ESCROW_FUNDED"
  | "TRANSFER_PENDING"
  | "COMPLETED"
  | "CANCELLED"
  | "EXPIRED"
  | "DISPUTED"
  | "SUSPENDED";

export type VerificationMethod = "UUID_ENTRY" | "REZZABLE" | "SALE_TO_BOT";

export type VerificationTier = "SCRIPT" | "BOT" | "OWNERSHIP_TRANSFER";

/**
 * Populated on SellerAuctionResponse only while status ==
 * VERIFICATION_PENDING. UUID_ENTRY is synchronous and never surfaces a
 * pendingVerification object (its transitions bypass this field).
 */
export interface PendingVerification {
  method: VerificationMethod;
  code: string | null;
  codeExpiresAt: string | null;
  botTaskId: number | null;
  instructions: string | null;
}

export interface AuctionPhotoDto {
  id: number;
  url: string;
  contentType: string;
  sizeBytes: number;
  sortOrder: number;
  uploadedAt: string;
}

export interface SellerAuctionResponse {
  id: number;
  sellerId: number;
  parcel: ParcelDto;
  status: AuctionStatus;
  verificationMethod: VerificationMethod | null;
  verificationTier: VerificationTier | null;
  pendingVerification: PendingVerification | null;
  verificationNotes: string | null;
  startingBid: number;
  reservePrice: number | null;
  buyNowPrice: number | null;
  currentBid: number | null;
  bidCount: number;
  // currentHighBid is a backend BigDecimal — Jackson serializes as a
  // JSON number by default, but tolerate string too for safety.
  currentHighBid: number | string | null;
  bidderCount: number;
  winnerId: number | null;
  durationHours: number;
  snipeProtect: boolean;
  snipeWindowMin: number | null;
  startsAt: string | null;
  endsAt: string | null;
  originalEndsAt: string | null;
  sellerDesc: string | null;
  tags: ParcelTagDto[];
  photos: AuctionPhotoDto[];
  listingFeePaid: boolean;
  listingFeeAmt: number | null;
  listingFeeTxn: string | null;
  listingFeePaidAt: string | null;
  // BigDecimal → number | string.
  commissionRate: number | string;
  commissionAmt: number | null;
  createdAt: string;
  updatedAt: string;
}

/** Duration choices permitted by the backend (hours). */
export type AuctionDurationHours = 24 | 48 | 72 | 168 | 336;

/** Snipe-protection extension windows permitted by the backend (minutes). */
export type AuctionSnipeWindowMin = 5 | 10 | 15 | 30 | 60;

export interface AuctionCreateRequest {
  parcelId: number;
  startingBid: number;
  reservePrice?: number | null;
  buyNowPrice?: number | null;
  durationHours: AuctionDurationHours;
  snipeProtect: boolean;
  snipeWindowMin?: AuctionSnipeWindowMin | null;
  sellerDesc?: string;
  tags: string[];
}

export type AuctionUpdateRequest = Partial<
  Omit<AuctionCreateRequest, "parcelId">
>;

export interface AuctionVerifyRequest {
  method: VerificationMethod;
}

export interface AuctionCancelRequest {
  reason?: string;
}
