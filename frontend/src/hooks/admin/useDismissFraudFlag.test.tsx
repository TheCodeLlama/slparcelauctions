import { describe, it, expect, vi } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { type ReactNode } from "react";
import { ToastProvider } from "@/components/ui/Toast";
import { server } from "@/test/msw/server";
import { adminHandlers } from "@/test/msw/handlers";
import { useDismissFraudFlag } from "./useDismissFraudFlag";
import { useReinstateFraudFlag } from "./useReinstateFraudFlag";
import { adminQueryKeys } from "@/lib/admin/queryKeys";
import type { AdminFraudFlagDetail } from "@/lib/admin/types";

function makeDetail(overrides: Partial<AdminFraudFlagDetail> = {}): AdminFraudFlagDetail {
  return {
    id: 5,
    reason: "BOT_PRICE_DRIFT",
    detectedAt: "2026-04-01T10:00:00Z",
    resolvedAt: null,
    resolvedByDisplayName: null,
    adminNotes: null,
    auction: null,
    evidenceJson: {},
    linkedUsers: {},
    siblingOpenFlagCount: 0,
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

describe("useDismissFraudFlag", () => {
  it("invalidates both fraud-flags and stats query keys on success", async () => {
    const detail = makeDetail({ id: 5 });
    server.use(adminHandlers.dismissSuccess(detail));

    const qc = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    const spy = vi.spyOn(qc, "invalidateQueries");

    const { result } = renderHook(() => useDismissFraudFlag(), {
      wrapper: makeWrapper(qc),
    });

    await act(async () => {
      await result.current.mutateAsync({ flagId: 5, adminNotes: "test notes" });
    });

    const callArgs = spy.mock.calls.map((c) => c[0]);
    const keys = callArgs.map((a) => (a as { queryKey?: unknown }).queryKey);

    expect(keys).toContainEqual(adminQueryKeys.fraudFlags());
    expect(keys).toContainEqual(adminQueryKeys.stats());
  });
});

describe("useReinstateFraudFlag", () => {
  it("invalidates both fraud-flags and stats query keys on success", async () => {
    const detail = makeDetail({
      id: 6,
      auction: {
        id: 99,
        title: "Test Auction",
        status: "SUSPENDED",
        endsAt: "2026-05-01T00:00:00Z",
        suspendedAt: "2026-04-01T10:00:00Z",
        sellerUserId: 1,
        sellerDisplayName: "Seller",
      },
    });
    server.use(adminHandlers.reinstateSuccess(detail));

    const qc = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    const spy = vi.spyOn(qc, "invalidateQueries");

    const { result } = renderHook(() => useReinstateFraudFlag(), {
      wrapper: makeWrapper(qc),
    });

    await act(async () => {
      await result.current.mutateAsync({ flagId: 6, adminNotes: "reinstating" });
    });

    const callArgs = spy.mock.calls.map((c) => c[0]);
    const keys = callArgs.map((a) => (a as { queryKey?: unknown }).queryKey);

    expect(keys).toContainEqual(adminQueryKeys.fraudFlags());
    expect(keys).toContainEqual(adminQueryKeys.stats());
  });
});
