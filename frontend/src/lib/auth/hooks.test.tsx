// frontend/src/lib/auth/hooks.test.tsx
import { describe, it, expect, beforeEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import { server } from "@/test/msw/server";
import { authHandlers } from "@/test/msw/handlers";
import { useAuth } from "./hooks";
import { setAccessToken, getAccessToken } from "./session";

function wrapper({ children }: { children: React.ReactNode }) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

describe("useAuth", () => {
  beforeEach(() => {
    setAccessToken(null);
  });

  it("starts in loading state, then transitions to unauthenticated on 401", async () => {
    server.use(authHandlers.refreshUnauthenticated());

    const { result } = renderHook(() => useAuth(), { wrapper });

    expect(result.current.status).toBe("loading");

    await waitFor(() => {
      expect(result.current.status).toBe("unauthenticated");
    });
    expect(result.current.user).toBeNull();
    expect(getAccessToken()).toBeNull();
  });

  it("transitions to authenticated and sets access token on successful refresh", async () => {
    server.use(authHandlers.refreshSuccess());

    const { result } = renderHook(() => useAuth(), { wrapper });

    await waitFor(() => {
      expect(result.current.status).toBe("authenticated");
    });
    expect(result.current.user?.email).toBe("test@example.com");
    expect(getAccessToken()).toBe("mock-access-token-jwt");
  });
});
