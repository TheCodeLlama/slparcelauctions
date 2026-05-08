import { describe, expect, it } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { BidPanelPreview } from "./BidPanelPreview";
import { sampleCurrentBid, sampleBidderCount } from "./SampleBidHistory";

describe("BidPanelPreview", () => {
  it("renders sample current bid + sample bidder count + Sample pill + disabled input", () => {
    renderWithProviders(
      <BidPanelPreview
        startingBid={500}
        buyNowPrice={5000}
        reservePrice={null}
        durationHours={48}
      />,
    );
    expect(screen.getByTestId("bid-panel-preview-sample-pill")).toBeInTheDocument();
    const expectedBid = `L$${sampleCurrentBid().toLocaleString()}`;
    expect(screen.getByText(expectedBid)).toBeInTheDocument();
    expect(screen.getByText(String(sampleBidderCount()))).toBeInTheDocument();
    expect(screen.getByText(/Runs for 48h when activated/)).toBeInTheDocument();
    const input = screen.getByTestId("bid-panel-preview-input");
    expect(input).toBeDisabled();
  });
});
