import { describe, it, expect } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { makeWrapper } from "@/test/render";
import { useAuctionSearch } from "./useAuctionSearch";
import { server } from "@/test/msw/server";
import { http, HttpResponse } from "msw";
import type { SearchResponse } from "@/types/search";

const emptyResponse: SearchResponse = {
  content: [],
  page: 0,
  size: 24,
  totalElements: 0,
  totalPages: 0,
  first: true,
  last: true,
};

describe("useAuctionSearch", () => {
  it("uses initialData without fetching on first render", () => {
    const { result } = renderHook(
      () => useAuctionSearch({ sort: "newest" }, { initialData: emptyResponse }),
      { wrapper: makeWrapper({}) },
    );
    expect(result.current.data).toEqual(emptyResponse);
    expect(result.current.isFetching).toBe(false);
  });

  it("refetches when the query changes", async () => {
    let calls = 0;
    server.use(
      http.get("*/api/v1/auctions/search", () => {
        calls++;
        return HttpResponse.json(emptyResponse);
      }),
    );
    // initialData only primes the FIRST render — the rerender with a different
    // sort creates a new queryKey that does not have initialData, so React
    // Query must fire the network request.
    type Props = {
      q: { sort: "newest" | "ending_soonest" };
      seed: boolean;
    };
    const { rerender } = renderHook(
      ({ q, seed }: Props) =>
        useAuctionSearch(q, seed ? { initialData: emptyResponse } : {}),
      {
        wrapper: makeWrapper({}),
        initialProps: {
          q: { sort: "newest" },
          seed: true,
        } as Props,
      },
    );
    expect(calls).toBe(0);
    rerender({ q: { sort: "ending_soonest" }, seed: false });
    await waitFor(() => expect(calls).toBe(1));
  });
});
