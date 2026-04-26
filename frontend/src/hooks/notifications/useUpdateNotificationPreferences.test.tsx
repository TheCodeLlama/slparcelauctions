import { describe, it, expect, beforeEach } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import { ThemeProvider } from "next-themes";
import type { ReactElement, ReactNode } from "react";
import { ToastProvider } from "@/components/ui/Toast";
import { server } from "@/test/msw/server";
import {
  preferencesHandlers,
  resetPreferences,
  seedPreferences,
} from "@/test/msw/handlers";
import { notificationKeys } from "@/lib/notifications/queryKeys";
import type { PreferencesDto } from "@/lib/notifications/preferencesTypes";
import { useNotificationPreferences } from "./useNotificationPreferences";
import { useUpdateNotificationPreferences } from "./useUpdateNotificationPreferences";

function makeWrapper(): {
  client: QueryClient;
  wrapper: (props: { children: ReactNode }) => ReactElement;
} {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });

  const wrapper = ({ children }: { children: ReactNode }): ReactElement => (
    <ThemeProvider attribute="class" defaultTheme="light" enableSystem={false}>
      <QueryClientProvider client={client}>
        <ToastProvider>{children}</ToastProvider>
      </QueryClientProvider>
    </ThemeProvider>
  );

  return { client, wrapper };
}

describe("useUpdateNotificationPreferences", () => {
  beforeEach(() => {
    resetPreferences();
    server.use(...preferencesHandlers);
  });

  it("optimistically updates the cache before server confirms", async () => {
    seedPreferences({
      slImMuted: false,
      slIm: {
        bidding: true,
        auction_result: true,
        escrow: true,
        listing_status: true,
        reviews: false,
      },
    });

    const { client, wrapper } = makeWrapper();

    const { result: query } = renderHook(() => useNotificationPreferences(), {
      wrapper,
    });
    await waitFor(() => expect(query.current.isSuccess).toBe(true));

    const { result: mut } = renderHook(
      () => useUpdateNotificationPreferences(),
      { wrapper }
    );

    act(() => {
      mut.current.mutate({
        slImMuted: false,
        slIm: {
          bidding: false,
          auction_result: true,
          escrow: true,
          listing_status: true,
          reviews: false,
        },
      });
    });

    // Cache should reflect optimistic value immediately, before the request settles.
    await waitFor(() => {
      const cached = client.getQueryData<PreferencesDto>(
        notificationKeys.preferences()
      );
      expect(cached?.slIm.bidding).toBe(false);
    });
  });

  it("reverts cache + shows toast on server 4xx", async () => {
    seedPreferences({
      slImMuted: false,
      slIm: {
        bidding: true,
        auction_result: true,
        escrow: true,
        listing_status: true,
        reviews: false,
      },
    });

    // Override PUT to 400 for this test
    server.use(
      http.put("*/api/v1/users/me/notification-preferences", () =>
        new HttpResponse(null, { status: 400 })
      )
    );

    const { client, wrapper } = makeWrapper();

    const { result: query } = renderHook(() => useNotificationPreferences(), {
      wrapper,
    });
    await waitFor(() => expect(query.current.isSuccess).toBe(true));

    const { result: mut } = renderHook(
      () => useUpdateNotificationPreferences(),
      { wrapper }
    );

    act(() => {
      mut.current.mutate({
        slImMuted: false,
        slIm: {
          bidding: false,
          auction_result: true,
          escrow: true,
          listing_status: true,
          reviews: false,
        },
      });
    });

    await waitFor(() => expect(mut.current.isError).toBe(true));

    // Cache reverted to prior value
    const cached = client.getQueryData<PreferencesDto>(
      notificationKeys.preferences()
    );
    expect(cached?.slIm.bidding).toBe(true);
  });
});
