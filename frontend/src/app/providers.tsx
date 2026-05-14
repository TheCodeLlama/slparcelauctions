"use client";

import { ThemeProvider } from "next-themes";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState, type ReactNode } from "react";
import { configureApiClient } from "@/lib/api";
import { beginAuthBootstrap } from "@/lib/auth/session";
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
    // Open the auth-ready gate. Subsequent api.* calls await this until the
    // bootstrap query (useAuth -> bootstrapSession) resolves and calls
    // markAuthReady() in its finally. Before this gate existed, any useQuery
    // hook racing the bootstrap could send its first request with no
    // Authorization header, get the anonymous-view response from the
    // backend's privacy gate, and cache the wrong shape for the rest of the
    // session. Client-only — SSR fetches don't have access to the in-memory
    // token at all, so server-side requests proceed unguarded (the gate's
    // default-resolved state covers that path).
    if (typeof window !== "undefined") {
      beginAuthBootstrap();
    }
    return client;
  });

  return (
    <ThemeProvider attribute="data-theme" defaultTheme="light" enableSystem>
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
