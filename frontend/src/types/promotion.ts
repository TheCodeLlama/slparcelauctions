export type FeaturedBoardSource = "PROMO_01" | "ALGORITHMIC" | "PLACEHOLDER";

export interface FeaturedBoardListing {
  publicId: string;
  title: string;
  region: string | null;
  sqm: number | null;
  photoUrl: string | null;
  currentBid: number;
  endsAt: string;
  listingUrl: string;
  slurl: string | null;
}

export interface FeaturedBoardPayload {
  boardIndex: number;
  cycleSeconds: number;
  listings: FeaturedBoardListing[];
  source: FeaturedBoardSource;
}

export interface PurchaseFeaturedResponse {
  slotPublicId: string;
  boardIndex: number;
  position: number;
  priceLindens: number;
  newBalanceLindens: number;
}
