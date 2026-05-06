import { describe, it, expect, vi } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { type ReactNode } from "react";
import { ToastProvider } from "@/components/ui/Toast";
import { http, HttpResponse } from "msw";
import { server } from "@/test/msw/server";
import { useReinstateAuction } from "./useReinstateAuction";
import { adminQueryKeys } from "@/lib/admin/queryKeys";

vi.mock("next/navigation", () => ({
  usePathname: vi.fn(() => "/admin/users/00000000-0000-0000-0000-000000000010"),
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

const USER_PID = "00000000-0000-0000-0000-000000000010";
const AUCTION_PID = "00000000-0000-0000-0000-000000000055";

function makeWrapper(qc: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={qc}>
        <ToastProvider>{children}</ToastProvider>
      </QueryClientProvider>
    );
  };
}

describe("useReinstateAuction", () => {
  it("invalidates user listings tab and stats on success", async () => {
    server.use(
      http.post(`*/api/v1/admin/auctions/${AUCTION_PID}/reinstate`, () =>
        HttpResponse.json({
          auctionId: 55,
          status: "ACTIVE",
          newEndsAt: "2026-05-01T00:00:00Z",
          suspensionDurationSeconds: 3600,
        })
      )
    );

    const qc = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    const spy = vi.spyOn(qc, "invalidateQueries");

    const { result } = renderHook(() => useReinstateAuction(USER_PID), {
      wrapper: makeWrapper(qc),
    });

    await act(async () => {
      await result.current.mutateAsync({ auctionPublicId: AUCTION_PID, notes: "reinstate notes" });
    });

    const keys = spy.mock.calls.map((c) => (c[0] as { queryKey?: unknown }).queryKey);
    expect(keys).toContainEqual(adminQueryKeys.userTab(USER_PID, "listings", { page: 0, size: 25 }));
    expect(keys).toContainEqual(adminQueryKeys.stats());
  });

  it("invalidates user listings on AUCTION_NOT_SUSPENDED 409", async () => {
    server.use(
      http.post(`*/api/v1/admin/auctions/${AUCTION_PID}/reinstate`, () =>
        HttpResponse.json(
          { code: "AUCTION_NOT_SUSPENDED", message: "Not suspended", details: {} },
          { status: 409 }
        )
      )
    );

    const qc = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    const spy = vi.spyOn(qc, "invalidateQueries");

    const { result } = renderHook(() => useReinstateAuction(USER_PID), {
      wrapper: makeWrapper(qc),
    });

    await act(async () => {
      await result.current.mutate({ auctionPublicId: AUCTION_PID, notes: "try reinstate" });
    });

    expect(result.current.isError).toBe(true);
    const keys = spy.mock.calls.map((c) => (c[0] as { queryKey?: unknown }).queryKey);
    expect(keys).toContainEqual(adminQueryKeys.userTab(USER_PID, "listings", { page: 0, size: 25 }));
  });

  it("ignores admin stats invalidation when AUCTION_NOT_SUSPENDED", async () => {
    server.use(
      http.post(`*/api/v1/admin/auctions/${AUCTION_PID}/reinstate`, () =>
        HttpResponse.json(
          { code: "AUCTION_NOT_SUSPENDED", message: "Not suspended", details: {} },
          { status: 409 }
        )
      )
    );

    const qc = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    const spy = vi.spyOn(qc, "invalidateQueries");

    const { result } = renderHook(() => useReinstateAuction(USER_PID), {
      wrapper: makeWrapper(qc),
    });

    await act(async () => {
      await result.current.mutate({ auctionPublicId: AUCTION_PID, notes: "try reinstate" });
    });

    const keys = spy.mock.calls.map((c) => (c[0] as { queryKey?: unknown }).queryKey);
    expect(keys).not.toContainEqual(adminQueryKeys.stats());
  });
});
