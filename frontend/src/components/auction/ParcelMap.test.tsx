import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

import { ParcelMap } from "./ParcelMap";
import * as parcelScanApi from "@/lib/api/parcelScan";

const CANVAS_PX = 256;

function wrap(ui: React.ReactElement) {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(<QueryClientProvider client={qc}>{ui}</QueryClientProvider>);
}

const publicId = "22222222-2222-2222-2222-222222222222";

function payloadWithParcelAt(row: number, col: number) {
  const layoutBytes = new Uint8Array(512);
  const bitIndex = row * 64 + col;
  layoutBytes[bitIndex >> 3] |= 1 << (7 - (bitIndex & 7));
  const heightBytes = new Uint8Array(4096); // all zero -> elev = base for every cell
  const toBase64 = (b: Uint8Array) => btoa(String.fromCharCode(...b));
  return {
    gridSize: 64,
    cellSizeMeters: 4,
    layoutCellsBase64: toBase64(layoutBytes),
    heightCellsBase64: toBase64(heightBytes),
    baseMeters: 22.0,
    stepMeters: 0.5,
    scannedAt: "2026-05-24T04:57:31Z",
  };
}

describe("ParcelMap", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    // jsdom does not implement HTMLCanvasElement.getContext -- shim it so the
    // paint functions don't throw and tests can assert on DOM structure only.
    vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue({
      createImageData: () => ({
        data: new Uint8ClampedArray(CANVAS_PX * CANVAS_PX * 4),
        width: CANVAS_PX,
        height: CANVAS_PX,
        colorSpace: "srgb",
      }) as unknown as ImageData,
      putImageData: vi.fn(),
      fillRect: vi.fn(),
      strokeRect: vi.fn(),
      fillStyle: "",
      strokeStyle: "",
      lineWidth: 1,
    } as unknown as CanvasRenderingContext2D);
  });

  it("renders the skeleton while pending", () => {
    vi.spyOn(parcelScanApi, "getParcelScan").mockImplementation(
      () => new Promise(() => {}),
    );
    const { container } = wrap(<ParcelMap publicId={publicId} />);
    expect(container.querySelector(".animate-pulse")).not.toBeNull();
  });

  it("returns null when the endpoint returns 404", async () => {
    vi.spyOn(parcelScanApi, "getParcelScan").mockResolvedValue(null);
    const { container } = wrap(<ParcelMap publicId={publicId} />);
    await new Promise((r) => setTimeout(r, 0));
    expect(container.querySelector("canvas")).toBeNull();
    expect(container.querySelector("figcaption")).toBeNull();
  });

  it("renders the canvas + figcaption summary on loaded data", async () => {
    vi.spyOn(parcelScanApi, "getParcelScan").mockResolvedValue(
      payloadWithParcelAt(10, 10),
    );
    wrap(<ParcelMap publicId={publicId} />);
    expect(await screen.findByRole("application")).toBeInTheDocument();
    const fig = screen.getByText(/Parcel covers 1 of 4096 cells/i);
    expect(fig).toBeInTheDocument();
    expect(fig.textContent).toMatch(/Elevation range 22\.0 m to 22\.0 m/);
  });

  it("ArrowRight from initial focus (32, 32) moves to (32, 33) and updates the aria-live region", async () => {
    vi.spyOn(parcelScanApi, "getParcelScan").mockResolvedValue(
      payloadWithParcelAt(32, 33),
    );
    wrap(<ParcelMap publicId={publicId} />);
    const canvas = await screen.findByRole("application");
    canvas.focus();
    await userEvent.keyboard("{ArrowRight}");
    const live = screen
      .getAllByRole("status")
      .find((n) => n.getAttribute("aria-live") === "polite");
    expect(live?.textContent).toMatch(/Cell 32, 33/);
    expect(live?.textContent).toMatch(/in parcel/);
  });
});
