import { describe, it, expect } from "vitest";
import {
  decodeBase64ToBytes,
  isCellInParcel,
  decodeElevationCell,
} from "./encoding";

describe("decodeBase64ToBytes", () => {
  it("decodes the empty string to a zero-length array", () => {
    expect(decodeBase64ToBytes("")).toEqual(new Uint8Array(0));
  });

  it("round-trips a 4-byte payload", () => {
    // 0x80 0x40 0x20 0x10 -> base64 "gEAgEA=="
    expect(decodeBase64ToBytes("gEAgEA==")).toEqual(
      new Uint8Array([0x80, 0x40, 0x20, 0x10]),
    );
  });

  it("decodes a 512-byte zero payload to 512 zero bytes", () => {
    const zeros512 = btoa(String.fromCharCode(...new Uint8Array(512)));
    expect(decodeBase64ToBytes(zeros512).length).toBe(512);
    expect(decodeBase64ToBytes(zeros512).every((b) => b === 0)).toBe(true);
  });
});

describe("isCellInParcel", () => {
  it("reads bit 7 (MSB) of byte 0 as cell (0, 0)", () => {
    const cells = new Uint8Array(512);
    cells[0] = 0x80; // 1000_0000
    expect(isCellInParcel(cells, 0, 0)).toBe(true);
    expect(isCellInParcel(cells, 0, 1)).toBe(false);
  });

  it("reads bit 0 (LSB) of byte 0 as cell (0, 7)", () => {
    const cells = new Uint8Array(512);
    cells[0] = 0x01;
    expect(isCellInParcel(cells, 0, 7)).toBe(true);
    expect(isCellInParcel(cells, 0, 6)).toBe(false);
  });

  it("indexes byte (row * 8) for col 0..7", () => {
    const cells = new Uint8Array(512);
    cells[1 * 8] = 0x80; // row 1, col 0
    expect(isCellInParcel(cells, 1, 0)).toBe(true);
  });

  it("returns false for the last cell when its byte is zero", () => {
    const cells = new Uint8Array(512);
    expect(isCellInParcel(cells, 63, 63)).toBe(false);
  });
});

describe("decodeElevationCell", () => {
  it("returns base when cell byte is 0", () => {
    const cells = new Uint8Array(4096);
    expect(decodeElevationCell(cells, 0, 0, 22.0, 0.5)).toBeCloseTo(22.0, 6);
  });

  it("returns base + 255 * step when cell byte is 0xFF", () => {
    const cells = new Uint8Array(4096);
    cells[10 * 64 + 5] = 0xff;
    expect(decodeElevationCell(cells, 10, 5, 22.0, 0.5)).toBeCloseTo(
      22.0 + 255 * 0.5,
      6,
    );
  });

  it("treats the cell byte as unsigned (0xFF, not -1)", () => {
    const cells = new Uint8Array(4096);
    cells[0] = 0xff;
    expect(decodeElevationCell(cells, 0, 0, 0, 1)).toBeCloseTo(255, 6);
  });
});
