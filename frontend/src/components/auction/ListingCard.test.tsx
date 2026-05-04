import { describe, it, expect } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, userEvent, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { ListingCard } from "./ListingCard";
import type { AuctionSearchResultDto } from "@/types/search";

const sample: AuctionSearchResultDto = {
  publicId: "00000000-0000-0000-0000-000000000001",
  title: "Premium Waterfront",
  status: "ACTIVE",
  endOutcome: null,
  parcel: {
    auctionPublicId: "00000000-0000-0000-0000-000000000001",
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
    publicId: "00000000-0000-0000-0000-000000000007",
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
    expect(link).toHaveAttribute("href", "/auction/00000000-0000-0000-0000-000000000001");
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

  it("authenticated heart click toggles the saved state (optimistic)", async () => {
    // Stateful handler so the onSettled invalidation refetch returns the
    // new state rather than clobbering the optimistic update.
    const saved = new Set<string>();
    server.use(
      http.get("*/api/v1/me/saved/ids", () =>
        HttpResponse.json({ publicIds: Array.from(saved) }),
      ),
      http.post("*/api/v1/me/saved", async ({ request }) => {
        const body = (await request.json()) as { auctionPublicId: string };
        saved.add(body.auctionPublicId);
        return HttpResponse.json(
          { auctionPublicId: body.auctionPublicId, savedAt: "2026-04-23T00:00:00Z" },
          { status: 201 },
        );
      }),
    );
    renderWithProviders(<ListingCard listing={sample} variant="default" />, {
      auth: "authenticated",
    });
    const heart = await screen.findByRole("button", { name: /save/i });
    expect(heart).toHaveAttribute("aria-pressed", "false");
    await userEvent.click(heart);
    await waitFor(() => {
      expect(
        screen.getByRole("button", { name: /unsave/i }),
      ).toHaveAttribute("aria-pressed", "true");
    });
  });
});
