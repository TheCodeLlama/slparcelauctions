import { api } from "@/lib/api";

/**
 * Mirrors backend ListingFeeConfigResponse (public, unauthenticated).
 * Exposed at GET /api/v1/config/listing-fee. The amount is sourced from
 * slpa.listing-fee.amount-lindens — changing it requires an application
 * restart.
 */
export interface ListingFeeConfig {
  amountLindens: number;
}

export function getListingFeeConfig(): Promise<ListingFeeConfig> {
  return api.get<ListingFeeConfig>("/api/v1/config/listing-fee");
}
