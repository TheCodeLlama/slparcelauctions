import { describe, it, expect, vi, beforeEach } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { userHandlers } from "@/test/msw/handlers";
import {
  mockPublicProfile,
  mockNewSellerPublicProfile,
  mockUnverifiedPublicProfile,
} from "@/test/msw/fixtures";
import { PublicProfileView } from "./PublicProfileView";

/**
 * The new {@link ProfileReviewTabs} fires a user-reviews request as soon
 * as the profile resolves — MSW is configured with
 * {@code onUnhandledRequest: "error"}, so every test needs a catch-all
 * reviews handler that returns an empty page. Tests that exercise the
 * reviews surface override this.
 */
function mockEmptyUserReviews() {
  return http.get("*/api/v1/users/:id/reviews", () =>
    HttpResponse.json({
      content: [],
      totalElements: 0,
      totalPages: 0,
      number: 0,
      size: 10,
    }),
  );
}

const mockNotFound = vi.fn();
vi.mock("next/navigation", () => ({
  notFound: (...args: unknown[]) => mockNotFound(...args),
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: vi.fn(() => "/users/42"),
  useSearchParams: () => new URLSearchParams(),
}));

describe("PublicProfileView", () => {
  beforeEach(() => {
    mockNotFound.mockReset();
  });

  it("renders verified user with badge and SL identity", async () => {
    server.use(
      userHandlers.publicProfileSuccess(mockPublicProfile),
      mockEmptyUserReviews(),
      userHandlers.realtyGroupsEmpty(),
    );

    renderWithProviders(<PublicProfileView userPublicId="00000000-0000-0000-0000-00000000002a" />);

    expect(await screen.findByText("Verified Tester")).toBeInTheDocument();
    expect(screen.getByText("Verified")).toBeInTheDocument();
    expect(screen.getByText("TesterBot Resident")).toBeInTheDocument();
    expect(screen.getByText("Auction enthusiast")).toBeInTheDocument();
  });

  it("renders unverified user with Unverified chip and no SL identity", async () => {
    server.use(
      userHandlers.publicProfileSuccess(mockUnverifiedPublicProfile),
      mockEmptyUserReviews(),
      userHandlers.realtyGroupsEmpty(),
    );

    renderWithProviders(<PublicProfileView userPublicId="00000000-0000-0000-0000-00000000002c" />);

    expect(await screen.findByText("Unverified Tester")).toBeInTheDocument();
    expect(screen.getByText("Unverified")).toBeInTheDocument();
    expect(screen.queryByText("TesterBot Resident")).not.toBeInTheDocument();
  });

  it("calls notFound on 404", async () => {
    server.use(userHandlers.publicProfileNotFound());

    renderWithProviders(<PublicProfileView userPublicId="00000000-0000-0000-0000-000000000999" />);

    await waitFor(() => {
      expect(mockNotFound).toHaveBeenCalled();
    });
  });

  it("shows NewSellerBadge when completedSales < 3", async () => {
    server.use(
      userHandlers.publicProfileSuccess(mockNewSellerPublicProfile),
      mockEmptyUserReviews(),
      userHandlers.realtyGroupsEmpty(),
    );

    renderWithProviders(<PublicProfileView userPublicId="00000000-0000-0000-0000-00000000002b" />);

    // Wait for the profile to load (displayName is also "New Seller")
    await screen.findByRole("heading", { level: 1, name: "New Seller" });
    // The StatusBadge "New Seller" appears separately from the heading
    const badges = screen.getAllByText("New Seller");
    expect(badges.length).toBeGreaterThanOrEqual(2);
  });

  it("hides NewSellerBadge for established seller", async () => {
    server.use(
      userHandlers.publicProfileSuccess(mockPublicProfile),
      mockEmptyUserReviews(),
      userHandlers.realtyGroupsEmpty(),
    );

    renderWithProviders(<PublicProfileView userPublicId="00000000-0000-0000-0000-00000000002a" />);

    expect(await screen.findByText("Verified Tester")).toBeInTheDocument();
    expect(screen.queryByText("New Seller")).not.toBeInTheDocument();
  });

  it("renders the Groups section when the user has affiliations", async () => {
    server.use(
      userHandlers.publicProfileSuccess(mockPublicProfile),
      mockEmptyUserReviews(),
      userHandlers.realtyGroupsSuccess([
        {
          groupPublicId: "10000000-0000-0000-0000-000000000001",
          groupName: "Mainland Realty",
          groupSlug: "mainland-realty",
          logoLightUrl: null, logoDarkUrl: null,
          role: "LEADER",
        },
      ]),
    );

    renderWithProviders(<PublicProfileView userPublicId="00000000-0000-0000-0000-00000000002a" />);

    expect(await screen.findByText("Verified Tester")).toBeInTheDocument();
    expect(await screen.findByText("Groups")).toBeInTheDocument();
    expect(await screen.findByText("Mainland Realty")).toBeInTheDocument();
    expect(screen.getByText("Leader")).toBeInTheDocument();
  });

  it("omits the Groups section when the user has no affiliations", async () => {
    server.use(
      userHandlers.publicProfileSuccess(mockPublicProfile),
      mockEmptyUserReviews(),
      userHandlers.realtyGroupsEmpty(),
    );

    renderWithProviders(<PublicProfileView userPublicId="00000000-0000-0000-0000-00000000002a" />);

    expect(await screen.findByText("Verified Tester")).toBeInTheDocument();
    expect(screen.queryByText("Groups")).not.toBeInTheDocument();
  });

  it("renders the ProfileReviewTabs instead of the legacy empty-state", async () => {
    server.use(
      userHandlers.publicProfileSuccess(mockPublicProfile),
      mockEmptyUserReviews(),
      userHandlers.realtyGroupsEmpty(),
    );

    renderWithProviders(<PublicProfileView userPublicId="00000000-0000-0000-0000-00000000002a" />);

    await screen.findByText("Verified Tester");
    expect(
      await screen.findByTestId("profile-review-tab-seller"),
    ).toBeInTheDocument();
    expect(screen.getByTestId("profile-review-tab-buyer")).toBeInTheDocument();
    // The legacy placeholder copy must not appear.
    expect(screen.queryByText("No reviews yet")).not.toBeInTheDocument();
    expect(
      screen.queryByText(
        /Reviews will appear here once this user completes transactions/,
      ),
    ).not.toBeInTheDocument();
  });
});
