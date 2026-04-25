import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { renderHook, waitFor } from "@testing-library/react";
import { makeWrapper } from "@/test/render";
import { server } from "@/test/msw/server";
import { useCancellationHistory } from "./useCancellationHistory";

describe("useCancellationHistory", () => {
  it("fetches a paged history response", async () => {
    let receivedUrl: URL | null = null;
    server.use(
      http.get("*/api/v1/users/me/cancellation-history", ({ request }) => {
        receivedUrl = new URL(request.url);
        return HttpResponse.json({
          content: [],
          totalElements: 0,
          totalPages: 0,
          number: 0,
          size: 10,
        });
      }),
    );

    const { result } = renderHook(() => useCancellationHistory(0), {
      wrapper: makeWrapper({ auth: "authenticated" }),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(receivedUrl!.searchParams.get("page")).toBe("0");
    expect(receivedUrl!.searchParams.get("size")).toBe("10");
  });

  it("forwards the size override on the wire", async () => {
    let receivedUrl: URL | null = null;
    server.use(
      http.get("*/api/v1/users/me/cancellation-history", ({ request }) => {
        receivedUrl = new URL(request.url);
        return HttpResponse.json({
          content: [],
          totalElements: 0,
          totalPages: 0,
          number: 0,
          size: 5,
        });
      }),
    );

    const { result } = renderHook(() => useCancellationHistory(1, 5), {
      wrapper: makeWrapper({ auth: "authenticated" }),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(receivedUrl!.searchParams.get("page")).toBe("1");
    expect(receivedUrl!.searchParams.get("size")).toBe("5");
  });
});
