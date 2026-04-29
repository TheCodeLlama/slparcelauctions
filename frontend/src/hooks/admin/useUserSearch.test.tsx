import { describe, it, expect, vi } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { type ReactNode } from "react";
import { ToastProvider } from "@/components/ui/Toast";
import { server } from "@/test/msw/server";
import { adminHandlers } from "@/test/msw/handlers";
import { useUserSearch } from "./useUserSearch";
import type { AdminUserSummary } from "@/lib/admin/types";

vi.mock("next/navigation", () => ({
  usePathname: vi.fn(() => "/admin/bans"),
  useRouter: () => ({
    push: vi.fn(),
    replace: vi.fn(),
    refresh: vi.fn(),
    back: vi.fn(),
    forward: vi.fn(),
    prefetch: vi.fn(),
  }),
  useSearchParams: vi.fn(() => new URLSearchParams()),
}));

function makeUser(overrides: Partial<AdminUserSummary> = {}): AdminUserSummary {
  return {
    id: 1,
    email: "alice@example.com",
    displayName: "Alice",
    slAvatarUuid: "aaaa-1111",
    slDisplayName: null,
    role: "USER",
    verified: true,
    hasActiveBan: false,
    completedSales: 0,
    cancelledWithBids: 0,
    createdAt: "2026-01-01T00:00:00Z",
    ...overrides,
  };
}

function makeWrapper(qc: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={qc}>
        <ToastProvider>{children}</ToastProvider>
      </QueryClientProvider>
    );
  };
}

describe("useUserSearch", () => {
  it("is disabled for queries shorter than 2 chars", () => {
    const qc = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });

    const { result } = renderHook(() => useUserSearch("a"), {
      wrapper: makeWrapper(qc),
    });

    expect(result.current.fetchStatus).toBe("idle");
    expect(result.current.data).toBeUndefined();
  });

  it("is disabled for empty query", () => {
    const qc = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });

    const { result } = renderHook(() => useUserSearch(""), {
      wrapper: makeWrapper(qc),
    });

    expect(result.current.fetchStatus).toBe("idle");
  });

  it("enables and fetches when query is 2+ chars", async () => {
    const user = makeUser({ id: 5, displayName: "Alice" });
    server.use(adminHandlers.usersSearchSuccess([user]));

    const qc = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });

    const { result } = renderHook(() => useUserSearch("al"), {
      wrapper: makeWrapper(qc),
    });

    await waitFor(
      () => expect(result.current.isSuccess).toBe(true),
      { timeout: 1500 }
    );

    expect(result.current.data?.content[0].displayName).toBe("Alice");
  });
});
