import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import type { AuctionPhotoDto } from "@/types/auction";
import { AuctionHero } from "./AuctionHero";

function photo(
  id: number,
  overrides: Partial<AuctionPhotoDto> = {},
): AuctionPhotoDto {
  return {
    id,
    url: `https://cdn.example/${id}.jpg`,
    contentType: "image/jpeg",
    sizeBytes: 1024,
    sortOrder: id,
    uploadedAt: "2026-04-20T00:00:00Z",
    ...overrides,
  };
}

describe("AuctionHero", () => {
  it("renders the gradient placeholder when no photos and no snapshot", () => {
    renderWithProviders(
      <AuctionHero photos={[]} snapshotUrl={null} regionName="Heterocera" />,
    );
    const hero = screen.getByTestId("auction-hero");
    expect(hero).toHaveAttribute("data-variant", "placeholder");
    expect(hero).toHaveTextContent("Heterocera");
  });

  it("falls back to the parcel snapshot when photos are empty", () => {
    renderWithProviders(
      <AuctionHero
        photos={[]}
        snapshotUrl="https://cdn.example/snap.jpg"
        regionName="Heterocera"
      />,
    );
    const hero = screen.getByTestId("auction-hero");
    expect(hero).toHaveAttribute("data-variant", "snapshot");
    const img = hero.querySelector("img");
    expect(img).not.toBeNull();
    expect(img?.getAttribute("src")).toBe("https://cdn.example/snap.jpg");
  });

  it("renders a single-photo variant when exactly one photo is provided", () => {
    renderWithProviders(
      <AuctionHero photos={[photo(1)]} snapshotUrl={null} />,
    );
    const hero = screen.getByTestId("auction-hero");
    expect(hero).toHaveAttribute("data-variant", "single");
    const img = screen.getByTestId("auction-hero-image");
    expect(img.getAttribute("src")).toBe("https://cdn.example/1.jpg");
  });

  it("renders the asymmetric gallery for 2 or more photos", () => {
    renderWithProviders(
      <AuctionHero photos={[photo(1), photo(2), photo(3)]} snapshotUrl={null} />,
    );
    const hero = screen.getByTestId("auction-hero");
    expect(hero).toHaveAttribute("data-variant", "gallery");
    expect(screen.getByTestId("auction-hero-image").getAttribute("src")).toBe(
      "https://cdn.example/1.jpg",
    );
    expect(screen.getByTestId("auction-hero-secondary-0")).toBeInTheDocument();
    expect(screen.getByTestId("auction-hero-secondary-1")).toBeInTheDocument();
  });

  it("sorts photos by sortOrder before selecting the hero cell", () => {
    renderWithProviders(
      <AuctionHero
        photos={[
          photo(30, { sortOrder: 2 }),
          photo(10, { sortOrder: 0 }),
          photo(20, { sortOrder: 1 }),
        ]}
        snapshotUrl={null}
      />,
    );
    // photo with sortOrder 0 should be the hero.
    expect(screen.getByTestId("auction-hero-image").getAttribute("src")).toBe(
      "https://cdn.example/10.jpg",
    );
  });

  it("shows a '+N more' overlay when more than 3 photos are provided", () => {
    renderWithProviders(
      <AuctionHero
        photos={[photo(1), photo(2), photo(3), photo(4), photo(5)]}
        snapshotUrl={null}
      />,
    );
    const overlay = screen.getByTestId("auction-hero-more-overlay");
    expect(overlay).toHaveTextContent("+2 more");
  });

  it("omits the 'more' overlay when photos.length <= 3", () => {
    renderWithProviders(
      <AuctionHero photos={[photo(1), photo(2), photo(3)]} snapshotUrl={null} />,
    );
    expect(screen.queryByTestId("auction-hero-more-overlay")).toBeNull();
  });

  it("includes a horizontal thumbnail strip on the mobile stack", () => {
    renderWithProviders(
      <AuctionHero photos={[photo(1), photo(2), photo(3)]} snapshotUrl={null} />,
    );
    const strip = screen.getByTestId("auction-hero-mobile-strip");
    expect(strip.className).toContain("overflow-x-auto");
    // 2 non-hero thumbnails.
    expect(strip.querySelectorAll("li").length).toBe(2);
  });
});
