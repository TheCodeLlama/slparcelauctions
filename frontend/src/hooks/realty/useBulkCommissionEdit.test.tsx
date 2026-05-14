import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import { server } from "@/test/msw/server";
import { useBulkCommissionEdit } from "./useBulkCommissionEdit";
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

describe("useBulkCommissionEdit", () => {
  beforeEach(() => {
    server.use(
      http.patch(
        `*/api/v1/realty-groups/${GROUP_PUBLIC_ID}/members/commission-rates`,
        () => new HttpResponse(null, { status: 204 }),
      ),
    );
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("invalidates the entire realty query prefix on success (so /manage/members refetches after the drawer closes)", async () => {
    const qc = makeQc();
    // Seed three slices of the group cache — they should all be marked
    // stale by the bulk-edit mutation's success handler. The earlier bug
    // was that the mutation used a non-existent ["realty", "groups", id]
    // prefix (plural "groups") that matched none of these keys.
    qc.setQueryData(realtyQueryKeys.group(GROUP_PUBLIC_ID), { stale: false });
    qc.setQueryData(realtyQueryKeys.groupBySlug(GROUP_SLUG), { stale: false });
    qc.setQueryData(realtyQueryKeys.groupMembers(GROUP_PUBLIC_ID), {
      stale: false,
    });

    const invalidateSpy = vi.spyOn(qc, "invalidateQueries");

    const { result } = renderHook(() => useBulkCommissionEdit(GROUP_PUBLIC_ID), {
      wrapper: wrapper(qc),
    });

    await result.current.mutateAsync({ rates: [] });

    await waitFor(() => {
      expect(invalidateSpy).toHaveBeenCalledWith({
        queryKey: realtyQueryKeys.all,
      });
    });

    // All three seeded keys are descendants of realtyQueryKeys.all, so the
    // invalidation hit every one.
    expect(qc.getQueryState(realtyQueryKeys.group(GROUP_PUBLIC_ID))?.isInvalidated)
      .toBe(true);
    expect(qc.getQueryState(realtyQueryKeys.groupBySlug(GROUP_SLUG))?.isInvalidated)
      .toBe(true);
    expect(
      qc.getQueryState(realtyQueryKeys.groupMembers(GROUP_PUBLIC_ID))
        ?.isInvalidated,
    ).toBe(true);
  });
});
