import { describe, it, expect, vi } from "vitest";
import { renderWithProviders, screen, waitFor, userEvent } from "@/test/render";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { server } from "@/test/msw/server";
import { adminHandlers } from "@/test/msw/handlers";
import { ToastProvider } from "@/components/ui/Toast";
import { ReportListingModal } from "./ReportListingModal";
import { adminQueryKeys } from "@/lib/admin/queryKeys";

const AUCTION_ID = 99;
const onClose = vi.fn();

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

describe("ReportListingModal", () => {
  it("submit button is disabled when fields are empty", () => {
    renderWithProviders(
      <ReportListingModal auctionId={AUCTION_ID} onClose={onClose} />,
      { auth: "authenticated" }
    );
    expect(screen.getByTestId("report-submit-btn")).toBeDisabled();
  });

  it("closes on ESC", async () => {
    const closeFn = vi.fn();
    renderWithProviders(
      <ReportListingModal auctionId={AUCTION_ID} onClose={closeFn} />,
      { auth: "authenticated" }
    );
    const user = userEvent.setup();
    await user.keyboard("{Escape}");
    expect(closeFn).toHaveBeenCalled();
  });

  it("submits successfully → calls onClose and invalidates myReport cache", async () => {
    const closeFn = vi.fn();
    const qc = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    server.use(
      adminHandlers.submitReportSuccess(AUCTION_ID, {
        id: 1,
        subject: "Fake auction",
        reason: "SHILL_BIDDING",
        details: "Bidder is the seller's alt.",
        status: "OPEN",
        createdAt: "2026-04-01T00:00:00Z",
        updatedAt: "2026-04-01T00:00:00Z",
      })
    );

    const invalidateSpy = vi.spyOn(qc, "invalidateQueries");

    const { unmount } = renderWithProviders(
      <ReportListingModal auctionId={AUCTION_ID} onClose={closeFn} />,
      { auth: "authenticated" }
    );
    // Use the wrapper's qc by re-rendering inside our own wrapper
    unmount();

    const { getByTestId, getByRole } = renderWithProviders(
      <QueryClientProvider client={qc}>
        <ToastProvider>
          <ReportListingModal auctionId={AUCTION_ID} onClose={closeFn} />
        </ToastProvider>
      </QueryClientProvider>
    );

    const user = userEvent.setup();

    await user.type(getByTestId("report-subject"), "Fake auction");

    const select = getByTestId("report-reason");
    await user.selectOptions(select, "SHILL_BIDDING");

    await user.type(getByTestId("report-details"), "Bidder is the seller's alt.");

    const submitBtn = getByTestId("report-submit-btn");
    expect(submitBtn).not.toBeDisabled();

    await user.click(submitBtn);

    await waitFor(() => {
      expect(closeFn).toHaveBeenCalled();
    });

    const callKeys = invalidateSpy.mock.calls.map(
      (c) => (c[0] as { queryKey?: unknown }).queryKey
    );
    expect(callKeys).toContainEqual(adminQueryKeys.myReport(AUCTION_ID));

    void getByRole; // suppress unused-var lint
  });
});
