import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { FeatureListingButton } from "./FeatureListingButton";

describe("FeatureListingButton", () => {
  it("shows 'Featured' chip when already featured", () => {
    render(<FeatureListingButton auctionPublicId="a1" priceLindens={500} alreadyFeatured />);
    expect(screen.getByText(/Featured/)).toBeInTheDocument();
  });

  it("shows the buy button with price when not yet featured", () => {
    render(<FeatureListingButton auctionPublicId="a1" priceLindens={500} alreadyFeatured={false} />);
    expect(screen.getByRole("button", { name: /Feature this listing for L\$500/ })).toBeInTheDocument();
  });
});
