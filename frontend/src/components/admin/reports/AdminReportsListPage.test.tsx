import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { adminHandlers } from "@/test/msw/handlers";
import { AdminReportsListPage } from "./AdminReportsListPage";
import type { AdminReportListingRow } from "@/lib/admin/types";

const mockReplace = vi.fn();

vi.mock("next/navigation", () => ({
  usePathname: vi.fn(() => "/admin/reports"),
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

function makeRow(overrides: Partial<AdminReportListingRow> = {}): AdminReportListingRow {
  return {
    auctionId: 10,
    auctionTitle: "Test Parcel Auction",
    auctionStatus: "ACTIVE",
    parcelRegionName: "Heterocera",
    sellerUserId: 7,
    sellerDisplayName: "Seller One",
    openReportCount: 2,
    latestReportAt: "2026-04-01T10:00:00Z",
    ...overrides,
  };
}

describe("AdminReportsListPage", () => {
  beforeEach(() => {
    mockReplace.mockReset();
  });

  it("renders rows from MSW seed", async () => {
    server.use(
      adminHandlers.reportsListSuccess([
        makeRow({ auctionId: 10, auctionTitle: "Parcel Alpha" }),
        makeRow({ auctionId: 11, auctionTitle: "Parcel Beta" }),
      ])
    );

    renderWithProviders(<AdminReportsListPage />);

    await waitFor(() =>
      expect(screen.getByTestId("reports-table")).toBeInTheDocument()
    );
    expect(screen.getByText("Parcel Alpha")).toBeInTheDocument();
    expect(screen.getByText("Parcel Beta")).toBeInTheDocument();
  });

  it("shows empty state when no rows returned", async () => {
    server.use(adminHandlers.reportsListSuccess([]));

    renderWithProviders(<AdminReportsListPage />);

    await waitFor(() =>
      expect(screen.getByTestId("empty-state")).toBeInTheDocument()
    );
    expect(screen.getByText(/No reports match/)).toBeInTheDocument();
  });

  it("clicking a row updates URL with auctionId", async () => {
    server.use(
      adminHandlers.reportsListSuccess([
        makeRow({ auctionId: 42, auctionTitle: "Click Me" }),
      ])
    );

    renderWithProviders(<AdminReportsListPage />);

    await waitFor(() =>
      expect(screen.getByTestId("report-row-42")).toBeInTheDocument()
    );

    screen.getByTestId("report-row-42").click();

    expect(mockReplace).toHaveBeenCalledWith(
      expect.stringContaining("auctionId=42"),
      expect.anything()
    );
  });
});
