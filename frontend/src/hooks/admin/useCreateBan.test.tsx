import { describe, it, expect, vi } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { type ReactNode } from "react";
import { ToastProvider } from "@/components/ui/Toast";
import { server } from "@/test/msw/server";
import { adminHandlers } from "@/test/msw/handlers";
import { useCreateBan } from "./useCreateBan";
import { adminQueryKeys } from "@/lib/admin/queryKeys";
import type { AdminBanRow } from "@/lib/admin/types";

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

function makeBan(overrides: Partial<AdminBanRow> = {}): AdminBanRow {
  return {
    id: 1,
    banType: "IP",
    ipAddress: "10.0.0.1",
    slAvatarUuid: null,
    avatarLinkedUserId: null,
    avatarLinkedDisplayName: null,
    firstSeenIp: null,
    reasonCategory: "TOS_ABUSE",
    reasonText: "Test",
    bannedByUserId: 1,
    bannedByDisplayName: "Admin",
    expiresAt: null,
    createdAt: "2026-04-01T10:00:00Z",
    liftedAt: null,
    liftedByUserId: null,
    liftedByDisplayName: null,
    liftedReason: null,
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

describe("useCreateBan", () => {
  it("invalidates bans and stats query keys on success", async () => {
    const ban = makeBan({ id: 1 });
    server.use(adminHandlers.createBanSuccess(ban));
    server.use(adminHandlers.statsSuccess());

    const qc = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    const spy = vi.spyOn(qc, "invalidateQueries");

    const { result } = renderHook(() => useCreateBan(), {
      wrapper: makeWrapper(qc),
    });

    await act(async () => {
      await result.current.mutateAsync({
        banType: "IP",
        ipAddress: "10.0.0.1",
        reasonCategory: "TOS_ABUSE",
        reasonText: "Test",
      });
    });

    const callArgs = spy.mock.calls.map((c) => c[0]);
    const keys = callArgs.map((a) => (a as { queryKey?: unknown }).queryKey);

    expect(keys).toContainEqual(adminQueryKeys.bans());
    expect(keys).toContainEqual(adminQueryKeys.stats());
  });

  it("shows BAN_TYPE_FIELD_MISMATCH toast on matching error code", async () => {
    server.use(adminHandlers.ban409TypeFieldMismatch(1));

    const qc = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });

    const { result } = renderHook(() => useCreateBan(), {
      wrapper: makeWrapper(qc),
    });

    await act(async () => {
      await result.current.mutate({
        banType: "IP",
        ipAddress: "10.0.0.1",
        reasonCategory: "TOS_ABUSE",
        reasonText: "Test",
      });
    });

    expect(result.current.isError).toBe(true);
  });
});
