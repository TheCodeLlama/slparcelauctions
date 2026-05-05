// frontend/src/lib/auth/hooks.test.tsx
import { describe, it, expect, beforeEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import { server } from "@/test/msw/server";
import { authHandlers } from "@/test/msw/handlers";
import { useAuth, useLogin, useRegister, useLogout, useForgotPassword } from "./hooks";
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
    expect(result.current.user?.username).toBe("test-user");
    expect(getAccessToken()).toBe("mock-access-token-jwt");
  });
});

describe("useLogin", () => {
  beforeEach(() => {
    setAccessToken(null);
  });

  it("calls /api/v1/auth/login, sets access token, and updates session cache on success", async () => {
    server.use(authHandlers.loginSuccess());

    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );

    const { result } = renderHook(() => useLogin(), { wrapper });

    result.current.mutate({ username: "test-user", password: "anything" });

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });

    expect(getAccessToken()).toBe("mock-access-token-jwt");
    expect(queryClient.getQueryData(["auth", "session"])).toMatchObject({
      username: "test-user",
    });
  });

  it("surfaces ApiError on 401 invalid credentials", async () => {
    server.use(authHandlers.loginInvalidCredentials());

    const { result } = renderHook(() => useLogin(), { wrapper });

    result.current.mutate({ username: "wrong", password: "wrong" });

    await waitFor(() => {
      expect(result.current.isError).toBe(true);
    });
    expect(result.current.error).toBeDefined();
  });
});

describe("useRegister", () => {
  beforeEach(() => {
    setAccessToken(null);
  });

  it("calls /api/v1/auth/register, sets access token, and updates session cache on 201", async () => {
    server.use(authHandlers.registerSuccess());

    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );

    const { result } = renderHook(() => useRegister(), { wrapper });

    result.current.mutate({
      username: "newuser",
      password: "hunter22ab",
    });

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });

    expect(getAccessToken()).toBe("mock-access-token-jwt");
    expect(queryClient.getQueryData(["auth", "session"])).toBeDefined();
  });
});

describe("useLogout", () => {
  it("clears access token and session cache on settled (even if network fails)", async () => {
    server.use(
      http.post("*/api/v1/auth/logout", () =>
        HttpResponse.json({ status: 500 }, { status: 500 })
      )
    );

    setAccessToken("some-token");
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    queryClient.setQueryData(["auth", "session"], { id: 1, username: "test-user" });

    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );

    const { result } = renderHook(() => useLogout(), { wrapper });

    result.current.mutate();

    await waitFor(() => {
      // onSettled clears state regardless of network outcome.
      expect(getAccessToken()).toBeNull();
      expect(queryClient.getQueryData(["auth", "session"])).toBeNull();
    });
  });
});

describe("useForgotPassword", () => {
  it("resolves successfully (UI stub — no real backend call)", async () => {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );

    const { result } = renderHook(() => useForgotPassword(), { wrapper });

    result.current.mutate("user@example.com");

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });
  });
});
