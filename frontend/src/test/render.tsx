// frontend/src/test/render.tsx
import { render, type RenderOptions } from "@testing-library/react";
import { ThemeProvider } from "next-themes";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactElement, ReactNode } from "react";

type RenderWithProvidersOptions = Omit<RenderOptions, "wrapper"> & {
  /** Initial theme. Default "light" for snapshot stability. */
  theme?: "light" | "dark";
  /**
   * If true, locks the theme via `forcedTheme` so `setTheme()` becomes
   * a no-op. Defaults to false so tests can observe theme transitions.
   */
  forceTheme?: boolean;
};

function makeWrapper(theme: "light" | "dark", force: boolean) {
  return function Wrapper({ children }: { children: ReactNode }) {
    const queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    });

    return (
      <ThemeProvider
        attribute="class"
        defaultTheme={theme}
        enableSystem={false}
        forcedTheme={force ? theme : undefined}
      >
        <QueryClientProvider client={queryClient}>
          {children}
        </QueryClientProvider>
      </ThemeProvider>
    );
  };
}

export function renderWithProviders(
  ui: ReactElement,
  { theme = "light", forceTheme = false, ...options }: RenderWithProvidersOptions = {}
) {
  return render(ui, { wrapper: makeWrapper(theme, forceTheme), ...options });
}

// Re-export RTL utilities so test files only import from one place.
export { screen, within, fireEvent, waitFor } from "@testing-library/react";
export { default as userEvent } from "@testing-library/user-event";
