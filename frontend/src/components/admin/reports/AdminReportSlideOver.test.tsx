import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderWithProviders, screen, waitFor, userEvent } from "@/test/render";
import { server } from "@/test/msw/server";
import { adminHandlers } from "@/test/msw/handlers";
import { AdminReportSlideOver } from "./AdminReportSlideOver";
import type { AdminReportListingRow, AdminReportDetail } from "@/lib/admin/types";

const mockPush = vi.fn();

vi.mock("next/navigation", () => ({
  usePathname: vi.fn(() => "/admin/reports"),
  useRouter: () => ({
    push: mockPush,
    replace: vi.fn(),
    refresh: vi.fn(),
    back: vi.fn(),
    forward: vi.fn(),
    prefetch: vi.fn(),
  }),
  useSearchParams: vi.fn(() => new URLSearchParams()),
}));

const listingRow: AdminReportListingRow = {
  auctionId: 10,
  auctionTitle: "Test Parcel Auction",
  auctionStatus: "ACTIVE",
  parcelRegionName: "Heterocera",
  sellerUserId: 7,
  sellerDisplayName: "Seller One",
  openReportCount: 1,
  latestReportAt: "2026-04-01T10:00:00Z",
};

const mockReport: AdminReportDetail = {
  id: 1,
  reason: "SHILL_BIDDING",
  subject: "Suspicious bidding",
  details: "The same alt account keeps bidding.",
  status: "OPEN",
  adminNotes: null,
  createdAt: "2026-04-01T10:00:00Z",
  updatedAt: "2026-04-01T10:00:00Z",
  reviewedAt: null,
  reporterUserId: 42,
  reporterDisplayName: "ReporterUser",
  reporterDismissedReportsCount: 0,
  reviewedByDisplayName: null,
};

describe("AdminReportSlideOver", () => {
  beforeEach(() => {
    mockPush.mockReset();
  });

  it("renders nothing when auctionId is null", () => {
    renderWithProviders(
      <AdminReportSlideOver
        auctionId={null}
        listingRow={null}
        hasPrev={false}
        hasNext={false}
        onPrev={vi.fn()}
        onNext={vi.fn()}
        onClose={vi.fn()}
      />
    );
    expect(screen.queryByTestId("admin-report-slideover")).not.toBeInTheDocument();
  });

  it("renders slide-over with listing info and reports", async () => {
    server.use(
      adminHandlers.reportsByListingSuccess(10, [mockReport])
    );

    renderWithProviders(
      <AdminReportSlideOver
        auctionId={10}
        listingRow={listingRow}
        hasPrev={false}
        hasNext={false}
        onPrev={vi.fn()}
        onNext={vi.fn()}
        onClose={vi.fn()}
      />
    );

    await waitFor(() =>
      expect(screen.getByTestId("admin-report-slideover")).toBeInTheDocument()
    );
    expect(screen.getByText("Test Parcel Auction")).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.getByTestId("report-card-1")).toBeInTheDocument()
    );
  });

  it("closes on ESC", async () => {
    server.use(adminHandlers.reportsByListingSuccess(10, []));
    const onClose = vi.fn();
    renderWithProviders(
      <AdminReportSlideOver
        auctionId={10}
        listingRow={listingRow}
        hasPrev={false}
        hasNext={false}
        onPrev={vi.fn()}
        onNext={vi.fn()}
        onClose={onClose}
      />
    );
    const user = userEvent.setup();
    await user.keyboard("{Escape}");
    expect(onClose).toHaveBeenCalled();
  });

  it("Ban seller button navigates to /admin/users/{sellerId}", async () => {
    server.use(adminHandlers.reportsByListingSuccess(10, []));
    renderWithProviders(
      <AdminReportSlideOver
        auctionId={10}
        listingRow={listingRow}
        hasPrev={false}
        hasNext={false}
        onPrev={vi.fn()}
        onNext={vi.fn()}
        onClose={vi.fn()}
      />
    );
    await waitFor(() =>
      expect(screen.getByTestId("ban-seller-btn")).toBeInTheDocument()
    );
    const user = userEvent.setup();
    await user.click(screen.getByTestId("ban-seller-btn"));
    expect(mockPush).toHaveBeenCalledWith("/admin/users/7");
  });
});
