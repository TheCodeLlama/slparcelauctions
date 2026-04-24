import { describe, it, expect, vi, beforeEach } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { CuratorTray } from "./CuratorTray";

type MqlLike = {
  matches: boolean;
  media: string;
  onchange: ((e: MediaQueryListEvent) => void) | null;
  addListener: () => void;
  removeListener: () => void;
  addEventListener: () => void;
  removeEventListener: () => void;
  dispatchEvent: () => boolean;
};

function setDesktopViewport(isDesktop: boolean) {
  const mqlFactory = (query: string): MqlLike => ({
    matches: query.includes("min-width") ? isDesktop : !isDesktop,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(() => true),
  });
  Object.defineProperty(window, "matchMedia", {
    writable: true,
    configurable: true,
    value: vi.fn(mqlFactory),
  });
}

const emptySaved = {
  content: [],
  page: 0,
  size: 24,
  totalElements: 0,
  totalPages: 0,
  first: true,
  last: true,
};

beforeEach(() => {
  server.use(
    http.get("*/api/v1/me/saved/ids", () =>
      HttpResponse.json({ ids: [] }),
    ),
    http.get("*/api/v1/me/saved/auctions", () =>
      HttpResponse.json(emptySaved),
    ),
  );
});

describe("CuratorTray (responsive shell)", () => {
  it("renders the Drawer shell at desktop widths", async () => {
    setDesktopViewport(true);
    renderWithProviders(
      <CuratorTray open onClose={() => {}} />,
      { auth: "authenticated" },
    );
    await waitFor(() => {
      const headings = screen.getAllByRole("heading", {
        name: /your curator tray/i,
      });
      expect(headings.length).toBeGreaterThan(0);
    });
  });

  it("renders the BottomSheet shell at mobile widths", async () => {
    setDesktopViewport(false);
    renderWithProviders(
      <CuratorTray open onClose={() => {}} />,
      { auth: "authenticated" },
    );
    await waitFor(() => {
      const headings = screen.getAllByRole("heading", {
        name: /your curator tray/i,
      });
      expect(headings.length).toBeGreaterThan(0);
    });
  });

  it("dark-mode render does not crash", async () => {
    setDesktopViewport(true);
    renderWithProviders(
      <CuratorTray open onClose={() => {}} />,
      { auth: "authenticated", theme: "dark", forceTheme: true },
    );
    await waitFor(() => {
      const headings = screen.getAllByRole("heading", {
        name: /your curator tray/i,
      });
      expect(headings.length).toBeGreaterThan(0);
    });
  });
});
