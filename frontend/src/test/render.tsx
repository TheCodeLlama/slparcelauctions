// frontend/src/test/render.tsx
import { render, type RenderOptions } from "@testing-library/react";
import { ThemeProvider } from "next-themes";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useRef, type ReactElement, type ReactNode } from "react";
import { ToastProvider } from "@/components/ui/Toast";
import type { AuthUser } from "@/lib/auth/session";
import { mockUser } from "./msw/fixtures";

type AuthState = "authenticated" | "anonymous";

type WrapperOptions = {
  theme?: "light" | "dark";
  forceTheme?: boolean;
  auth?: AuthState;
  authUser?: AuthUser;
};

type RenderWithProvidersOptions = Omit<RenderOptions, "wrapper"> & WrapperOptions;

const SESSION_QUERY_KEY = ["auth", "session"] as const;

export function makeWrapper({
  theme = "light",
  forceTheme = false,
  auth,
  authUser = mockUser,
}: WrapperOptions = {}) {
  return function Wrapper({ children }: { children: ReactNode }) {
    const queryClientRef = useRef<QueryClient | null>(null);
    if (!queryClientRef.current) {
      const client = new QueryClient({
        defaultOptions: {
          queries: { retry: false },
          mutations: { retry: false },
        },
      });
      if (auth === "authenticated") {
        client.setQueryData(SESSION_QUERY_KEY, authUser);
      } else if (auth === "anonymous") {
        client.setQueryData(SESSION_QUERY_KEY, null);
      }
      // When auth is undefined (not provided), don't seed the cache at all.
      // This preserves the existing behavior for tests that rely on the
      // bootstrap query firing via MSW handlers.
      queryClientRef.current = client;
    }

    return (
      <ThemeProvider
        attribute="class"
        defaultTheme={theme}
        enableSystem={false}
        forcedTheme={forceTheme ? theme : undefined}
      >
        <QueryClientProvider client={queryClientRef.current}>
          <ToastProvider>{children}</ToastProvider>
        </QueryClientProvider>
      </ThemeProvider>
    );
  };
}

export function renderWithProviders(
  ui: ReactElement,
  { theme, forceTheme, auth, authUser, ...options }: RenderWithProvidersOptions = {}
) {
  return render(ui, {
    wrapper: makeWrapper({ theme, forceTheme, auth, authUser }),
    ...options,
  });
}

// Re-export RTL utilities so test files only import from one place.
export { screen, within, fireEvent, waitFor } from "@testing-library/react";
export { default as userEvent } from "@testing-library/user-event";
