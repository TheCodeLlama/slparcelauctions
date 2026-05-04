import { describe, it, expect, vi } from "vitest";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { adminHandlers } from "@/test/msw/handlers";
import { ReportListingButton } from "./ReportListingButton";
import type { AuthUser } from "@/lib/auth/session";

const AUCTION_ID = "00000000-0000-0000-0000-000000000063";
const SELLER_ID = "00000000-0000-0000-0000-000000000007";

// Verified user — different from seller
const verifiedUser: AuthUser = {
  publicId: "00000000-0000-0000-0000-00000000002a",
  email: "user@example.com",
  displayName: "Buyer",
  slAvatarUuid: "some-uuid",
  verified: true,
  role: "USER",
};

// Unverified user — different from seller
const unverifiedUser: AuthUser = {
  ...verifiedUser,
  verified: false,
};

// Seller themselves
const sellerUser: AuthUser = {
  ...verifiedUser,
  publicId: SELLER_ID,
};

vi.mock("next/navigation", () => ({
  usePathname: vi.fn(() => "/auction/99"),
  useRouter: () => ({
    push: vi.fn(),
    replace: vi.fn(),
    refresh: vi.fn(),
    back: vi.fn(),
    forward: vi.fn(),
    prefetch: vi.fn(),
  }),
  useSearchParams: vi.fn(() => new URLSearchParams()),
}));

describe("ReportListingButton state machine", () => {
  it("renders nothing for anonymous user", () => {
    renderWithProviders(
      <ReportListingButton auctionPublicId={AUCTION_ID} sellerPublicId={SELLER_ID} />,
      { auth: "anonymous" }
    );
    expect(screen.queryByTestId("report-listing-btn")).not.toBeInTheDocument();
  });

  it("renders nothing for seller viewing own listing", () => {
    server.use(adminHandlers.myReport204(AUCTION_ID));
    renderWithProviders(
      <ReportListingButton auctionPublicId={AUCTION_ID} sellerPublicId={SELLER_ID} />,
      { auth: "authenticated", authUser: sellerUser }
    );
    expect(screen.queryByTestId("report-listing-btn")).not.toBeInTheDocument();
  });

  it("renders disabled button with tooltip for unverified user", async () => {
    server.use(adminHandlers.myReport204(AUCTION_ID));
    renderWithProviders(
      <ReportListingButton auctionPublicId={AUCTION_ID} sellerPublicId={SELLER_ID} />,
      { auth: "authenticated", authUser: unverifiedUser }
    );
    await waitFor(() =>
      expect(screen.getByTestId("report-listing-btn")).toBeInTheDocument()
    );
    const btn = screen.getByTestId("report-listing-btn");
    expect(btn).toBeDisabled();
    expect(btn.getAttribute("title")).toMatch(/Verify your SL avatar/);
  });

  it("renders enabled 'Report' button when no existing report (204)", async () => {
    server.use(adminHandlers.myReport204(AUCTION_ID));
    renderWithProviders(
      <ReportListingButton auctionPublicId={AUCTION_ID} sellerPublicId={SELLER_ID} />,
      { auth: "authenticated", authUser: verifiedUser }
    );
    await waitFor(() =>
      expect(screen.getByTestId("report-listing-btn")).toBeInTheDocument()
    );
    const btn = screen.getByTestId("report-listing-btn");
    expect(btn).not.toBeDisabled();
    expect(btn).toHaveTextContent("Report");
  });

  it("renders enabled 'Report' button when existing report is DISMISSED", async () => {
    server.use(
      adminHandlers.myReportSuccess(AUCTION_ID, {
        id: 1,
        subject: "test",
        reason: "OTHER",
        details: "details",
        status: "DISMISSED",
        createdAt: "2026-04-01T00:00:00Z",
        updatedAt: "2026-04-01T00:00:00Z",
      })
    );
    renderWithProviders(
      <ReportListingButton auctionPublicId={AUCTION_ID} sellerPublicId={SELLER_ID} />,
      { auth: "authenticated", authUser: verifiedUser }
    );
    await waitFor(() =>
      expect(screen.getByTestId("report-listing-btn")).toBeInTheDocument()
    );
    const btn = screen.getByTestId("report-listing-btn");
    expect(btn).not.toBeDisabled();
    expect(btn).toHaveTextContent("Report");
  });

  it("renders disabled 'Reported ✓' button when existing OPEN report", async () => {
    server.use(
      adminHandlers.myReportSuccess(AUCTION_ID, {
        id: 1,
        subject: "test",
        reason: "SHILL_BIDDING",
        details: "details",
        status: "OPEN",
        createdAt: "2026-04-01T00:00:00Z",
        updatedAt: "2026-04-01T00:00:00Z",
      })
    );
    renderWithProviders(
      <ReportListingButton auctionPublicId={AUCTION_ID} sellerPublicId={SELLER_ID} />,
      { auth: "authenticated", authUser: verifiedUser }
    );
    await waitFor(() => {
      const btn = screen.getByTestId("report-listing-btn");
      expect(btn).toHaveTextContent("Reported ✓");
    });
    expect(screen.getByTestId("report-listing-btn")).toBeDisabled();
  });

  it("renders disabled 'Reported ✓' button when existing REVIEWED report", async () => {
    server.use(
      adminHandlers.myReportSuccess(AUCTION_ID, {
        id: 1,
        subject: "test",
        reason: "OTHER",
        details: "details",
        status: "REVIEWED",
        createdAt: "2026-04-01T00:00:00Z",
        updatedAt: "2026-04-01T00:00:00Z",
      })
    );
    renderWithProviders(
      <ReportListingButton auctionPublicId={AUCTION_ID} sellerPublicId={SELLER_ID} />,
      { auth: "authenticated", authUser: verifiedUser }
    );
    await waitFor(() => {
      const btn = screen.getByTestId("report-listing-btn");
      expect(btn).toHaveTextContent("Reported ✓");
    });
    expect(screen.getByTestId("report-listing-btn")).toBeDisabled();
  });
});
