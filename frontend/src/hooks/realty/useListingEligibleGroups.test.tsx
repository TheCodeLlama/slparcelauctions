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

describe("useListingEligibleGroups", () => {
  it("returns eligible groups from the endpoint", async () => {
    server.use(
      http.get("*/api/v1/realty/me/listing-eligible-groups", () =>
        HttpResponse.json([
          {
            publicId: "g1",
            name: "Sunset Realty",
            slug: "sunset-realty",
            logoUrl: null,
            agentFeeRate: 0.02,
          },
        ]),
      ),
    );
    const { result } = renderHook(() => useListingEligibleGroups(), { wrapper });
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
    const { result } = renderHook(() => useListingEligibleGroups(), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toEqual([]);
  });
});
