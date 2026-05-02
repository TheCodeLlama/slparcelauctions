import { describe, expect, it } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import type { ParcelDto } from "@/types/parcel";
import {
  ListingPreviewCard,
  type ListingPreviewAuction,
} from "./ListingPreviewCard";

const parcel: ParcelDto = {
  id: 1,
  slParcelUuid: "00000000-0000-0000-0000-000000000001",
  ownerUuid: "aaaa1111-0000-0000-0000-000000000000",
  ownerType: "agent",
  regionName: "Heterocera",
  gridX: 1000,
  gridY: 1000,
  positionX: 128,
  positionY: 128,
  positionZ: 0,
  ownerName: null,
  parcelName: null,
  continentName: null,
  areaSqm: 1024,
  description: "Beachfront retreat",
  snapshotUrl: null,
  slurl: "secondlife://Heterocera/128/128/25",
  maturityRating: "GENERAL",
  verified: false,
  verifiedAt: null,
  lastChecked: null,
  createdAt: "2026-04-17T00:00:00Z",
};

function base(overrides: Partial<ListingPreviewAuction> = {}): ListingPreviewAuction {
  return {
    title: "Featured Parcel Listing",
    parcel,
    startingBid: 500,
    reservePrice: null,
    buyNowPrice: null,
    durationHours: 72,
    tags: [],
    photos: [],
    sellerDesc: null,
    ...overrides,
  };
}

describe("ListingPreviewCard", () => {
  it("renders parcel, starting bid, and duration", () => {
    renderWithProviders(<ListingPreviewCard auction={base()} />);
    expect(screen.getByText("Beachfront retreat")).toBeInTheDocument();
    expect(screen.getByText("L$500")).toBeInTheDocument();
    expect(screen.getByText("3 days")).toBeInTheDocument();
  });

  it("shows auction title as the primary headline", () => {
    renderWithProviders(
      <ListingPreviewCard auction={base({ title: "Bayside Cottage Lot" })} />,
    );
    expect(screen.getByRole("heading", { level: 3 })).toHaveTextContent(
      "Bayside Cottage Lot",
    );
  });

  it("renders the preview banner when isPreview is set", () => {
    renderWithProviders(<ListingPreviewCard auction={base()} isPreview />);
    expect(
      screen.getByText(/Preview — this is how your listing will appear/),
    ).toBeInTheDocument();
  });

  it("renders reserve label and buy-now price when set", () => {
    renderWithProviders(
      <ListingPreviewCard
        auction={{ ...base(), reservePrice: 800, buyNowPrice: 1500 }}
      />,
    );
    expect(screen.getByText("Reserve")).toBeInTheDocument();
    expect(screen.getByText("L$1500")).toBeInTheDocument();
  });

  it("renders tags and first photo when present", () => {
    const { container } = renderWithProviders(
      <ListingPreviewCard
        auction={{
          ...base(),
          tags: [
            {
              code: "beach",
              label: "Beach",
              category: "Terrain",
              description: null,
              sortOrder: 1,
            },
          ],
          photos: [
            {
              id: 1,
              url: "/api/v1/auctions/1/photos/1/bytes",
              contentType: "image/png",
              sizeBytes: 100,
              sortOrder: 0,
              uploadedAt: "2026-04-17T00:00:00Z",
            },
          ],
        }}
      />,
    );
    expect(screen.getByText("Beach")).toBeInTheDocument();
    // alt="" makes the image presentational, so getByRole("img") won't
    // find it — fall back to a DOM query for the src match.
    const cover = container.querySelector("img");
    expect(cover?.getAttribute("src")).toMatch(/photos\/1\/bytes$/);
  });
});
