import { describe, it, expect, vi, beforeEach } from "vitest";
import { http, HttpResponse } from "msw";
import { act, renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactElement, ReactNode } from "react";
import { ThemeProvider } from "next-themes";
import { ToastProvider } from "@/components/ui/Toast";
import { server } from "@/test/msw/server";
import { mockUser } from "@/test/msw/fixtures";
import type { SearchResponse } from "@/types/search";
import {
  savedAuctionsQueryKey,
  useSavedAuctions,
  useSavedIds,
  useToggleSaved,
  SAVED_IDS_KEY,
} from "./useSavedAuctions";

const SESSION_QUERY_KEY = ["auth", "session"] as const;

type Wrapped = {
  client: QueryClient;
  wrapper: (props: { children: ReactNode }) => ReactElement;
};

function makeAuthedWrapper(): Wrapped {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  client.setQueryData(SESSION_QUERY_KEY, mockUser);

  const wrapper = ({ children }: { children: ReactNode }): ReactElement => (
    <ThemeProvider attribute="class" defaultTheme="light" enableSystem={false}>
      <QueryClientProvider client={client}>
        <ToastProvider>{children}</ToastProvider>
      </QueryClientProvider>
    </ThemeProvider>
  );

  return { client, wrapper };
}

function makeAnonWrapper(): Wrapped {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  client.setQueryData(SESSION_QUERY_KEY, null);

  const wrapper = ({ children }: { children: ReactNode }): ReactElement => (
    <ThemeProvider attribute="class" defaultTheme="light" enableSystem={false}>
      <QueryClientProvider client={client}>
        <ToastProvider>{children}</ToastProvider>
      </QueryClientProvider>
    </ThemeProvider>
  );

  return { client, wrapper };
}

const emptySearch: SearchResponse = {
  content: [],
  page: 0,
  size: 24,
  totalElements: 0,
  totalPages: 0,
  first: true,
  last: true,
};

beforeEach(() => {
  // Stub window.location.assign so the unauth sign-in CTA does not try to
  // actually navigate the test harness.
  Object.defineProperty(window, "location", {
    configurable: true,
    value: {
      ...window.location,
      assign: vi.fn(),
    },
  });
});

describe("useSavedIds", () => {
  it("returns an empty Set synchronously when unauthenticated", () => {
    const { wrapper } = makeAnonWrapper();
    const { result } = renderHook(() => useSavedIds(), { wrapper });
    expect(result.current.ids.size).toBe(0);
    expect(result.current.isSaved(42)).toBe(false);
    expect(result.current.isLoading).toBe(false);
  });

  it("hydrates from GET /me/saved/ids when authenticated", async () => {
    server.use(
      http.get("*/api/v1/me/saved/ids", () =>
        HttpResponse.json({ ids: [1, 7, 42] }),
      ),
    );
    const { wrapper } = makeAuthedWrapper();
    const { result } = renderHook(() => useSavedIds(), { wrapper });

    await waitFor(() => {
      expect(result.current.ids.size).toBe(3);
    });
    expect(result.current.isSaved(7)).toBe(true);
    expect(result.current.isSaved(99)).toBe(false);
  });
});

describe("useToggleSaved", () => {
  it("unauthenticated → surfaces a sign-in warning toast and does not POST", async () => {
    let posted = false;
    server.use(
      http.post("*/api/v1/me/saved", () => {
        posted = true;
        return HttpResponse.json(
          { auctionId: 1, savedAt: "2026-04-23T00:00:00Z" },
          { status: 201 },
        );
      }),
    );
    const { wrapper } = makeAnonWrapper();
    const { result } = renderHook(() => useToggleSaved(), { wrapper });

    await act(async () => {
      await result.current.toggle(1);
    });

    await waitFor(() => {
      expect(
        (document.body.textContent ?? "").toLowerCase(),
      ).toContain("sign in to save");
    });
    expect(posted).toBe(false);
  });

  it("authenticated save → optimistically adds id", async () => {
    server.use(
      http.get("*/api/v1/me/saved/ids", () =>
        HttpResponse.json({ ids: [] }),
      ),
      http.post("*/api/v1/me/saved", () =>
        HttpResponse.json(
          { auctionId: 7, savedAt: "2026-04-23T00:00:00Z" },
          { status: 201 },
        ),
      ),
    );
    const { client, wrapper } = makeAuthedWrapper();
    client.setQueryData(SAVED_IDS_KEY, { ids: [] });

    const { result } = renderHook(() => useToggleSaved(), { wrapper });

    await act(async () => {
      await result.current.toggle(7);
    });

    await waitFor(() => {
      const next = client.getQueryData<{ ids: number[] }>(SAVED_IDS_KEY);
      expect(next?.ids).toContain(7);
    });
  });

  it("rolls back the optimistic update when the POST fails", async () => {
    server.use(
      http.get("*/api/v1/me/saved/ids", () =>
        HttpResponse.json({ ids: [] }),
      ),
      http.post("*/api/v1/me/saved", () =>
        HttpResponse.json(
          { status: 500, title: "Internal Server Error" },
          { status: 500 },
        ),
      ),
    );
    const { client, wrapper } = makeAuthedWrapper();
    client.setQueryData(SAVED_IDS_KEY, { ids: [] });

    const { result } = renderHook(() => useToggleSaved(), { wrapper });

    await act(async () => {
      await result.current.toggle(7);
    });

    await waitFor(() => {
      const next = client.getQueryData<{ ids: number[] }>(SAVED_IDS_KEY);
      expect(next?.ids ?? []).not.toContain(7);
    });
  });

  it("surfaces the SAVED_LIMIT_REACHED copy on 409", async () => {
    server.use(
      http.get("*/api/v1/me/saved/ids", () =>
        HttpResponse.json({ ids: [] }),
      ),
      http.post("*/api/v1/me/saved", () =>
        HttpResponse.json(
          {
            status: 409,
            code: "SAVED_LIMIT_REACHED",
            title: "Limit reached",
          },
          { status: 409 },
        ),
      ),
    );
    const { client, wrapper } = makeAuthedWrapper();
    client.setQueryData(SAVED_IDS_KEY, { ids: [] });

    const { result } = renderHook(() => useToggleSaved(), { wrapper });

    await act(async () => {
      await result.current.toggle(7);
    });

    await waitFor(() => {
      expect(document.body.textContent ?? "").toContain(
        "Curator Tray is full",
      );
    });
  });

  it("surfaces the CANNOT_SAVE_PRE_ACTIVE copy on 403", async () => {
    server.use(
      http.get("*/api/v1/me/saved/ids", () =>
        HttpResponse.json({ ids: [] }),
      ),
      http.post("*/api/v1/me/saved", () =>
        HttpResponse.json(
          {
            status: 403,
            code: "CANNOT_SAVE_PRE_ACTIVE",
            title: "Not available",
          },
          { status: 403 },
        ),
      ),
    );
    const { client, wrapper } = makeAuthedWrapper();
    client.setQueryData(SAVED_IDS_KEY, { ids: [] });

    const { result } = renderHook(() => useToggleSaved(), { wrapper });

    await act(async () => {
      await result.current.toggle(7);
    });

    await waitFor(() => {
      expect(document.body.textContent ?? "").toContain(
        "isn't available to save yet",
      );
    });
  });

  it("unsave path: DELETE when id is already saved", async () => {
    let deletedId = 0;
    server.use(
      http.get("*/api/v1/me/saved/ids", () =>
        HttpResponse.json({ ids: [7] }),
      ),
      http.delete("*/api/v1/me/saved/:id", ({ params }) => {
        deletedId = Number(params.id);
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const { client, wrapper } = makeAuthedWrapper();
    client.setQueryData(SAVED_IDS_KEY, { ids: [7] });

    const { result } = renderHook(() => useToggleSaved(), { wrapper });

    await act(async () => {
      await result.current.toggle(7);
    });

    await waitFor(() => {
      expect(deletedId).toBe(7);
    });
  });
});

describe("useSavedAuctions query key canonicalization", () => {
  it("produces the same queryKey for different object/array orders", () => {
    const a = savedAuctionsQueryKey({
      region: "Tula",
      tags: ["A", "B"],
      sort: "newest",
    });
    const b = savedAuctionsQueryKey({
      tags: ["B", "A"],
      sort: "newest",
      region: "Tula",
    });
    expect(a).toEqual(b);
  });

  it("fetches from GET /me/saved/auctions when authenticated", async () => {
    let called = 0;
    server.use(
      http.get("*/api/v1/me/saved/auctions", () => {
        called++;
        return HttpResponse.json(emptySearch);
      }),
    );
    const { wrapper } = makeAuthedWrapper();
    const { result } = renderHook(
      () => useSavedAuctions({ sort: "newest" }),
      { wrapper },
    );
    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });
    expect(called).toBeGreaterThanOrEqual(1);
  });

  it("does not fetch when unauthenticated", async () => {
    let called = 0;
    server.use(
      http.get("*/api/v1/me/saved/auctions", () => {
        called++;
        return HttpResponse.json(emptySearch);
      }),
    );
    const { wrapper } = makeAnonWrapper();
    renderHook(() => useSavedAuctions({ sort: "newest" }), { wrapper });
    await new Promise((r) => setTimeout(r, 25));
    expect(called).toBe(0);
  });
});

describe("useToggleSaved invalidation — refetchType: active", () => {
  it("does NOT refetch inactive saved-auctions query observers after a toggle", async () => {
    server.use(
      http.get("*/api/v1/me/saved/ids", () =>
        HttpResponse.json({ ids: [] }),
      ),
      http.post("*/api/v1/me/saved", () =>
        HttpResponse.json(
          { auctionId: 1, savedAt: "2026-04-23T00:00:00Z" },
          { status: 201 },
        ),
      ),
    );
    const { client, wrapper } = makeAuthedWrapper();
    client.setQueryData(SAVED_IDS_KEY, { ids: [] });

    const inactiveKey = savedAuctionsQueryKey({ sort: "newest" });
    client.setQueryData(inactiveKey, emptySearch);
    let inactiveFetchCount = 0;
    server.use(
      http.get("*/api/v1/me/saved/auctions", () => {
        inactiveFetchCount++;
        return HttpResponse.json(emptySearch);
      }),
    );

    const { result } = renderHook(() => useToggleSaved(), { wrapper });
    await act(async () => {
      await result.current.toggle(1);
    });

    await new Promise((r) => setTimeout(r, 25));
    expect(inactiveFetchCount).toBe(0);
  });
});
