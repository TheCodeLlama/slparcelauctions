import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { renderHook, waitFor, act } from "@testing-library/react";
import { server } from "@/test/msw/server";
import { makeWrapper } from "@/test/render";
import { useSearchSuggest } from "./useSearchSuggest";
import { mockSuggestResponse } from "@/test/msw/fixtures";

describe("useSearchSuggest", () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it("does not fetch when query is < 2 chars", async () => {
    let calls = 0;
    server.use(
      http.get("*/api/v1/search/suggest", () => {
        calls += 1;
        return HttpResponse.json(mockSuggestResponse());
      }),
    );
    const { result } = renderHook(() => useSearchSuggest("a"), {
      wrapper: makeWrapper(),
    });
    await act(async () => {
      vi.advanceTimersByTime(300);
    });
    expect(result.current.isFetching).toBe(false);
    expect(calls).toBe(0);
  });

  it("debounces rapid keystrokes into a single fetch", async () => {
    let calls = 0;
    server.use(
      http.get("*/api/v1/search/suggest", () => {
        calls += 1;
        return HttpResponse.json(mockSuggestResponse());
      }),
    );
    // Start with a 1-char input that's below the gate so no fetch
    // fires — then rapidly type "tu", "tul", "tula" before letting
    // the debounce settle. Only the final value should hit the wire.
    const { rerender, result } = renderHook(
      (q: string) => useSearchSuggest(q),
      { wrapper: makeWrapper(), initialProps: "t" },
    );
    rerender("tu");
    rerender("tul");
    rerender("tula");
    await act(async () => {
      vi.advanceTimersByTime(300);
    });
    await waitFor(() => expect(calls).toBe(1));
    expect(result.current.data).toBeDefined();
  });
});
