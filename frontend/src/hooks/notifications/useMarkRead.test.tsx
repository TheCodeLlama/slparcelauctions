import { describe, it, expect, beforeEach } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import type { ReactElement, ReactNode } from "react";
import { ThemeProvider } from "next-themes";
import { ToastProvider } from "@/components/ui/Toast";
import { server } from "@/test/msw/server";
import { notificationHandlers, seedNotification, clearNotifications } from "@/test/msw/handlers";
import { notificationKeys } from "@/lib/notifications/queryKeys";
import type { Page } from "@/types/page";
import type { NotificationDto } from "@/lib/notifications/types";
import { useMarkRead } from "./useMarkRead";

const SESSION_QUERY_KEY = ["auth", "session"] as const;

function makeWrapper(): {
  client: QueryClient;
  wrapper: (props: { children: ReactNode }) => ReactElement;
} {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  client.setQueryData(SESSION_QUERY_KEY, { id: 42, email: "test@example.com" });

  const wrapper = ({ children }: { children: ReactNode }): ReactElement => (
    <ThemeProvider attribute="class" defaultTheme="light" enableSystem={false}>
      <QueryClientProvider client={client}>
        <ToastProvider>{children}</ToastProvider>
      </QueryClientProvider>
    </ThemeProvider>
  );

  return { client, wrapper };
}

describe("useMarkRead", () => {
  beforeEach(() => {
    clearNotifications();
  });

  it("optimistically decrements unread count and flips read flag", async () => {
    const n = seedNotification({ read: false });
    server.use(...notificationHandlers);

    const { client, wrapper } = makeWrapper();

    // Pre-seed the query caches.
    const listKey = notificationKeys.list({});
    const countKey = notificationKeys.unreadCount();
    const initialList: Page<NotificationDto> = {
      content: [n],
      totalElements: 1,
      totalPages: 1,
      number: 0,
      size: 20,
    };
    client.setQueryData(listKey, initialList);
    client.setQueryData(countKey, { count: 1 });

    const { result } = renderHook(() => useMarkRead(), { wrapper });

    act(() => { result.current.mutate(n.id); });

    // Optimistic update fires synchronously inside mutate.
    await waitFor(() => {
      const list = client.getQueryData<Page<NotificationDto>>(listKey);
      expect(list?.content[0].read).toBe(true);
      const count = client.getQueryData<{ count: number }>(countKey);
      expect(count?.count).toBe(0);
    });
  });

  it("reverts optimistic update on server error", async () => {
    const n = seedNotification({ read: false });

    server.use(
      http.put("*/api/v1/notifications/:id/read", () =>
        new HttpResponse(null, { status: 500 })
      )
    );

    const { client, wrapper } = makeWrapper();

    const listKey = notificationKeys.list({});
    const countKey = notificationKeys.unreadCount();
    const initialList: Page<NotificationDto> = {
      content: [n],
      totalElements: 1,
      totalPages: 1,
      number: 0,
      size: 20,
    };
    client.setQueryData(listKey, initialList);
    client.setQueryData(countKey, { count: 1 });

    const { result } = renderHook(() => useMarkRead(), { wrapper });

    act(() => { result.current.mutate(n.id); });

    await waitFor(() => result.current.isError);

    // Count should be restored to 1.
    const count = client.getQueryData<{ count: number }>(countKey);
    expect(count?.count).toBe(1);
  });
});
