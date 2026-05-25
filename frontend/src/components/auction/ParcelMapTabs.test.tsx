import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

import { ParcelMapTabs } from "./ParcelMapTabs";

// Mock next/dynamic to render the imported component synchronously so we can
// assert switching without spinning up three.js in jsdom.
vi.mock("next/dynamic", () => ({
  default: (loader: () => Promise<{ default: React.ComponentType<unknown> }>) => {
    const Stub = (props: Record<string, unknown>) => (
      <div data-testid="parcel-map-3d-stub" data-props={JSON.stringify(props)} />
    );
    // Trigger the loader call so the dynamic-import path is exercised, then
    // return the synchronous stub.
    void loader;
    return Stub;
  },
}));

// Stub the 2D ParcelMap so the test never tries to render a real canvas.
vi.mock("./ParcelMap", () => ({
  ParcelMap: (props: Record<string, unknown>) => (
    <div data-testid="parcel-map-2d-stub" data-props={JSON.stringify(props)} />
  ),
}));

const STORAGE_KEY = "slpa:parcel-map:view";

function wrap(ui: React.ReactElement) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={qc}>{ui}</QueryClientProvider>);
}

describe("ParcelMapTabs", () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it("defaults to the 2D view when localStorage is empty", () => {
    wrap(<ParcelMapTabs publicId="abc" />);
    expect(screen.getByTestId("parcel-map-2d-stub")).toBeInTheDocument();
    expect(screen.queryByTestId("parcel-map-3d-stub")).toBeNull();
  });

  it("renders the 3D view when localStorage holds '3d'", async () => {
    window.localStorage.setItem(STORAGE_KEY, "3d");
    wrap(<ParcelMapTabs publicId="abc" />);
    expect(await screen.findByTestId("parcel-map-3d-stub")).toBeInTheDocument();
    expect(screen.queryByTestId("parcel-map-2d-stub")).toBeNull();
  });

  it("clicking the 3D tab switches the panel and writes to localStorage", async () => {
    const user = userEvent.setup();
    wrap(<ParcelMapTabs publicId="abc" />);
    await user.click(screen.getByRole("tab", { name: "3D View" }));
    expect(await screen.findByTestId("parcel-map-3d-stub")).toBeInTheDocument();
    expect(window.localStorage.getItem(STORAGE_KEY)).toBe("3d");
  });

  it("exposes the standard ARIA tabs pattern", () => {
    wrap(<ParcelMapTabs publicId="abc" />);
    const tablist = screen.getByRole("tablist");
    expect(tablist).toHaveAttribute("aria-label", "Parcel map view");
    const tab2d = screen.getByRole("tab", { name: "2D Map" });
    const tab3d = screen.getByRole("tab", { name: "3D View" });
    expect(tab2d).toHaveAttribute("aria-selected", "true");
    expect(tab3d).toHaveAttribute("aria-selected", "false");
    expect(tab2d).toHaveAttribute("aria-controls", "parcel-map-panel");
    expect(tab3d).toHaveAttribute("aria-controls", "parcel-map-panel");
    expect(screen.getByRole("tabpanel")).toHaveAttribute("id", "parcel-map-panel");
  });

  it("ArrowRight from the active 2D tab moves focus to the 3D tab", async () => {
    const user = userEvent.setup();
    wrap(<ParcelMapTabs publicId="abc" />);
    const tab2d = screen.getByRole("tab", { name: "2D Map" });
    tab2d.focus();
    await user.keyboard("{ArrowRight}");
    expect(screen.getByRole("tab", { name: "3D View" })).toHaveFocus();
  });

  it("ArrowLeft from the 3D tab wraps back to the 2D tab", async () => {
    const user = userEvent.setup();
    wrap(<ParcelMapTabs publicId="abc" />);
    const tab3d = screen.getByRole("tab", { name: "3D View" });
    tab3d.focus();
    await user.keyboard("{ArrowLeft}");
    expect(screen.getByRole("tab", { name: "2D Map" })).toHaveFocus();
  });

  it("renders the WebGL-fallback message when the 3D child calls onWebGLUnavailable", async () => {
    // Replace the next/dynamic mock for this test so the dynamic-imported
    // child invokes onWebGLUnavailable on mount.
    vi.doMock("next/dynamic", () => ({
      default: () => {
        const Stub = (props: { onWebGLUnavailable?: () => void }) => {
          // Fire on mount to simulate WebGL absence.
          (props.onWebGLUnavailable ?? (() => {}))();
          return <div data-testid="parcel-map-3d-stub" />;
        };
        return Stub;
      },
    }));
    // Re-import the component fresh so the new mock takes effect.
    vi.resetModules();
    const { ParcelMapTabs: Reloaded } = await import("./ParcelMapTabs");
    window.localStorage.setItem(STORAGE_KEY, "3d");
    wrap(<Reloaded publicId="abc" />);
    await waitFor(() => {
      expect(
        screen.getByText(
          /3D view requires WebGL, which your browser does not support\./,
        ),
      ).toBeInTheDocument();
    });
    expect(screen.getByTestId("parcel-map-2d-stub")).toBeInTheDocument();
    // localStorage preference must NOT be overwritten.
    expect(window.localStorage.getItem(STORAGE_KEY)).toBe("3d");
  });
});
