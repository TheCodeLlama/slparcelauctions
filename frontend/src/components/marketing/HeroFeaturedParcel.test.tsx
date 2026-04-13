// frontend/src/components/marketing/HeroFeaturedParcel.test.tsx

import { describe, it, expect } from "vitest";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { HeroFeaturedParcel } from "./HeroFeaturedParcel";

describe("HeroFeaturedParcel", () => {
  it("renders the placeholder caption", async () => {
    renderWithProviders(<HeroFeaturedParcel />);
    expect(screen.getByText("Featured Parcel Preview")).toBeInTheDocument();
    expect(screen.getByText("Live auctions coming soon")).toBeInTheDocument();
  });

  it("renders the light hero image by default (theme=light)", async () => {
    renderWithProviders(<HeroFeaturedParcel />, { theme: "light", forceTheme: true });
    await waitFor(() => {
      const img = screen.getByAltText("Featured Parcel Preview") as HTMLImageElement;
      expect(img.src).toContain("hero-parcel-light.png");
    });
  });

  it("renders the dark hero image when theme=dark", async () => {
    renderWithProviders(<HeroFeaturedParcel />, { theme: "dark", forceTheme: true });
    await waitFor(() => {
      const img = screen.getByAltText("Featured Parcel Preview") as HTMLImageElement;
      expect(img.src).toContain("hero-parcel-dark.png");
    });
  });
});
