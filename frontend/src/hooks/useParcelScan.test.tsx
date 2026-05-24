import { describe, it, expect } from "vitest";
import { http, HttpResponse } from "msw";
import { renderHook, waitFor } from "@testing-library/react";
import { makeWrapper } from "@/test/render";
import { server } from "@/test/msw/server";
import type { ParcelScanResponse } from "@/types/auction";
import { useParcelScan, parcelScanKey } from "./useParcelScan";

const publicId = "11111111-1111-1111-1111-111111111111";

const mockScan: ParcelScanResponse = {
  gridSize: 64,
  cellSizeMeters: 4,
  layoutCellsBase64: "AAAA",
  heightCellsBase64: "BBBB",
  baseMeters: 22.5,
  stepMeters: 0.5,
  scannedAt: "2026-05-24T04:57:31Z",
};

describe("useParcelScan", () => {
  it("returns the scan payload on 200", async () => {
    server.use(
      http.get("*/api/v1/auctions/:id/parcel-scan", () =>
        HttpResponse.json(mockScan),
      ),
    );

    const { result } = renderHook(() => useParcelScan(publicId), {
      wrapper: makeWrapper(),
    });

    await waitFor(() => expect(result.current.isPending).toBe(false));
    expect(result.current.data).toEqual(mockScan);
    expect(result.current.isError).toBe(false);
  });

  it("returns data: null on 404", async () => {
    server.use(
      http.get("*/api/v1/auctions/:id/parcel-scan", () =>
        HttpResponse.json(
          { status: 404, code: "PARCEL_SCAN_NOT_FOUND" },
          { status: 404 },
        ),
      ),
    );

    const { result } = renderHook(() => useParcelScan(publicId), {
      wrapper: makeWrapper(),
    });

    await waitFor(() => expect(result.current.isPending).toBe(false));
    expect(result.current.data).toBeNull();
    expect(result.current.isError).toBe(false);
  });

  it("surfaces isError on non-404 failures", async () => {
    server.use(
      http.get("*/api/v1/auctions/:id/parcel-scan", () =>
        HttpResponse.json({ status: 500 }, { status: 500 }),
      ),
    );

    const { result } = renderHook(() => useParcelScan(publicId), {
      wrapper: makeWrapper(),
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.data).toBeUndefined();
  });
});

describe("parcelScanKey", () => {
  it("produces stable, auction-scoped cache keys", () => {
    expect(parcelScanKey(publicId)).toEqual([
      "auction",
      publicId,
      "parcel-scan",
    ]);
  });

  it("differentiates by publicId", () => {
    const other = "22222222-2222-2222-2222-222222222222";
    expect(parcelScanKey(publicId)).not.toEqual(parcelScanKey(other));
  });
});
