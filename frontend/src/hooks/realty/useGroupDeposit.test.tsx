import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import { server } from "@/test/msw/server";
import { useGroupDeposit } from "./useGroupDeposit";
import { realtyQueryKeys } from "./useRealtyGroups";

const GROUP_PUBLIC_ID = "00000000-0000-0000-0000-000000000001";
const GROUP_SLUG = "mainland-realty";

function makeQc() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
}

function wrapper(qc: QueryClient) {
  return function Wrapper({ children }: { children: React.ReactNode }) {
    return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
  };
}

describe("useGroupDeposit", () => {
  beforeEach(() => {
    server.use(
      http.post(
        `*/api/v1/realty/groups/${GROUP_PUBLIC_ID}/wallet/deposit`,
        () =>
          HttpResponse.json(
            {
              groupLedgerEntryId: 99,
              personalLedgerEntryId: 199,
              newGroupAvailable: 2200,
              newPersonalAvailable: 3800,
            },
            { status: 200 },
          ),
      ),
    );
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("invalidates the realty query prefix and the personal wallet key on success", async () => {
    const qc = makeQc();
    // Seed slices the mutation must mark stale: group + by-slug + personal
    // wallet. The realty mutation pattern invalidates the entire realty
    // prefix plus the ["me", "wallet"] key.
    qc.setQueryData(realtyQueryKeys.group(GROUP_PUBLIC_ID), { stale: false });
    qc.setQueryData(realtyQueryKeys.groupBySlug(GROUP_SLUG), { stale: false });
    qc.setQueryData(["me", "wallet"], { stale: false });

    const invalidateSpy = vi.spyOn(qc, "invalidateQueries");

    const { result } = renderHook(() => useGroupDeposit(GROUP_PUBLIC_ID), {
      wrapper: wrapper(qc),
    });

    const response = await result.current.mutateAsync({
      amount: 1200,
      memo: "Reimbursement",
      idempotencyKey: "00000000-0000-0000-0000-0000000000aa",
    });

    expect(response.newGroupAvailable).toBe(2200);
    expect(response.newPersonalAvailable).toBe(3800);

    await waitFor(() => {
      expect(invalidateSpy).toHaveBeenCalledWith({
        queryKey: realtyQueryKeys.all,
      });
      expect(invalidateSpy).toHaveBeenCalledWith({
        queryKey: ["me", "wallet"],
      });
    });

    expect(
      qc.getQueryState(realtyQueryKeys.group(GROUP_PUBLIC_ID))?.isInvalidated,
    ).toBe(true);
    expect(
      qc.getQueryState(realtyQueryKeys.groupBySlug(GROUP_SLUG))?.isInvalidated,
    ).toBe(true);
    expect(qc.getQueryState(["me", "wallet"])?.isInvalidated).toBe(true);
  });
});
