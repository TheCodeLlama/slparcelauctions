import { describe, it, expect, vi } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { type ReactNode } from "react";
import { http, HttpResponse } from "msw";
import { ToastProvider } from "@/components/ui/Toast";
import { server } from "@/test/msw/server";
import { adminHandlers } from "@/test/msw/handlers";
import { useDemoteUser } from "./useDemoteUser";
import { adminQueryKeys } from "@/lib/admin/queryKeys";

vi.mock("next/navigation", () => ({
  usePathname: vi.fn(() => "/admin/users/7"),
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

const USER_ID = 7;

function makeWrapper(qc: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={qc}>
        <ToastProvider>{children}</ToastProvider>
      </QueryClientProvider>
    );
  };
}

describe("useDemoteUser", () => {
  it("invalidates user, users, and stats on success", async () => {
    // Use a 204 response since the endpoint returns void
    server.use(
      http.post(`*/api/v1/admin/users/${USER_ID}/demote`, () =>
        new HttpResponse(null, { status: 204 })
      )
    );

    const qc = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    const spy = vi.spyOn(qc, "invalidateQueries");

    const { result } = renderHook(() => useDemoteUser(USER_ID), {
      wrapper: makeWrapper(qc),
    });

    await act(async () => {
      await result.current.mutateAsync("demote notes");
    });

    const keys = spy.mock.calls.map((c) => (c[0] as { queryKey?: unknown }).queryKey);
    expect(keys).toContainEqual(adminQueryKeys.user(USER_ID));
    expect(keys).toContainEqual(adminQueryKeys.users());
    expect(keys).toContainEqual(adminQueryKeys.stats());
  });

  it("surfaces SELF_DEMOTE_FORBIDDEN as a toast error (mutation is error state)", async () => {
    server.use(adminHandlers.demoteUser409SelfForbidden(USER_ID));

    const qc = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });

    const { result } = renderHook(() => useDemoteUser(USER_ID), {
      wrapper: makeWrapper(qc),
    });

    // Use mutate (fire-and-forget) so the onError handler runs but the returned
    // promise doesn't reject — the isError flag is still set in React Query.
    await act(async () => {
      result.current.mutate("self demote attempt");
      // Wait for all microtasks/state updates to flush
      await new Promise((r) => setTimeout(r, 50));
    });

    expect(result.current.isError).toBe(true);
  });

  it("surfaces NOT_ADMIN as error state", async () => {
    server.use(adminHandlers.demoteUser409NotAdmin(USER_ID));

    const qc = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });

    const { result } = renderHook(() => useDemoteUser(USER_ID), {
      wrapper: makeWrapper(qc),
    });

    await act(async () => {
      result.current.mutate("not admin");
      await new Promise((r) => setTimeout(r, 50));
    });

    expect(result.current.isError).toBe(true);
  });
});
