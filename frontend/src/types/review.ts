// Wire-shape mirrors of the backend review DTOs. Kept intentionally flat and
// un-opinionated so the lib/api layer can JSON.parse straight into these
// interfaces without any custom mapping. The Java side (see
// {@code backend/.../review/dto/ReviewDto.java} and siblings) is the source of
// truth — if the backend adds a field, add it here too.

/**
 * Which side of the transaction is being reviewed. A SELLER review is the
 * buyer's feedback about the seller; a BUYER review is the seller's feedback
 * about the buyer. The role is embedded in every {@link ReviewDto} for cheap
 * filtering on the client without a second round-trip.
 */
export type ReviewedRole = "SELLER" | "BUYER";

/**
 * Five reason codes a flagger can pick from the {@code FlagModal} radio
 * group. {@code OTHER} mandates a non-empty elaboration server-side (see
 * {@code ElaborationRequiredWhenOtherValidator}); the UI enforces the same
 * rule before submit so the 422 path is the fallback, not the norm.
 */
export type ReviewFlagReason =
  | "SPAM"
  | "ABUSIVE"
  | "OFF_TOPIC"
  | "FALSE_INFO"
  | "OTHER";

/**
 * Reviewee's reply to a revealed review. One-shot: no editing, no deletion
 * (spec §15). Absent when the reviewee hasn't responded yet or was never
 * entitled (flag target, self-response, …).
 */
export interface ReviewResponseDto {
  publicId: string;
  text: string;
  createdAt: string;
}

/**
 * Shape of one review row. Every field that depends on visibility (text,
 * rating, submittedAt) is nullable — the backend returns {@code null} until
 * the row is revealed to the viewer (§8.1). The {@code pending} boolean is a
 * viewer-specific hint: {@code true} means the viewer is this review's
 * author and the counterparty hasn't submitted yet.
 */
export interface ReviewDto {
  publicId: string;
  auctionPublicId: string;
  auctionTitle: string;
  auctionPrimaryPhotoUrl: string | null;
  reviewerPublicId: string;
  reviewerDisplayName: string;
  reviewerAvatarUrl: string | null;
  revieweePublicId: string;
  reviewedRole: ReviewedRole;
  rating: number | null;
  text: string | null;
  visible: boolean;
  pending: boolean;
  submittedAt: string | null;
  revealedAt: string | null;
  response: ReviewResponseDto | null;
}

/**
 * Envelope returned by {@code GET /api/v1/auctions/{id}/reviews}. Collapses
 * the five {@code ReviewPanel} states into one round-trip: {@code reviews}
 * contains any revealed rows, {@code myPendingReview} is the viewer's own
 * hidden submission (if any), {@code canReview}/{@code windowClosesAt}
 * describe whether the form should render.
 */
export interface AuctionReviewsResponse {
  reviews: ReviewDto[];
  myPendingReview: ReviewDto | null;
  canReview: boolean;
  windowClosesAt: string | null;
}

/**
 * One entry in {@code GET /api/v1/users/me/pending-reviews}. Sorted most-
 * urgent-first by the backend, so the dashboard renders in order without
 * client-side sort (see backend commit {@code 2c7e125}).
 */
export interface PendingReviewDto {
  auctionPublicId: string;
  title: string;
  primaryPhotoUrl: string | null;
  counterpartyPublicId: string;
  counterpartyDisplayName: string;
  counterpartyAvatarUrl: string | null;
  escrowCompletedAt: string;
  windowClosesAt: string;
  hoursRemaining: number;
  viewerRole: ReviewedRole;
}

/**
 * POST body for {@code /api/v1/auctions/{id}/reviews}. {@code text} is
 * optional — rating-only reviews are allowed (spec §10).
 */
export interface ReviewSubmitRequest {
  rating: number;
  text?: string;
}

/**
 * POST body for {@code /api/v1/reviews/{id}/respond}. {@code text} is
 * required and non-empty; the service rejects blank strings.
 */
export interface ReviewResponseSubmitRequest {
  text: string;
}

/**
 * POST body for {@code /api/v1/reviews/{id}/flag}. {@code elaboration} is
 * required iff {@code reason === "OTHER"}.
 */
export interface ReviewFlagRequest {
  reason: ReviewFlagReason;
  elaboration?: string;
}
