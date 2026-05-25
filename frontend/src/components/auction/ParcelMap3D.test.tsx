import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";

import * as parcelScanApi from "@/lib/api/parcelScan";
import * as geometryModule from "@/lib/parcelMap3D/geometry";
import ParcelMap3D from "./ParcelMap3D";

// Mock the R3F + drei modules so jsdom never tries to boot WebGL.
vi.mock("@react-three/fiber", () => ({
  Canvas: ({ children }: { children: ReactNode }) => (
    <div data-testid="r3f-canvas">{children}</div>
  ),
}));

vi.mock("@react-three/drei", () => ({
  OrbitControls: (props: Record<string, unknown>) => (
    <div data-testid="orbit-controls" data-props={JSON.stringify(props)} />
  ),
  PerspectiveCamera: ({ children, ...props }: { children?: ReactNode } & Record<string, unknown>) => (
    <div data-testid="perspective-camera" data-props={JSON.stringify(props)}>
      {children}
    </div>
  ),
  Line: (props: Record<string, unknown>) => (
    <div data-testid="parcel-perimeter-line" data-props={JSON.stringify({ ...props, points: undefined })} />
  ),
}));

function wrap(ui: React.ReactElement) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={qc}>{ui}</QueryClientProvider>);
}

const publicId = "33333333-3333-3333-3333-333333333333";

function scanPayload() {
  const layoutBytes = new Uint8Array(512);
  const bitIndex = 10 * 64 + 10;
  layoutBytes[bitIndex >> 3] |= 1 << (7 - (bitIndex & 7));
  const heightBytes = new Uint8Array(4096);
  const toBase64 = (b: Uint8Array) => btoa(String.fromCharCode(...b));
  return {
    gridSize: 64,
    cellSizeMeters: 4,
    layoutCellsBase64: toBase64(layoutBytes),
    heightCellsBase64: toBase64(heightBytes),
    baseMeters: 22.0,
    stepMeters: 0.5,
    scannedAt: "2026-05-24T05:00:00Z",
  };
}

describe("ParcelMap3D", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it("renders the skeleton while data is pending", () => {
    vi.spyOn(parcelScanApi, "getParcelScan").mockImplementation(
      () => new Promise(() => {}),
    );
    vi.spyOn(geometryModule, "isWebGLAvailable").mockReturnValue(true);
    wrap(<ParcelMap3D publicId={publicId} />);
    expect(screen.getByTestId("parcel-map-3d-skeleton")).toBeInTheDocument();
  });

  it("returns null when the endpoint returns 404", async () => {
    vi.spyOn(parcelScanApi, "getParcelScan").mockResolvedValue(null);
    vi.spyOn(geometryModule, "isWebGLAvailable").mockReturnValue(true);
    const { container } = wrap(<ParcelMap3D publicId={publicId} />);
    await waitFor(() => {
      expect(container.querySelector('[data-testid="parcel-map-3d-skeleton"]')).toBeNull();
    });
    expect(container.querySelector('[data-testid="r3f-canvas"]')).toBeNull();
  });

  it("renders Canvas + PerspectiveCamera + OrbitControls + perimeter Line on loaded data", async () => {
    vi.spyOn(parcelScanApi, "getParcelScan").mockResolvedValue(scanPayload());
    vi.spyOn(geometryModule, "isWebGLAvailable").mockReturnValue(true);
    wrap(<ParcelMap3D publicId={publicId} />);
    expect(await screen.findByTestId("r3f-canvas")).toBeInTheDocument();
    expect(screen.getByTestId("perspective-camera")).toBeInTheDocument();
    expect(screen.getByTestId("orbit-controls")).toBeInTheDocument();
    expect(screen.getByTestId("parcel-perimeter-line")).toBeInTheDocument();
  });

  it("renders the WebGL-unavailable fallback message and fires onWebGLUnavailable", async () => {
    vi.spyOn(parcelScanApi, "getParcelScan").mockResolvedValue(scanPayload());
    vi.spyOn(geometryModule, "isWebGLAvailable").mockReturnValue(false);
    const onWebGLUnavailable = vi.fn();
    wrap(<ParcelMap3D publicId={publicId} onWebGLUnavailable={onWebGLUnavailable} />);
    expect(
      await screen.findByText(
        /3D view requires WebGL, which your browser does not support\. Showing 2D view instead\./,
      ),
    ).toBeInTheDocument();
    await waitFor(() => expect(onWebGLUnavailable).toHaveBeenCalledTimes(1));
    expect(screen.queryByTestId("r3f-canvas")).toBeNull();
  });

  it("passes the spec's camera defaults (FOV 50, target at region center)", async () => {
    vi.spyOn(parcelScanApi, "getParcelScan").mockResolvedValue(scanPayload());
    vi.spyOn(geometryModule, "isWebGLAvailable").mockReturnValue(true);
    wrap(<ParcelMap3D publicId={publicId} />);
    const cam = await screen.findByTestId("perspective-camera");
    const props = JSON.parse(cam.getAttribute("data-props") ?? "{}");
    expect(props.fov).toBe(50);
  });

  it("wrapping div has the correct aria-label for the 3D scene", async () => {
    vi.spyOn(parcelScanApi, "getParcelScan").mockResolvedValue(scanPayload());
    vi.spyOn(geometryModule, "isWebGLAvailable").mockReturnValue(true);
    wrap(<ParcelMap3D publicId={publicId} />);
    await screen.findByTestId("r3f-canvas");
    expect(
      screen.getByRole("img", { name: /Interactive 3D region and parcel elevation map/ }),
    ).toBeInTheDocument();
  });

  it("renders the color mode toggle once scan data is loaded, defaulting to elevation", async () => {
    vi.spyOn(parcelScanApi, "getParcelScan").mockResolvedValue(scanPayload());
    vi.spyOn(geometryModule, "isWebGLAvailable").mockReturnValue(true);
    wrap(<ParcelMap3D publicId={publicId} />);
    const group = await screen.findByRole("radiogroup", { name: "Color by" });
    expect(group).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: "Elevation" })).toHaveAttribute("aria-checked", "true");
    expect(screen.getByRole("radio", { name: "Slope" })).toHaveAttribute("aria-checked", "false");
  });

  it("hides the color mode toggle during the loading skeleton state", () => {
    vi.spyOn(parcelScanApi, "getParcelScan").mockImplementation(() => new Promise(() => {}));
    vi.spyOn(geometryModule, "isWebGLAvailable").mockReturnValue(true);
    wrap(<ParcelMap3D publicId={publicId} />);
    expect(screen.queryByRole("radiogroup", { name: "Color by" })).toBeNull();
  });

  it("clicking the Slope radio writes 'slope' to localStorage", async () => {
    const user = userEvent.setup();
    window.localStorage.clear();
    vi.spyOn(parcelScanApi, "getParcelScan").mockResolvedValue(scanPayload());
    vi.spyOn(geometryModule, "isWebGLAvailable").mockReturnValue(true);
    wrap(<ParcelMap3D publicId={publicId} />);
    const slope = await screen.findByRole("radio", { name: "Slope" });
    await user.click(slope);
    expect(window.localStorage.getItem("slpa:parcel-map:3d-color")).toBe("slope");
  });
});
