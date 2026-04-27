import { describe, it, expect, vi, beforeEach } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { adminHandlers } from "@/test/msw/handlers";
import { FraudFlagsListPage } from "./FraudFlagsListPage";
import type { AdminFraudFlagSummary } from "@/lib/admin/types";

const mockReplace = vi.fn();

vi.mock("next/navigation", () => ({
  usePathname: vi.fn(() => "/admin/fraud-flags"),
  useRouter: () => ({
    push: vi.fn(),
    replace: mockReplace,
    refresh: vi.fn(),
    back: vi.fn(),
    forward: vi.fn(),
    prefetch: vi.fn(),
  }),
  useSearchParams: vi.fn(() => new URLSearchParams()),
}));

function makeRow(overrides: Partial<AdminFraudFlagSummary> = {}): AdminFraudFlagSummary {
  return {
    id: 1,
    reason: "BOT_PRICE_DRIFT",
    detectedAt: "2026-04-01T10:00:00Z",
    auctionId: 42,
    auctionTitle: "Test Parcel Auction",
    auctionStatus: "SUSPENDED",
    parcelRegionName: "Heterocera",
    parcelLocalId: 123,
    resolved: false,
    resolvedAt: null,
    resolvedByDisplayName: null,
    ...overrides,
  };
}

describe("FraudFlagsListPage", () => {
  beforeEach(() => {
    mockReplace.mockReset();
  });

  it("renders rows from MSW seed", async () => {
    server.use(
      adminHandlers.fraudFlagsListSuccess([
        makeRow({ id: 1, auctionTitle: "Parcel Alpha" }),
        makeRow({ id: 2, auctionTitle: "Parcel Beta", reason: "ESCROW_WRONG_PAYER" }),
      ]),
    );

    renderWithProviders(<FraudFlagsListPage />);

    await waitFor(() => expect(screen.getByTestId("fraud-flag-table")).toBeInTheDocument());
    expect(screen.getByText("Parcel Alpha")).toBeInTheDocument();
    expect(screen.getByText("Parcel Beta")).toBeInTheDocument();
  });

  it("shows empty state when no rows returned", async () => {
    server.use(adminHandlers.fraudFlagsListSuccess([]));

    renderWithProviders(<FraudFlagsListPage />);

    await waitFor(() => expect(screen.getByTestId("empty-state")).toBeInTheDocument());
    expect(screen.getByText(/No fraud flags match/)).toBeInTheDocument();
  });

  it("clicking a row updates URL with flagId", async () => {
    server.use(
      adminHandlers.fraudFlagsListSuccess([
        makeRow({ id: 7, auctionTitle: "Click Me" }),
      ]),
    );

    const { container: _c } = renderWithProviders(<FraudFlagsListPage />);

    await waitFor(() => expect(screen.getByTestId("flag-row-7")).toBeInTheDocument());

    screen.getByTestId("flag-row-7").click();

    expect(mockReplace).toHaveBeenCalledWith(
      expect.stringContaining("flagId=7"),
      expect.anything(),
    );
  });
});
