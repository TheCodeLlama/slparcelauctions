import { describe, it, expect, vi } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { type ReactNode } from "react";
import { ToastProvider } from "@/components/ui/Toast";
import { server } from "@/test/msw/server";
import { adminHandlers } from "@/test/msw/handlers";
import { useDismissReport } from "./useDismissReport";
import { adminQueryKeys } from "@/lib/admin/queryKeys";
import type { AdminReportDetail } from "@/lib/admin/types";

function makeReport(overrides: Partial<AdminReportDetail> = {}): AdminReportDetail {
  return {
    id: 5,
    reason: "OTHER",
    subject: "Test report",
    details: "Some details",
    status: "OPEN",
    adminNotes: null,
    createdAt: "2026-04-01T10:00:00Z",
    updatedAt: "2026-04-01T10:00:00Z",
    reviewedAt: null,
    reporterUserId: 42,
    reporterDisplayName: "Reporter",
    reporterDismissedReportsCount: 0,
    reviewedByDisplayName: null,
    ...overrides,
  };
}

function makeWrapper(qc: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={qc}>
        <ToastProvider>{children}</ToastProvider>
      </QueryClientProvider>
    );
  };
}

describe("useDismissReport", () => {
  it("invalidates both reports and stats query keys on success", async () => {
    const report = makeReport({ id: 5 });
    server.use(adminHandlers.dismissReportSuccess(report));

    const qc = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    const spy = vi.spyOn(qc, "invalidateQueries");

    const { result } = renderHook(() => useDismissReport(), {
      wrapper: makeWrapper(qc),
    });

    await act(async () => {
      await result.current.mutateAsync({ reportId: 5, notes: "legitimate" });
    });

    const callArgs = spy.mock.calls.map((c) => c[0]);
    const keys = callArgs.map((a) => (a as { queryKey?: unknown }).queryKey);

    expect(keys).toContainEqual(adminQueryKeys.reports());
    expect(keys).toContainEqual(adminQueryKeys.stats());
  });
});
