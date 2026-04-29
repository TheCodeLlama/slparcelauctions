"use client";

import { ThemeProvider } from "next-themes";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState, type ReactNode } from "react";
import { configureApiClient } from "@/lib/api";
import { ToastProvider } from "@/components/ui/Toast";
import { CuratorTrayMount } from "@/components/curator/CuratorTrayMount";
import { useNotificationStream } from "@/hooks/notifications/useNotificationStream";

function NotificationStreamMount() {
  useNotificationStream();
  return null;
}

export function Providers({ children }: { children: ReactNode }) {
  const [queryClient] = useState(() => {
    const client = new QueryClient({
      defaultOptions: {
        queries: {
          staleTime: 60_000,             // 1 min — sane default; per-query override allowed
          refetchOnWindowFocus: false,   // real-time updates come via WebSocket later
          retry: 1,                      // one automatic retry, then surface to caller
        },
      },
    });
    // Wire the API client to the QueryClient so the 401 interceptor can
    // update session cache state on failed refresh. See FOOTGUNS §F.3.
    configureApiClient(client);
    return client;
  });

  return (
    <ThemeProvider attribute="class" defaultTheme="dark" enableSystem>
      <QueryClientProvider client={queryClient}>
        <ToastProvider>
          {children}
          <CuratorTrayMount />
          <NotificationStreamMount />
        </ToastProvider>
      </QueryClientProvider>
    </ThemeProvider>
  );
}
