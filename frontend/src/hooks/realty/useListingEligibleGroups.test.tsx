import { describe, it, expect } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import { type ReactNode } from "react";
import { server } from "@/test/msw/server";
import { useListingEligibleGroups } from "./useListingEligibleGroups";

function wrapper({ children }: { children: ReactNode }) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
}

const PARCEL_UUID = "11111111-1111-1111-1111-111111111111";

describe("useListingEligibleGroups", () => {
  it("returns eligible groups from the endpoint", async () => {
    server.use(
      http.get("*/api/v1/realty/me/listing-eligible-groups", ({ request }) => {
        const url = new URL(request.url);
        expect(url.searchParams.get("slParcelUuid")).toBe(PARCEL_UUID);
        return HttpResponse.json([
          {
            publicId: "g1",
            name: "Sunset Realty",
            slug: "sunset-realty",
            logoUrl: null,
            agentCommissionRate: 0.02,
          },
        ]);
      }),
    );
    const { result } = renderHook(() => useListingEligibleGroups(PARCEL_UUID), {
      wrapper,
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveLength(1);
    expect(result.current.data?.[0].name).toBe("Sunset Realty");
  });

  it("returns empty array when no eligible groups", async () => {
    server.use(
      http.get("*/api/v1/realty/me/listing-eligible-groups", () =>
        HttpResponse.json([]),
      ),
    );
    const { result } = renderHook(() => useListingEligibleGroups(PARCEL_UUID), {
      wrapper,
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toEqual([]);
  });

  it("stays disabled (no fetch) when slParcelUuid is undefined", async () => {
    const { result } = renderHook(() => useListingEligibleGroups(undefined), {
      wrapper,
    });
    expect(result.current.fetchStatus).toBe("idle");
    expect(result.current.data).toBeUndefined();
  });
});
