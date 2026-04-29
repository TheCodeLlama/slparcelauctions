import { describe, it, expect, beforeEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactElement, ReactNode } from "react";
import { ThemeProvider } from "next-themes";
import { ToastProvider } from "@/components/ui/Toast";
import { server } from "@/test/msw/server";
import { notificationHandlers, seedNotification, clearNotifications } from "@/test/msw/handlers";
import { mockVerifiedCurrentUser } from "@/test/msw/fixtures";
import { notificationKeys } from "@/lib/notifications/queryKeys";
import { useUnreadCount } from "./useUnreadCount";

const SESSION_QUERY_KEY = ["auth", "session"] as const;
const CURRENT_USER_KEY = ["currentUser"] as const;

function makeWrapper(unreadCount = 0): {
  client: QueryClient;
  wrapper: (props: { children: ReactNode }) => ReactElement;
} {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const user = { ...mockVerifiedCurrentUser, unreadNotificationCount: unreadCount };
  client.setQueryData(SESSION_QUERY_KEY, {
    id: user.id, email: user.email, displayName: user.displayName,
    slAvatarUuid: user.slAvatarUuid, verified: user.verified,
  });
  client.setQueryData(CURRENT_USER_KEY, user);

  const wrapper = ({ children }: { children: ReactNode }): ReactElement => (
    <ThemeProvider attribute="class" defaultTheme="light" enableSystem={false}>
      <QueryClientProvider client={client}>
        <ToastProvider>{children}</ToastProvider>
      </QueryClientProvider>
    </ThemeProvider>
  );

  return { client, wrapper };
}

describe("useUnreadCount", () => {
  beforeEach(() => {
    clearNotifications();
  });

  it("seeds initialData from /me unreadNotificationCount before fetch resolves", () => {
    server.use(...notificationHandlers);

    const { wrapper } = makeWrapper(5);
    const { result } = renderHook(() => useUnreadCount(), { wrapper });

    // initialData is available synchronously before the background fetch completes.
    expect(result.current.data?.count).toBe(5);
  });

  it("returns live count from server after background fetch completes", async () => {
    seedNotification({ read: false });
    seedNotification({ read: false });
    seedNotification({ read: true });
    server.use(...notificationHandlers);

    // Start with initialData=0 from /me; background fetch should return 2.
    const { client, wrapper } = makeWrapper(0);

    // Pre-seed the count cache as stale so the background refetch fires immediately.
    // We set updatedAt to 0 (epoch) to ensure staleTime check passes.
    client.setQueryData(notificationKeys.unreadCount(), { count: 0 }, { updatedAt: 0 });

    const { result } = renderHook(() => useUnreadCount(), { wrapper });

    // Wait for the background fetch to return the live server count.
    await waitFor(() => expect(result.current.data?.count).toBe(2), { timeout: 3000 });
  });
});
