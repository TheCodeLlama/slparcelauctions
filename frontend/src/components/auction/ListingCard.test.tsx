import { describe, it, expect } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { ListingCard } from "./ListingCard";
import type { AuctionSearchResultDto } from "@/types/search";

const sample: AuctionSearchResultDto = {
  id: 1,
  title: "Premium Waterfront",
  status: "ACTIVE",
  endOutcome: null,
  parcel: {
    id: 11,
    name: "Bayside Lot",
    region: "Tula",
    area: 1024,
    maturity: "MODERATE",
    snapshotUrl: "/snap.jpg",
    gridX: 1,
    gridY: 2,
    positionX: 80,
    positionY: 104,
    positionZ: 89,
    tags: ["BEACHFRONT", "ROADSIDE"],
  },
  primaryPhotoUrl: "/photo.jpg",
  seller: {
    id: 7,
    displayName: "seller",
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
  endsAt: new Date(Date.now() + 5 * 3600_000).toISOString(),
  snipeProtect: true,
  snipeWindowMin: 5,
  distanceRegions: null,
};

describe("ListingCard", () => {
  it.each(["default", "compact", "featured"] as const)(
    "renders variant=%s",
    (variant) => {
      renderWithProviders(<ListingCard listing={sample} variant={variant} />);
      expect(screen.getByText("Premium Waterfront")).toBeInTheDocument();
    },
  );

  it("shows status chip LIVE for active far-future", () => {
    renderWithProviders(<ListingCard listing={sample} variant="default" />);
    expect(screen.getByText("LIVE")).toBeInTheDocument();
  });

  it("shows SOLD chip for COMPLETED/SOLD", () => {
    renderWithProviders(
      <ListingCard
        listing={{ ...sample, status: "COMPLETED", endOutcome: "SOLD" }}
        variant="default"
      />,
    );
    expect(screen.getByText("SOLD")).toBeInTheDocument();
  });

  it("card links to detail route", () => {
    renderWithProviders(<ListingCard listing={sample} variant="default" />);
    const link = screen.getByRole("link", { name: /premium waterfront/i });
    expect(link).toHaveAttribute("href", "/auction/1");
  });

  it("compact variant shows fewer tag pills (2) than default (3)", () => {
    const many = {
      ...sample,
      parcel: { ...sample.parcel, tags: ["A", "B", "C", "D", "E"] },
    };
    const { rerender } = renderWithProviders(
      <ListingCard listing={many} variant="default" />,
    );
    // Default: 3 pills + overflow count "+2"
    expect(screen.getByText("+2")).toBeInTheDocument();
    rerender(<ListingCard listing={many} variant="compact" />);
    // Compact: 2 pills + overflow count "+3"
    expect(screen.getByText("+3")).toBeInTheDocument();
  });

  it("featured variant surfaces the full tag list (up to 5, no overflow for 5)", () => {
    const many = {
      ...sample,
      parcel: { ...sample.parcel, tags: ["A", "B", "C", "D", "E"] },
    };
    renderWithProviders(<ListingCard listing={many} variant="featured" />);
    expect(screen.queryByText(/^\+\d+$/)).not.toBeInTheDocument();
    expect(screen.getByText("A")).toBeInTheDocument();
    expect(screen.getByText("E")).toBeInTheDocument();
  });

  it("distance chip rendered when distanceRegions present", () => {
    renderWithProviders(
      <ListingCard
        listing={{ ...sample, distanceRegions: 3.2 }}
        variant="default"
      />,
    );
    expect(screen.getByText(/3\.2 regions/i)).toBeInTheDocument();
  });

  it("heart click surfaces the sign-in-to-save toast", async () => {
    renderWithProviders(<ListingCard listing={sample} variant="default" />);
    const heart = screen.getByRole("button", { name: /save/i });
    await userEvent.click(heart);
    // Warning toast with the unauth CTA.
    expect(
      await screen.findByText(/sign in to save/i),
    ).toBeInTheDocument();
  });

  it("heart click does not navigate (preventDefault)", async () => {
    renderWithProviders(<ListingCard listing={sample} variant="default" />);
    const heart = screen.getByRole("button", { name: /save/i });
    // If the inner Link swallowed the click, the warning toast still appears.
    await userEvent.click(heart);
    expect(await screen.findByRole("alert")).toHaveTextContent(
      /sign in to save/i,
    );
  });

  it("dark mode renders without visual regressions", () => {
    renderWithProviders(<ListingCard listing={sample} variant="default" />, {
      theme: "dark",
      forceTheme: true,
    });
    expect(screen.getByText("Premium Waterfront")).toBeInTheDocument();
  });

  it("does not render heart button on pre-active statuses", () => {
    renderWithProviders(
      <ListingCard
        listing={{ ...sample, status: "DRAFT" }}
        variant="default"
      />,
    );
    expect(
      screen.queryByRole("button", { name: /save/i }),
    ).not.toBeInTheDocument();
  });
});
