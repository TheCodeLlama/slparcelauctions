import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { userHandlers } from "@/test/msw/handlers";
import {
  mockPublicProfile,
  mockNewSellerPublicProfile,
  mockUnverifiedPublicProfile,
} from "@/test/msw/fixtures";
import { PublicProfileView } from "./PublicProfileView";

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
    server.use(userHandlers.publicProfileSuccess(mockPublicProfile));

    renderWithProviders(<PublicProfileView userId={42} />);

    expect(await screen.findByText("Verified Tester")).toBeInTheDocument();
    expect(screen.getByText("Verified")).toBeInTheDocument();
    expect(screen.getByText("TesterBot Resident")).toBeInTheDocument();
    expect(screen.getByText("Auction enthusiast")).toBeInTheDocument();
  });

  it("renders unverified user with Unverified chip and no SL identity", async () => {
    server.use(
      userHandlers.publicProfileSuccess(mockUnverifiedPublicProfile),
    );

    renderWithProviders(<PublicProfileView userId={44} />);

    expect(await screen.findByText("Unverified Tester")).toBeInTheDocument();
    expect(screen.getByText("Unverified")).toBeInTheDocument();
    expect(screen.queryByText("TesterBot Resident")).not.toBeInTheDocument();
  });

  it("calls notFound on 404", async () => {
    server.use(userHandlers.publicProfileNotFound());

    renderWithProviders(<PublicProfileView userId={999} />);

    await waitFor(() => {
      expect(mockNotFound).toHaveBeenCalled();
    });
  });

  it("shows NewSellerBadge when completedSales < 3", async () => {
    server.use(
      userHandlers.publicProfileSuccess(mockNewSellerPublicProfile),
    );

    renderWithProviders(<PublicProfileView userId={43} />);

    // Wait for the profile to load (displayName is also "New Seller")
    await screen.findByRole("heading", { level: 1, name: "New Seller" });
    // The StatusBadge "New Seller" appears separately from the heading
    const badges = screen.getAllByText("New Seller");
    expect(badges.length).toBeGreaterThanOrEqual(2);
  });

  it("hides NewSellerBadge for established seller", async () => {
    server.use(userHandlers.publicProfileSuccess(mockPublicProfile));

    renderWithProviders(<PublicProfileView userId={42} />);

    expect(await screen.findByText("Verified Tester")).toBeInTheDocument();
    expect(screen.queryByText("New Seller")).not.toBeInTheDocument();
  });
});
