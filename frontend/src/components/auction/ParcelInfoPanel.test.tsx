import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import type { PublicAuctionResponse } from "@/types/auction";
import { ParcelInfoPanel } from "./ParcelInfoPanel";

function publicAuctionFixture(
  overrides: Partial<PublicAuctionResponse> = {},
): PublicAuctionResponse {
  return {
    id: 7,
    sellerId: 100,
    title: "Featured Parcel Listing",
    parcel: {
      id: 1,
      slParcelUuid: "00000000-0000-0000-0000-000000000001",
      ownerUuid: "aaaa1111-0000-0000-0000-000000000000",
      ownerType: "agent",
      regionName: "Heterocera",
      gridX: 0,
      gridY: 0,
      positionX: 128,
      positionY: 128,
      positionZ: 0,
      continentName: null,
      areaSqm: 1024,
      description: "Beachfront parcel",
      snapshotUrl: null,
      slurl: "secondlife://Heterocera/128/128/25",
      maturityRating: "GENERAL",
      verified: true,
      verifiedAt: "2026-04-20T00:00:00Z",
      lastChecked: "2026-04-20T00:00:00Z",
      createdAt: "2026-04-17T00:00:00Z",
    },
    status: "ACTIVE",
    verificationTier: "SCRIPT",
    startingBid: 500,
    hasReserve: false,
    reserveMet: true,
    buyNowPrice: null,
    currentBid: 1500,
    bidCount: 3,
    currentHighBid: 1500,
    bidderCount: 2,
    durationHours: 72,
    snipeProtect: true,
    snipeWindowMin: 10,
    startsAt: "2026-04-19T00:00:00Z",
    endsAt: "2026-04-22T00:00:00Z",
    originalEndsAt: "2026-04-22T00:00:00Z",
    sellerDesc: null,
    tags: [],
    photos: [],
    ...overrides,
  };
}

describe("ParcelInfoPanel", () => {
  it("prefers auction.title over parcel.description for the heading", () => {
    renderWithProviders(
      <ParcelInfoPanel
        auction={publicAuctionFixture({
          title: "Premium Waterfront",
          parcel: {
            ...publicAuctionFixture().parcel,
            description: "Lorem",
            regionName: "Tula",
          },
        })}
      />,
    );
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent(
      "Premium Waterfront",
    );
  });

  it("falls back to parcel.description when title is blank", () => {
    renderWithProviders(
      <ParcelInfoPanel auction={publicAuctionFixture({ title: "" })} />,
    );
    expect(screen.getByTestId("parcel-info-panel-title")).toHaveTextContent(
      "Beachfront parcel",
    );
  });

  it("falls back to the region name when title and description are blank", () => {
    renderWithProviders(
      <ParcelInfoPanel
        auction={publicAuctionFixture({
          title: "",
          parcel: {
            ...publicAuctionFixture().parcel,
            description: "",
          },
        })}
      />,
    );
    expect(screen.getByTestId("parcel-info-panel-title")).toHaveTextContent(
      "Heterocera",
    );
  });

  it("renders region + area + maturity rating in the subline", () => {
    renderWithProviders(
      <ParcelInfoPanel
        auction={publicAuctionFixture({
          parcel: {
            ...publicAuctionFixture().parcel,
            areaSqm: 4096,
            maturityRating: "MODERATE",
          },
        })}
      />,
    );
    const subline = screen.getByTestId("parcel-info-panel-subline");
    expect(subline).toHaveTextContent("Heterocera");
    expect(subline).toHaveTextContent("4,096 m²");
    const maturity = screen.getByTestId("parcel-info-panel-maturity");
    expect(maturity).toHaveAttribute("data-maturity", "MODERATE");
    expect(maturity).toHaveTextContent("Moderate");
  });

  it("renders the verification tier badge", () => {
    renderWithProviders(
      <ParcelInfoPanel
        auction={publicAuctionFixture({ verificationTier: "BOT" })}
      />,
    );
    expect(screen.getByTestId("verification-tier-badge")).toHaveTextContent(
      "Bot verified",
    );
  });

  it("renders the snipe protection badge only when snipeProtect is true", () => {
    renderWithProviders(
      <ParcelInfoPanel
        auction={publicAuctionFixture({
          snipeProtect: true,
          snipeWindowMin: 15,
        })}
      />,
    );
    expect(screen.getByTestId("snipe-protection-badge")).toHaveTextContent(
      "Snipe 15m",
    );
  });

  it("hides the snipe badge when snipeProtect is false", () => {
    renderWithProviders(
      <ParcelInfoPanel
        auction={publicAuctionFixture({
          snipeProtect: false,
          snipeWindowMin: null,
        })}
      />,
    );
    expect(screen.queryByTestId("snipe-protection-badge")).toBeNull();
  });

  it("renders tag chips when tags are present", () => {
    renderWithProviders(
      <ParcelInfoPanel
        auction={publicAuctionFixture({
          tags: [
            {
              code: "waterfront",
              label: "Waterfront",
              category: "feature",
              description: null,
              sortOrder: 0,
            },
            {
              code: "residential",
              label: "Residential",
              category: "use",
              description: null,
              sortOrder: 1,
            },
          ],
        })}
      />,
    );
    const tags = screen.getByTestId("parcel-info-panel-tags");
    expect(tags).toHaveTextContent("Waterfront");
    expect(tags).toHaveTextContent("Residential");
  });

  it("omits the tags section entirely when tags is empty", () => {
    renderWithProviders(
      <ParcelInfoPanel auction={publicAuctionFixture({ tags: [] })} />,
    );
    expect(screen.queryByTestId("parcel-info-panel-tags")).toBeNull();
  });

  it("renders the seller description when provided", () => {
    renderWithProviders(
      <ParcelInfoPanel
        auction={publicAuctionFixture({ sellerDesc: "Come visit!" })}
      />,
    );
    expect(
      screen.getByTestId("parcel-info-panel-description"),
    ).toHaveTextContent("Come visit!");
  });

  it("omits the seller description section when sellerDesc is null", () => {
    renderWithProviders(
      <ParcelInfoPanel auction={publicAuctionFixture({ sellerDesc: null })} />,
    );
    expect(screen.queryByTestId("parcel-info-panel-description")).toBeNull();
  });

  it("does not render any seller identity information", () => {
    // The seller card handles avatar/displayName/rating. Assert we don't
    // leak seller fields into the parcel panel.
    renderWithProviders(
      <ParcelInfoPanel
        auction={publicAuctionFixture({ sellerId: 12345 })}
      />,
    );
    expect(screen.queryByText(/12345/)).not.toBeInTheDocument();
    expect(screen.queryByRole("img")).not.toBeInTheDocument();
  });

  it("includes the VisitInSecondLifeButton", () => {
    renderWithProviders(
      <ParcelInfoPanel auction={publicAuctionFixture()} />,
    );
    expect(screen.getByText("Visit in Second Life")).toBeInTheDocument();
  });
});
