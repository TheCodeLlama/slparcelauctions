// Closed shape: exactly the user-mutable groups. SYSTEM/REALTY_GROUP/MARKETING
// are excluded by server contract — sending them returns 400.
export type EditableGroup =
  | "bidding"
  | "auction_result"
  | "escrow"
  | "listing_status"
  | "reviews";

export interface PreferencesDto {
  slImMuted: boolean;
  slIm: Record<EditableGroup, boolean>;
}

export const EDITABLE_GROUPS: EditableGroup[] = [
  "bidding",
  "auction_result",
  "escrow",
  "listing_status",
  "reviews",
];

export const GROUP_LABELS: Record<EditableGroup, string> = {
  bidding: "Bidding",
  auction_result: "Auction Result",
  escrow: "Escrow",
  listing_status: "Listings",
  reviews: "Reviews",
};

export const GROUP_SUBTEXT: Record<EditableGroup, string> = {
  bidding: "Outbid, proxy exhausted",
  auction_result: "Won, lost, ended (sold/reserve/no-bids/buy-now)",
  escrow:
    "Funded, transfer confirmed, payout, expired, disputed, frozen, payout stalled",
  listing_status: "Verified, suspended, review required, cancelled by seller",
  reviews: "New review received",
};
