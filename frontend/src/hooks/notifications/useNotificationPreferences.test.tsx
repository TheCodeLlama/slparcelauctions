import { describe, it, expect, beforeEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "next-themes";
import type { ReactElement, ReactNode } from "react";
import { ToastProvider } from "@/components/ui/Toast";
import { server } from "@/test/msw/server";
import {
  preferencesHandlers,
  resetPreferences,
  seedPreferences,
} from "@/test/msw/handlers";
import { useNotificationPreferences } from "./useNotificationPreferences";

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

describe("useNotificationPreferences", () => {
  beforeEach(() => {
    resetPreferences();
    server.use(...preferencesHandlers);
  });

  it("fetches preferences from the GET endpoint", async () => {
    seedPreferences({
      slImMuted: false,
      slIm: {
        bidding: true,
        auction_result: true,
        escrow: true,
        listing_status: false,
        reviews: false,
      },
    });

    const { wrapper } = makeWrapper();
    const { result } = renderHook(() => useNotificationPreferences(), {
      wrapper,
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(result.current.data).toEqual({
      slImMuted: false,
      slIm: {
        bidding: true,
        auction_result: true,
        escrow: true,
        listing_status: false,
        reviews: false,
      },
    });
  });

  it("returns reviews=false by default", async () => {
    const { wrapper } = makeWrapper();
    const { result } = renderHook(() => useNotificationPreferences(), {
      wrapper,
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.slIm.reviews).toBe(false);
  });
});
