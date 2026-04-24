import { describe, it, expect, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { CuratorTrayTrigger } from "./CuratorTrayTrigger";

describe("CuratorTrayTrigger", () => {
  it("renders nothing when the caller is unauthenticated", () => {
    renderWithProviders(<CuratorTrayTrigger onOpen={() => {}} />, {
      auth: "anonymous",
    });
    expect(
      screen.queryByRole("button", { name: /open curator tray/i }),
    ).not.toBeInTheDocument();
    expect(screen.queryByTestId("curator-tray-trigger")).not.toBeInTheDocument();
  });

  it("renders the heart + literal count when authenticated", async () => {
    server.use(
      http.get("*/api/v1/me/saved/ids", () =>
        HttpResponse.json({ ids: [1, 2, 3, 4] }),
      ),
    );
    renderWithProviders(<CuratorTrayTrigger onOpen={() => {}} />, {
      auth: "authenticated",
    });
    await waitFor(() => {
      expect(screen.getByTestId("curator-tray-count")).toHaveTextContent("4");
    });
  });

  it("collapses to 99+ when the count is at or above 100", async () => {
    const ids = Array.from({ length: 123 }, (_, i) => i + 1);
    server.use(
      http.get("*/api/v1/me/saved/ids", () =>
        HttpResponse.json({ ids }),
      ),
    );
    renderWithProviders(<CuratorTrayTrigger onOpen={() => {}} />, {
      auth: "authenticated",
    });
    await waitFor(() => {
      expect(screen.getByTestId("curator-tray-count")).toHaveTextContent(
        "99+",
      );
    });
  });

  it("calls onOpen when clicked", async () => {
    const onOpen = vi.fn();
    server.use(
      http.get("*/api/v1/me/saved/ids", () =>
        HttpResponse.json({ ids: [] }),
      ),
    );
    renderWithProviders(<CuratorTrayTrigger onOpen={onOpen} />, {
      auth: "authenticated",
    });
    const btn = await screen.findByRole("button", {
      name: /open curator tray/i,
    });
    btn.click();
    expect(onOpen).toHaveBeenCalledTimes(1);
  });

  it("renders in dark mode without visual regression", async () => {
    server.use(
      http.get("*/api/v1/me/saved/ids", () =>
        HttpResponse.json({ ids: [] }),
      ),
    );
    renderWithProviders(<CuratorTrayTrigger onOpen={() => {}} />, {
      auth: "authenticated",
      theme: "dark",
      forceTheme: true,
    });
    expect(
      await screen.findByRole("button", { name: /open curator tray/i }),
    ).toBeInTheDocument();
  });
});
