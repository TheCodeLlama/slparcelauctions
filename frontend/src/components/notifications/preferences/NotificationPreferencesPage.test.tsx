import { screen, fireEvent, waitFor, renderWithProviders } from "@/test/render";
import { describe, expect, test, beforeEach } from "vitest";
import { NotificationPreferencesPage } from "./NotificationPreferencesPage";
import { server } from "@/test/msw/server";
import {
  preferencesHandlers,
  resetPreferences,
  seedPreferences,
} from "@/test/msw/handlers";

describe("NotificationPreferencesPage", () => {
  beforeEach(() => {
    resetPreferences();
    server.use(...preferencesHandlers);
  });

  test("renders banner and 5 group toggle rows (no SYSTEM/REALTY_GROUP/MARKETING)", async () => {
    renderWithProviders(<NotificationPreferencesPage />);

    await waitFor(() => {
      expect(screen.getByText(/In-app and system notifications always deliver/i))
        .toBeInTheDocument();
    });

    expect(screen.getByText("Bidding")).toBeInTheDocument();
    expect(screen.getByText("Auction Result")).toBeInTheDocument();
    expect(screen.getByText("Escrow")).toBeInTheDocument();
    expect(screen.getByText("Listings")).toBeInTheDocument();
    expect(screen.getByText("Reviews")).toBeInTheDocument();
    // None of these should appear:
    expect(screen.queryByText("System")).not.toBeInTheDocument();
    expect(screen.queryByText("Realty Group")).not.toBeInTheDocument();
    expect(screen.queryByText("Marketing")).not.toBeInTheDocument();
  });

  test("Reviews defaults to OFF for fresh user", async () => {
    renderWithProviders(<NotificationPreferencesPage />);

    await waitFor(() => {
      const reviewsToggle = screen.getByTestId("group-toggle-reviews");
      expect(reviewsToggle).toHaveAttribute("aria-checked", "false");
    });
  });

  test("master mute disables group toggles but preserves underlying values", async () => {
    seedPreferences({
      slImMuted: false,
      slIm: { bidding: true, auction_result: true, escrow: true,
              listing_status: true, reviews: false },
    });
    renderWithProviders(<NotificationPreferencesPage />);

    // Wait for initial render
    await waitFor(() => {
      expect(screen.getByTestId("group-toggle-bidding"))
        .toHaveAttribute("aria-checked", "true");
    });

    // Find the master mute switch by its aria-label and click
    const masterMute = screen.getByLabelText("Mute all SL IM notifications");
    fireEvent.click(masterMute);

    await waitFor(() => {
      expect(masterMute).toHaveAttribute("aria-checked", "true");
    });

    // Group toggles should now be disabled but values unchanged
    const biddingToggle = screen.getByTestId("group-toggle-bidding");
    expect(biddingToggle).toBeDisabled();
    expect(biddingToggle).toHaveAttribute("aria-checked", "true");

    const reviewsToggle = screen.getByTestId("group-toggle-reviews");
    expect(reviewsToggle).toBeDisabled();
    expect(reviewsToggle).toHaveAttribute("aria-checked", "false");

    // Click bidding toggle while muted — should be no-op
    fireEvent.click(biddingToggle);
    expect(biddingToggle).toHaveAttribute("aria-checked", "true");

    // Toggle master mute off
    fireEvent.click(masterMute);
    await waitFor(() => {
      expect(masterMute).toHaveAttribute("aria-checked", "false");
    });

    // Group toggles re-enabled, values preserved
    expect(biddingToggle).not.toBeDisabled();
    expect(biddingToggle).toHaveAttribute("aria-checked", "true");
    expect(reviewsToggle).toHaveAttribute("aria-checked", "false");
  });

  test("clicking a group toggle when not muted updates the toggle state", async () => {
    seedPreferences({
      slImMuted: false,
      slIm: { bidding: true, auction_result: true, escrow: true,
              listing_status: true, reviews: false },
    });
    renderWithProviders(<NotificationPreferencesPage />);

    await waitFor(() => {
      expect(screen.getByTestId("group-toggle-bidding"))
        .toHaveAttribute("aria-checked", "true");
    });

    fireEvent.click(screen.getByTestId("group-toggle-bidding"));

    await waitFor(() => {
      expect(screen.getByTestId("group-toggle-bidding"))
        .toHaveAttribute("aria-checked", "false");
    });
  });
});
