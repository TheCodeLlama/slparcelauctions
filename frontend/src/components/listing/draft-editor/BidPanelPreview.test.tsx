import { describe, expect, it, vi } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { BidPanelPreview } from "./BidPanelPreview";
import type { DraftSettings } from "./draftEditorMutations";

const settings: DraftSettings = {
  startingBid: 500,
  reservePrice: null,
  buyNowPrice: 5000,
  durationHours: 48,
  snipeProtect: false,
  snipeWindowMin: null,
};

describe("BidPanelPreview", () => {
  it("renders starting bid, buy-now, duration, and a disabled bid input", () => {
    renderWithProviders(
      <BidPanelPreview settings={settings} onSettingsChange={vi.fn()} />,
    );
    expect(screen.getByText("L$500")).toBeInTheDocument();
    expect(screen.getByText("L$5,000")).toBeInTheDocument();
    expect(screen.getByText("2 days")).toBeInTheDocument();
    expect(screen.getByText(/Listing not yet active/)).toBeInTheDocument();
    expect(screen.getByTestId("bid-panel-preview-input")).toBeDisabled();
  });

  it("does not render any sample/dummy bid data", () => {
    renderWithProviders(
      <BidPanelPreview settings={settings} onSettingsChange={vi.fn()} />,
    );
    expect(screen.queryByText(/Sample/i)).toBeNull();
    expect(screen.queryByText(/Current bid/i)).toBeNull();
    expect(screen.queryByText(/Bidders/i)).toBeNull();
  });

  it("Edit auction settings button opens the settings modal", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <BidPanelPreview settings={settings} onSettingsChange={vi.fn()} />,
    );
    await user.click(
      screen.getByTestId("bid-panel-preview-edit-settings"),
    );
    expect(await screen.findByRole("dialog")).toBeInTheDocument();
  });
});
