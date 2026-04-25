// Mirrors backend SellerAuctionResponse (com.slparcelauctions.backend.
// auction.dto.SellerAuctionResponse) and its sibling request DTOs.
// Key quirks vs. the plan skeleton:
//   - Numeric IDs, Long money fields (not BigDecimal-as-string), except
//     currentHighBid which is BigDecimal (serialized as number or string).
//   - sellerId is a Long — the backend DTO does not embed a
//     UserPublicProfile, so the frontend fetches it separately when needed.
//   - photos[].url (not bytesUrl) + contentType + sizeBytes + uploadedAt.
//   - bidCount: Integer, bidderCount: Long (both are `number` in TS).

import type { EscrowEnvelope, EscrowState } from "./escrow";
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

/**
 * Enriched seller block returned on the auction detail endpoint.
 * Mirrors {@code PublicAuctionResponse.SellerSummary} server-side (Epic 07
 * sub-spec 1 Task 2 extended the DTO). {@code averageRating} and
 * {@code completionRate} are {@code null} for sellers without enough data
 * points; consumers render "New Seller" / "Too new to calculate" copy in
 * that case. {@code avatarUrl} points at
 * {@code /api/v1/users/{id}/avatar/256} which already serves cached +
 * placeholder avatars, so the UI never needs to fall back to initials for
 * a valid server response.
 */
export interface AuctionSellerSummaryDto {
  id: number;
  displayName: string;
  avatarUrl: string | null;
  averageRating: number | string | null;
  reviewCount: number | null;
  completedSales: number;
  completionRate: number | string | null;
  memberSince: string | null;
}

export interface SellerAuctionResponse {
  id: number;
  sellerId: number;
  /**
   * Seller-authored display title for the listing. Required by the backend
   * (Epic 07 sub-spec 1 Task 2 added {@code title NOT NULL} to the auction
   * row and the {@code AuctionCreateRequest} DTO). Treated as the primary
   * headline on browse cards, summary rows, and the auction detail page;
   * {@code parcel.description} + {@code parcel.regionName} remain as
   * secondary / fallback text.
   */
  title: string;
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
  /**
   * Enriched seller block. Present on every {@code GET /api/v1/auctions/{id}}
   * response after Epic 07 sub-spec 1 Task 2; kept optional on the TS type
   * to tolerate legacy fixtures that predate the enrichment.
   */
  seller?: AuctionSellerSummaryDto | null;
  listingFeePaid: boolean;
  listingFeeAmt: number | null;
  listingFeeTxn: string | null;
  listingFeePaidAt: string | null;
  // BigDecimal → number | string.
  commissionRate: number | string;
  commissionAmt: number | null;
  createdAt: string;
  updatedAt: string;
  // Sub-spec 2: escrow enrichment. Populated once the auction reaches ENDED
  // and an escrow row exists; null otherwise.
  escrowState?: EscrowState | null;
  transferConfirmedAt?: string | null;
}

/** Duration choices permitted by the backend (hours). */
export type AuctionDurationHours = 24 | 48 | 72 | 168 | 336;

/** Snipe-protection extension windows permitted by the backend (minutes). */
export type AuctionSnipeWindowMin = 5 | 10 | 15 | 30 | 60;

export interface AuctionCreateRequest {
  parcelId: number;
  /**
   * Seller-authored display title. Required by the backend (sub-spec 1
   * Task 2); validated 1–120 chars. The wizard trims whitespace before
   * sending.
   */
  title: string;
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

/**
 * Reduced status enum for the public auction view. Backend collapses the four
 * terminal statuses (COMPLETED, CANCELLED, EXPIRED, DISPUTED) to ENDED so the
 * public cannot infer why an auction ended. Mirrors
 * {@code com.slparcelauctions.backend.auction.dto.PublicAuctionStatus}.
 */
export type PublicAuctionStatus = "ACTIVE" | "ENDED";

/**
 * Public auction view returned by GET /api/v1/auctions/{id} to non-sellers and
 * by the user-scoped active-listings endpoint. Deliberately excludes winnerId,
 * reservePrice (exposes only hasReserve + reserveMet), listing-fee + commission
 * fields, verification notes, pendingVerification, and seller-only verification
 * metadata — mirrors {@code PublicAuctionResponse} server-side.
 */
export interface PublicAuctionResponse {
  id: number;
  sellerId: number;
  /**
   * Seller-authored display title. See {@link SellerAuctionResponse#title} —
   * the public DTO mirrors the same field so browse cards and the public
   * auction detail page can render the headline without a second fetch.
   */
  title: string;
  parcel: ParcelDto;
  status: PublicAuctionStatus;
  verificationTier: VerificationTier | null;
  startingBid: number;
  hasReserve: boolean;
  reserveMet: boolean;
  buyNowPrice: number | null;
  currentBid: number | null;
  bidCount: number;
  // BigDecimal on the backend — see SellerAuctionResponse for serialization
  // rationale.
  currentHighBid: number | string | null;
  bidderCount: number;
  durationHours: number;
  snipeProtect: boolean;
  snipeWindowMin: number | null;
  startsAt: string | null;
  endsAt: string | null;
  originalEndsAt: string | null;
  sellerDesc: string | null;
  tags: ParcelTagDto[];
  photos: AuctionPhotoDto[];
  /**
   * Enriched seller block added by Epic 07 sub-spec 1 Task 2. Optional for
   * forward/back compatibility — older server builds or fixtures without
   * this field keep {@code sellerId} as the sole seller signal and the
   * detail-page card falls back accordingly.
   */
  seller?: AuctionSellerSummaryDto | null;
  // Sub-spec 2: escrow enrichment. Populated once the auction reaches ENDED
  // and an escrow row exists; null otherwise.
  escrowState?: EscrowState | null;
  transferConfirmedAt?: string | null;
}

/**
 * A bid's provenance. MANUAL = submitted directly via the place-bid form;
 * PROXY_AUTO = emitted by the proxy-bid engine as a response to a competitor;
 * BUY_NOW = the single inline-close bid emitted when the caller triggers the
 * buy-it-now threshold. Mirrors backend enum
 * {@code com.slparcelauctions.backend.auction.BidType}.
 */
export type BidType = "MANUAL" | "PROXY_AUTO" | "BUY_NOW";

/**
 * Terminal outcome once an auction reaches an end state. Mirrors
 * {@code com.slparcelauctions.backend.auction.AuctionEndOutcome}.
 */
export type AuctionEndOutcome =
  | "SOLD"
  | "RESERVE_NOT_MET"
  | "NO_BIDS"
  | "BOUGHT_NOW";

/**
 * Derived status the My Bids dashboard uses to theme each row. Computed
 * server-side per spec §10 from the auction lifecycle + whether the caller is
 * currently winning vs. outbid. Mirrors
 * {@code com.slparcelauctions.backend.auction.mybids.MyBidStatus}.
 */
export type MyBidStatus =
  | "WINNING"
  | "OUTBID"
  | "WON"
  | "LOST"
  | "RESERVE_NOT_MET"
  | "CANCELLED"
  | "SUSPENDED";

/**
 * Single public row in the bid history for an auction. Matches backend
 * {@code BidHistoryEntry} — omits abuse-audit fields (ipAddress) and the
 * internal proxyBidId linkage. snipeExtensionMinutes + newEndsAt are stamped
 * on the bid row that triggered a snipe-protection extension (null otherwise).
 */
export interface BidHistoryEntry {
  bidId: number;
  userId: number;
  bidderDisplayName: string;
  amount: number;
  bidType: BidType;
  snipeExtensionMinutes: number | null;
  newEndsAt: string | null;
  createdAt: string;
}

/**
 * WebSocket envelope broadcast on {@code /topic/auction/{auctionId}} after a
 * bid commits. Carries enough post-commit state for the client to update the
 * BidPanel without an additional GET and a tail of {@link BidHistoryEntry}s
 * that should be prepended to the cached page 0 (deduped by bidId). See spec
 * §5 for the merge sequence.
 */
export interface BidSettlementEnvelope {
  type: "BID_SETTLEMENT";
  auctionId: number;
  serverTime: string;
  currentBid: number;
  currentBidderId: number;
  currentBidderDisplayName: string;
  bidCount: number;
  endsAt: string;
  originalEndsAt: string;
  newBids: BidHistoryEntry[];
}

/**
 * Terminal envelope broadcast when the auction ends (natural expiry, buy-now
 * inline close, or sweeper tick). After this arrives, the client transitions
 * the BidPanel to the AuctionEndedPanel variant without a scroll jump.
 */
export interface AuctionEndedEnvelope {
  type: "AUCTION_ENDED";
  auctionId: number;
  serverTime: string;
  endsAt: string;
  endOutcome: AuctionEndOutcome;
  finalBid: number | null;
  winnerUserId: number | null;
  winnerDisplayName: string | null;
  bidCount: number;
}

/** Discriminated union of the two auction-topic envelopes. */
export type AuctionEnvelope = BidSettlementEnvelope | AuctionEndedEnvelope;

/**
 * WebSocket envelope broadcast on {@code /topic/auction/{auctionId}} when a
 * review flips from {@code visible=false} to {@code visible=true} — either
 * via the simultaneous-reveal branch in {@code ReviewService.submit} or the
 * hourly {@code BlindReviewRevealTask} scheduler sweep (Epic 08 sub-spec 1
 * §7).
 *
 * <p>Subscribers should invalidate their {@code useAuctionReviews(auctionId)}
 * query and refetch through the authenticated GET — the payload deliberately
 * omits the rating/text fields so public subscribers can't read review
 * content via the topic. No {@code serverTime} field: the backend
 * {@code ReviewRevealedEnvelope} record carries only the discriminators
 * needed to drive the client-side invalidation.
 */
export interface ReviewRevealedEnvelope {
  type: "REVIEW_REVEALED";
  auctionId: number;
  reviewId: number;
  reviewerId: number;
  revieweeId: number;
  reviewedRole: "SELLER" | "BUYER";
  revealedAt: string;
}

/**
 * Broadcast on {@code /topic/auction/{auctionId}} when an auction transitions
 * to {@code CANCELLED} (Epic 08 sub-spec 2 §8.5). Subscribers in the auction
 * detail page invalidate their auction query so the page transitions to the
 * "auction cancelled" UI on the next refetch — bidders mid-bid see their bid
 * form replaced by the cancellation banner. Like {@link ReviewRevealedEnvelope}
 * this envelope does not carry a {@code serverTime} field; subscribers that
 * read it should short-circuit on the type discriminator first.
 */
export interface AuctionCancelledEnvelope {
  type: "AUCTION_CANCELLED";
  auctionId: number;
  cancelledAt: string;
  hadBids: boolean;
}

/**
 * Every envelope type that can arrive on `/topic/auction/{id}`.
 * {@code AuctionDetailClient} and {@code EscrowPageClient} both subscribe
 * with this union; handlers discriminate on {@code type}. Re-exports the
 * nine escrow variants from `./escrow` via the imported EscrowEnvelope so
 * subscribers only need a single discriminator switch. The review envelope
 * lives on the same topic (Epic 08 sub-spec 1 §7) — subscribers that don't
 * care about reviews should short-circuit on {@code type === "REVIEW_REVEALED"}
 * before reading any envelope-specific field, since review envelopes do not
 * carry {@code serverTime}. The cancellation envelope (sub-spec 2 §8.5) is
 * the same shape pattern — early-return on {@code type === "AUCTION_CANCELLED"}
 * before reading {@code serverTime}.
 */
export type AuctionTopicEnvelope =
  | AuctionEnvelope
  | EscrowEnvelope
  | ReviewRevealedEnvelope
  | AuctionCancelledEnvelope;

/**
 * POST /api/v1/auctions/{id}/bids response body. Returns the just-committed
 * bid plus the post-commit auction-level fields the client needs to render
 * without a second GET. {@code buyNowTriggered} flips true when the submitted
 * amount crossed the buy-it-now threshold and the auction closed inline.
 */
export interface BidResponse {
  bidId: number;
  auctionId: number;
  amount: number;
  bidType: BidType;
  bidCount: number;
  endsAt: string;
  originalEndsAt: string;
  snipeExtensionMinutes: number | null;
  newEndsAt: string | null;
  buyNowTriggered: boolean;
}

/**
 * Response body for the four proxy-bid endpoints. {@code status} tracks the
 * caller's proxy lifecycle: ACTIVE (competing), EXHAUSTED (outbid beyond
 * {@code maxAmount}), or CANCELLED. Nothing auction-scoped is included — the
 * WS envelope covers currentBid/endsAt changes triggered by the proxy call.
 */
export interface ProxyBidResponse {
  proxyBidId: number;
  auctionId: number;
  maxAmount: number;
  status: "ACTIVE" | "EXHAUSTED" | "CANCELLED";
  createdAt: string;
  updatedAt: string;
}

/**
 * Compact auction projection embedded in {@link MyBidSummary}. Distinct from
 * {@link PublicAuctionResponse} — this is a flat, pre-joined shape optimised
 * for rendering a dashboard row (parcel name/region/area inline on the row,
 * no nested parcel object). {@code parcelName} is sourced from
 * {@code parcel.location} server-side; bidderCount is
 * auction.bidCount.longValue() per backend comments.
 */
export interface AuctionSummaryForMyBids {
  id: number;
  status: AuctionStatus;
  endOutcome: AuctionEndOutcome | null;
  parcelName: string | null;
  parcelRegion: string | null;
  parcelAreaSqm: number | null;
  snapshotUrl: string | null;
  endsAt: string | null;
  endedAt: string | null;
  currentBid: number | null;
  bidderCount: number;
  sellerUserId: number | null;
  sellerDisplayName: string | null;
  // Sub-spec 2: escrow enrichment. Populated once the auction reaches ENDED
  // and an escrow row exists; null otherwise.
  escrowState?: EscrowState | null;
  transferConfirmedAt?: string | null;
}

/**
 * Single My Bids dashboard row. Combines the auction summary, the caller's own
 * activity (highest bid amount + timestamp, optional proxy max), and the
 * derived {@link MyBidStatus}. {@code myProxyMaxAmount} is non-null only when
 * the caller currently owns an ACTIVE proxy — EXHAUSTED / CANCELLED proxies
 * do not resurrect here.
 */
export interface MyBidSummary {
  auction: AuctionSummaryForMyBids;
  myHighestBidAmount: number;
  myHighestBidAt: string;
  myProxyMaxAmount: number | null;
  myBidStatus: MyBidStatus;
}
