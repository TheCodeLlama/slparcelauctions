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

function makeBase64Zeros(byteLength: number): string {
  return btoa(String.fromCharCode(...new Uint8Array(byteLength)));
}

function payloadWithParcelAt(
  row: number,
  col: number,
  options: { landUseCellsBase64?: string | null } = {},
) {
  const layoutBytes = new Uint8Array(512);
  const bitIndex = row * 64 + col;
  layoutBytes[bitIndex >> 3] |= 1 << (7 - (bitIndex & 7));
  const heightBytes = new Uint8Array(4096); // all zero -> elev = base for every cell
  const toBase64 = (b: Uint8Array) => btoa(String.fromCharCode(...b));
  // Default landUseCells to a 4096-byte zero array unless the caller passes null
  // or an explicit value.
  const landUseCellsBase64 =
    "landUseCellsBase64" in options
      ? options.landUseCellsBase64
      : makeBase64Zeros(4096);
  return {
    gridSize: 64,
    cellSizeMeters: 4,
    layoutCellsBase64: toBase64(layoutBytes),
    heightCellsBase64: toBase64(heightBytes),
    baseMeters: 22.0,
    stepMeters: 0.5,
    scannedAt: "2026-05-24T04:57:31Z",
    landUseCellsBase64,
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
    const fig = screen.getByText(/Parcel covers 16 m/i);
    expect(fig).toBeInTheDocument();
    expect(fig.textContent).toMatch(/Elevation 22\.0 m to 22\.0 m/);
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
    // col 33 * 4 = 132 (x, east), row 32 * 4 = 128 (y, north)
    expect(live?.textContent).toMatch(/Position 132, 128/);
    expect(live?.textContent).toMatch(/in parcel/);
  });

  it("renders the Elevation/Land Use mode toggle below the legend", async () => {
    vi.spyOn(parcelScanApi, "getParcelScan").mockResolvedValue(
      payloadWithParcelAt(10, 10),
    );
    wrap(<ParcelMap publicId={publicId} />);
    await screen.findByRole("application");
    expect(screen.getByRole("radiogroup", { name: "Color by" })).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: "Elevation" })).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: "Land Use" })).toBeInTheDocument();
  });

  it("renders the Land Use option as aria-disabled when the response has null landUseCellsBase64", async () => {
    vi.spyOn(parcelScanApi, "getParcelScan").mockResolvedValue(
      payloadWithParcelAt(10, 10, { landUseCellsBase64: null }),
    );
    wrap(<ParcelMap publicId={publicId} />);
    await screen.findByRole("application");
    expect(screen.getByRole("radio", { name: "Land Use" }))
      .toHaveAttribute("aria-disabled", "true");
  });

  it("renders the elevation legend by default (Elevation mode)", async () => {
    vi.spyOn(parcelScanApi, "getParcelScan").mockResolvedValue(
      payloadWithParcelAt(10, 10),
    );
    wrap(<ParcelMap publicId={publicId} />);
    await screen.findByRole("application");
    // Elevation legend has a gradient bar; Land Use swatches are absent.
    expect(screen.queryByText("Listed")).toBeNull();
  });

  it("swaps to the Land Use legend when the toggle is set to Land Use", async () => {
    const user = userEvent.setup();
    vi.spyOn(parcelScanApi, "getParcelScan").mockResolvedValue(
      payloadWithParcelAt(10, 10),
    );
    wrap(<ParcelMap publicId={publicId} />);
    await screen.findByRole("application");
    await user.click(screen.getByRole("radio", { name: "Land Use" }));
    expect(screen.getByText("Listed")).toBeInTheDocument();
    expect(screen.getByText("Abandoned")).toBeInTheDocument();
    expect(screen.getByText("For Sale")).toBeInTheDocument();
    expect(screen.getByText("Protected")).toBeInTheDocument();
  });

  it("announces land-use category in Land Use mode on keyboard arrow nav", async () => {
    const user = userEvent.setup();
    // Build a payload where cell (1, 1) is encoded as Listed (1)
    const landUseCells = new Uint8Array(4096);
    landUseCells[1 * 64 + 1] = 1; // Listed
    const landUseBase64 = btoa(String.fromCharCode(...landUseCells));

    vi.spyOn(parcelScanApi, "getParcelScan").mockResolvedValue(
      payloadWithParcelAt(1, 1, { landUseCellsBase64: landUseBase64 }),
    );
    wrap(<ParcelMap publicId={publicId} />);
    const canvas = await screen.findByRole("application");

    // Switch to Land Use mode
    await user.click(screen.getByRole("radio", { name: "Land Use" }));

    // Focus the canvas and navigate to cell (1, 1) via arrow keys
    canvas.focus();
    await userEvent.keyboard("{ArrowDown}{ArrowDown}{ArrowDown}{ArrowDown}{ArrowDown}{ArrowDown}{ArrowDown}{ArrowDown}{ArrowDown}{ArrowDown}{ArrowDown}{ArrowDown}{ArrowDown}{ArrowDown}{ArrowDown}{ArrowDown}{ArrowDown}{ArrowDown}{ArrowDown}{ArrowDown}{ArrowDown}{ArrowDown}{ArrowDown}{ArrowDown}{ArrowDown}{ArrowDown}{ArrowDown}{ArrowDown}{ArrowDown}{ArrowDown}{ArrowDown}"); // 31 down arrows from row 32 to row 1
    await userEvent.keyboard("{ArrowLeft}{ArrowLeft}{ArrowLeft}{ArrowLeft}{ArrowLeft}{ArrowLeft}{ArrowLeft}{ArrowLeft}{ArrowLeft}{ArrowLeft}{ArrowLeft}{ArrowLeft}{ArrowLeft}{ArrowLeft}{ArrowLeft}{ArrowLeft}{ArrowLeft}{ArrowLeft}{ArrowLeft}{ArrowLeft}{ArrowLeft}{ArrowLeft}{ArrowLeft}{ArrowLeft}{ArrowLeft}{ArrowLeft}{ArrowLeft}{ArrowLeft}{ArrowLeft}{ArrowLeft}{ArrowLeft}"); // 31 left arrows from col 32 to col 1

    // The live region should announce the land-use category
    const liveRegion = screen
      .getAllByRole("status")
      .find((n) => n.getAttribute("aria-live") === "polite");
    expect(liveRegion?.textContent).toContain("Listed");
    expect(liveRegion?.textContent).toMatch(/\(4, 4\)/); // col 1 * 4 = 4, row 1 * 4 = 4
  });
});
