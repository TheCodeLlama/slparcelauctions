import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";

vi.mock("next/navigation", () => ({
  useRouter: () => ({
    push: vi.fn(),
    replace: vi.fn(),
    refresh: vi.fn(),
    back: vi.fn(),
    forward: vi.fn(),
    prefetch: vi.fn(),
  }),
  usePathname: () => "/admin/groups/reports/cccccccc-cccc-cccc-cccc-cccccccccccc",
  useSearchParams: () => new URLSearchParams(),
}));

const detailMock = vi.fn(
  ({ reportPublicId }: { reportPublicId: string }) => (
    <div data-testid="admin-group-report-detail-stub">{reportPublicId}</div>
  ),
);

vi.mock(
  "@/components/admin/realty-groups/AdminGroupReportDetailPage",
  () => ({
    AdminGroupReportDetailPage: (props: { reportPublicId: string }) =>
      detailMock(props),
  }),
);

import AdminGroupReportDetailRoute from "./page";

function wrap(node: ReactNode) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={qc}>{node}</QueryClientProvider>);
}

const VALID_ID = "cccccccc-cccc-cccc-cccc-cccccccccccc";

describe("/admin/groups/reports/[publicId]", () => {
  it("forwards the report publicId param to AdminGroupReportDetailPage", async () => {
    const ui = await AdminGroupReportDetailRoute({
      params: Promise.resolve({ publicId: VALID_ID }),
    });
    wrap(ui);
    expect(
      screen.getByTestId("admin-group-report-detail-stub"),
    ).toHaveTextContent(VALID_ID);
    expect(detailMock).toHaveBeenCalledWith({ reportPublicId: VALID_ID });
  });

  it("renders an invalid-id message when the publicId is not a UUID", async () => {
    const ui = await AdminGroupReportDetailRoute({
      params: Promise.resolve({ publicId: "not-a-uuid" }),
    });
    wrap(ui);
    expect(screen.getByText(/invalid report id/i)).toBeInTheDocument();
  });
});
