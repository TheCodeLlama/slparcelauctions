import { describe, expect, it, beforeAll, afterAll, afterEach } from "vitest";
import { setupServer } from "msw/node";
import { http, HttpResponse } from "msw";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { useDraftEditorMutations } from "./draftEditorMutations";

const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function makeWrapper(qc: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
  };
}

describe("useDraftEditorMutations", () => {
  it("saveTitle PUTs the auction with { title }", async () => {
    let received: unknown = null;
    server.use(
      http.put("*/api/v1/auctions/abc", async ({ request }) => {
        received = await request.json();
        return HttpResponse.json({ publicId: "abc", title: "new" });
      }),
    );
    const qc = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    const { result } = renderHook(() => useDraftEditorMutations("abc"), {
      wrapper: makeWrapper(qc),
    });
    await result.current.saveTitle("new");
    await waitFor(() => expect(received).toEqual({ title: "new" }));
  });

  it("saveDescription PUTs { sellerDesc }", async () => {
    let received: unknown = null;
    server.use(
      http.put("*/api/v1/auctions/abc", async ({ request }) => {
        received = await request.json();
        return HttpResponse.json({ publicId: "abc", sellerDesc: "Lovely parcel" });
      }),
    );
    const qc = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    const { result } = renderHook(() => useDraftEditorMutations("abc"), {
      wrapper: makeWrapper(qc),
    });
    await result.current.saveDescription("Lovely parcel");
    await waitFor(() => expect(received).toEqual({ sellerDesc: "Lovely parcel" }));
  });

  it("saveTags PUTs { tags: string[] }", async () => {
    let received: unknown = null;
    server.use(
      http.put("*/api/v1/auctions/abc", async ({ request }) => {
        received = await request.json();
        return HttpResponse.json({ publicId: "abc", tags: [] });
      }),
    );
    const qc = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    const { result } = renderHook(() => useDraftEditorMutations("abc"), {
      wrapper: makeWrapper(qc),
    });
    await result.current.saveTags(["WATERFRONT"]);
    await waitFor(() => expect(received).toEqual({ tags: ["WATERFRONT"] }));
  });
});
