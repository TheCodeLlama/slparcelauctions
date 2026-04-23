import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { renderHook, waitFor } from "@testing-library/react";
import { makeWrapper } from "@/test/render";
import { server } from "@/test/msw/server";
import type { MyBidSummary } from "@/types/auction";
import type { Page } from "@/types/page";
import {
  useMyBids,
  useMyBidsPage,
  myBidsKey,
  myBidsInfiniteKey,
} from "./useMyBids";

function emptyPage<T>(): Page<T> {
  return {
    content: [],
    totalElements: 0,
    totalPages: 0,
    number: 0,
    size: 20,
  };
}

describe("useMyBids (infinite)", () => {
  it("fetches the first page with the provided status filter", async () => {
    let captured: URL | null = null;
    server.use(
      http.get("*/api/v1/users/me/bids", ({ request }) => {
        captured = new URL(request.url);
        return HttpResponse.json(emptyPage<MyBidSummary>());
      }),
    );

    const { result } = renderHook(() => useMyBids("active"), {
      wrapper: makeWrapper({ auth: "authenticated" }),
    });

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });
    expect(captured).not.toBeNull();
    expect(captured!.searchParams.get("status")).toBe("active");
    expect(captured!.searchParams.get("page")).toBe("0");
    expect(captured!.searchParams.get("size")).toBe("20");
  });

  it("flips hasNextPage off when every row is loaded", async () => {
    server.use(
      http.get("*/api/v1/users/me/bids", () =>
        HttpResponse.json({
          ...emptyPage<MyBidSummary>(),
          content: [],
          totalElements: 0,
        }),
      ),
    );
    const { result } = renderHook(() => useMyBids("all"), {
      wrapper: makeWrapper({ auth: "authenticated" }),
    });
    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });
    expect(result.current.hasNextPage).toBe(false);
  });
});

describe("useMyBidsPage (single page)", () => {
  it("sends status/page/size query params", async () => {
    let captured: URL | null = null;
    server.use(
      http.get("*/api/v1/users/me/bids", ({ request }) => {
        captured = new URL(request.url);
        return HttpResponse.json(emptyPage<MyBidSummary>());
      }),
    );

    const { result } = renderHook(() => useMyBidsPage("won", 1), {
      wrapper: makeWrapper({ auth: "authenticated" }),
    });

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });
    expect(captured!.searchParams.get("status")).toBe("won");
    expect(captured!.searchParams.get("page")).toBe("1");
  });
});

describe("key factories", () => {
  it("myBidsKey differentiates on status and page", () => {
    expect(myBidsKey("all", 0)).toEqual([
      "my-bids",
      { status: "all", page: 0 },
    ]);
    expect(myBidsKey("won", 2)).toEqual([
      "my-bids",
      { status: "won", page: 2 },
    ]);
  });

  it("myBidsInfiniteKey differentiates only on status", () => {
    expect(myBidsInfiniteKey("lost")).toEqual([
      "my-bids",
      "infinite",
      { status: "lost" },
    ]);
  });
});
