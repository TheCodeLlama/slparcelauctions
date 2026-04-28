import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/test/render";
import { server } from "@/test/msw/server";
import { adminHandlers, adminOwnershipRecheckHandlers } from "@/test/msw/handlers";
import { FraudFlagSlideOver } from "./FraudFlagSlideOver";
import type { AdminFraudFlagDetail } from "@/lib/admin/types";

vi.mock("next/navigation", () => ({
  usePathname: vi.fn(() => "/admin/fraud-flags"),
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

const AUCTION_ID = 99;
const FLAG_ID = 5;

function makeDetail(overrides: Partial<AdminFraudFlagDetail> = {}): AdminFraudFlagDetail {
  return {
    id: FLAG_ID,
    reason: "BOT_PRICE_DRIFT",
    detectedAt: "2026-04-01T10:00:00Z",
    resolvedAt: null,
    resolvedByDisplayName: null,
    adminNotes: null,
    auction: {
      id: AUCTION_ID,
      title: "Test Parcel",
      status: "ACTIVE",
      endsAt: "2026-05-01T00:00:00Z",
      suspendedAt: null,
      sellerUserId: 1,
      sellerDisplayName: "SellerName",
    },
    evidenceJson: {},
    linkedUsers: {},
    siblingOpenFlagCount: 0,
    ...overrides,
  };
}

function renderSlideOver() {
  return renderWithProviders(
    <FraudFlagSlideOver
      flagId={FLAG_ID}
      hasPrev={false}
      hasNext={false}
      onPrev={vi.fn()}
      onNext={vi.fn()}
      onClose={vi.fn()}
    />
  );
}

describe("FraudFlagSlideOver — Re-check ownership", () => {
  beforeEach(() => {
    server.use(adminHandlers.fraudFlagDetailSuccess(makeDetail()));
  });

  it("renders the recheck button for an unresolved flag with an auction", async () => {
    renderSlideOver();

    await waitFor(() =>
      expect(screen.getByTestId("recheck-ownership-btn")).toBeInTheDocument()
    );
  });

  it("shows success toast when owner matches", async () => {
    server.use(
      adminOwnershipRecheckHandlers.matchSuccess(AUCTION_ID, "owner-uuid-1", "owner-uuid-1")
    );

    renderSlideOver();

    await waitFor(() =>
      expect(screen.getByTestId("recheck-ownership-btn")).toBeInTheDocument()
    );

    await userEvent.click(screen.getByTestId("recheck-ownership-btn"));

    await waitFor(() =>
      expect(screen.getByText(/Owner match/i)).toBeInTheDocument()
    );
  });

  it("shows error toast when mismatch causes suspension", async () => {
    server.use(
      adminOwnershipRecheckHandlers.mismatchSuspended(AUCTION_ID, "new-owner-uuid", "original-uuid")
    );

    renderSlideOver();

    await waitFor(() =>
      expect(screen.getByTestId("recheck-ownership-btn")).toBeInTheDocument()
    );

    await userEvent.click(screen.getByTestId("recheck-ownership-btn"));

    await waitFor(() =>
      expect(screen.getByText(/Owner mismatch detected. Auction suspended/i)).toBeInTheDocument()
    );
  });
});
