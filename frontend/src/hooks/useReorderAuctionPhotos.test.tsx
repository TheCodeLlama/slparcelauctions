import { describe, expect, it, beforeAll, afterAll, afterEach } from "vitest";
import { setupServer } from "msw/node";
import { http, HttpResponse } from "msw";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { useReorderAuctionPhotos } from "./useReorderAuctionPhotos";

const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function makeWrapper(qc: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
  };
}

describe("useReorderAuctionPhotos", () => {
  it("calls PATCH /photos/order and resolves with the new DTO array", async () => {
    server.use(
      http.patch("*/api/v1/auctions/abc/photos/order", async () =>
        HttpResponse.json([
          {
            publicId: "p2",
            url: "/api/v1/photos/p2",
            contentType: "image/webp",
            sizeBytes: 1,
            sortOrder: 1,
            uploadedAt: "x",
          },
        ])),
    );
    const qc = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    const { result } = renderHook(() => useReorderAuctionPhotos("abc"), {
      wrapper: makeWrapper(qc),
    });
    await result.current.mutateAsync(["p2"]);
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveLength(1);
    expect(result.current.data?.[0].publicId).toBe("p2");
  });
});
