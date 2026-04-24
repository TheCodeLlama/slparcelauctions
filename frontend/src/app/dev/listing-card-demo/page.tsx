import { Suspense } from "react";
import { ListingCard } from "@/components/auction/ListingCard";
import type { AuctionSearchResultDto } from "@/types/search";

/**
 * Dev-only showcase for the three {@code ListingCard} variants. Served at
 * /dev/listing-card-demo so designers and reviewers can eyeball the cards
 * in both themes without needing live backend data. Not linked from nav;
 * dropped in production via the existing {@code /dev/*} route convention.
 */
const sample: AuctionSearchResultDto = {
  id: 42,
  title: "Demo — Premium Waterfront",
  status: "ACTIVE",
  endOutcome: null,
  parcel: {
    id: 11,
    name: "Bayside Lot",
    region: "Tula",
    area: 1024,
    maturity: "MODERATE",
    snapshotUrl: null,
    gridX: 1,
    gridY: 2,
    positionX: 80,
    positionY: 104,
    positionZ: 89,
    tags: ["BEACHFRONT", "ROADSIDE", "ELEVATED", "PROTECTED"],
  },
  primaryPhotoUrl: null,
  seller: {
    id: 7,
    displayName: "seller.one",
    avatarUrl: null,
    averageRating: 4.8,
    reviewCount: 12,
  },
  verificationTier: "BOT",
  currentBid: 12500,
  startingBid: 5000,
  reservePrice: 10000,
  reserveMet: true,
  buyNowPrice: null,
  bidCount: 7,
  endsAt: new Date(Date.now() + 48 * 3600_000).toISOString(),
  snipeProtect: true,
  snipeWindowMin: 5,
  distanceRegions: 3.2,
};

export default function ListingCardDemo() {
  // ListingCard transitively reads useSearchParams via useSavedAuctions,
  // so the Next.js 16 prerender needs a Suspense boundary to bail out
  // cleanly. Fallback is invisible — the demo is client-rendered anyway.
  return (
    <Suspense fallback={null}>
      <div className="p-8 grid grid-cols-1 md:grid-cols-3 gap-6 max-w-6xl mx-auto">
        <section>
          <h2 className="text-title-lg font-bold mb-3">default</h2>
          <ListingCard listing={sample} variant="default" />
        </section>
        <section>
          <h2 className="text-title-lg font-bold mb-3">compact</h2>
          <ListingCard listing={sample} variant="compact" />
        </section>
        <section className="md:col-span-2">
          <h2 className="text-title-lg font-bold mb-3">featured</h2>
          <ListingCard listing={sample} variant="featured" />
        </section>
      </div>
    </Suspense>
  );
}
