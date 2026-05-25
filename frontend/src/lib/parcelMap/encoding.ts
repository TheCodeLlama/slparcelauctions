/**
 * Pure binary decoders for the parcel-scan rasters returned by the
 * GET /api/v1/auctions/{publicId}/parcel-scan endpoint. Mirrors the
 * backend's encoding contract (see AuctionParcelLayout.java and
 * AuctionParcelHeightMap.java Javadoc). 64x64 cells, 4 m per cell.
 *
 * Layout bitmap: 1 bit per cell, MSB-first within each byte, row-major
 * SW-first (row 0 = south, col 0 = west). 4096 bits = 512 bytes.
 *
 * Heightmap: 4096 uint8s, same row-major SW-first. Decode:
 *   elevationMeters = baseMeters + (cells[i] & 0xFF) * stepMeters
 */

/** Decode a base64 string to a Uint8Array using the runtime's native atob. */
export function decodeBase64ToBytes(s: string): Uint8Array {
  if (s.length === 0) return new Uint8Array(0);
  const binary = atob(s);
  const out = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    out[i] = binary.charCodeAt(i);
  }
  return out;
}

/**
 * Returns true iff the cell at (row, col) belongs to the listed parcel.
 * row and col are 0..63 inclusive. The bitmap is MSB-first within each
 * byte; bit index = row*64 + col; byte index = floor(bitIndex / 8);
 * bit-in-byte position (from MSB) = 7 - (bitIndex % 8).
 */
export function isCellInParcel(
  layoutCells: Uint8Array,
  row: number,
  col: number,
): boolean {
  const bitIndex = row * 64 + col;
  const byteIndex = bitIndex >> 3;
  const bitInByte = 7 - (bitIndex & 7);
  return ((layoutCells[byteIndex] >> bitInByte) & 1) === 1;
}

/**
 * Decode the elevation at (row, col) in meters above sea level.
 * row and col are 0..63 inclusive.
 */
export function decodeElevationCell(
  heightCells: Uint8Array,
  row: number,
  col: number,
  baseMeters: number,
  stepMeters: number,
): number {
  return baseMeters + (heightCells[row * 64 + col] & 0xff) * stepMeters;
}

/**
 * Read a single land-use category byte at (row, col) from the 4096-byte
 * decoded payload. Row-major SW-first, same indexing as the height map.
 */
export function decodeLandUseCell(
  landUseCells: Uint8Array,
  row: number,
  col: number,
): number {
  return landUseCells[row * 64 + col] ?? 0;
}
