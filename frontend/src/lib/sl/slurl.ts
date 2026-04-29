/**
 * Second Life URL helpers. Two distinct formats serve two distinct clients:
 *
 *   - Viewer protocol: {@code secondlife:///app/teleport/{region}/{x}/{y}/{z}}
 *     Handed to the OS protocol handler, which forwards to the SL viewer.
 *     The viewer accepts raw spaces in the region segment (and some edge
 *     builds actively reject percent-encoded spaces), so the region name is
 *     NOT encoded for this path — other URL-unsafe characters are harmless
 *     because the string never reaches an HTTP stack.
 *
 *   - Map URL: {@code https://maps.secondlife.com/secondlife/{region}/{x}/{y}/{z}}
 *     Web resource served over HTTPS, so the region MUST be
 *     {@code encodeURIComponent}-ed to survive the browser's URL parser
 *     (spaces, apostrophes, unicode all become percent-escapes).
 *
 * Null / zero positions fall back to the region-centre convention
 * {@code (128, 128, 0)}. SL's sim grid is 256m wide; 128/128 is the
 * geometric centre, which every viewer maps to a "generic landing" if the
 * actual parcel coords are unknown. Positive Z defaults stay 0 because
 * in-world terrain height varies wildly — the viewer resolves ground
 * altitude on arrival when Z is 0.
 */

function resolvePosition(value: number | null | undefined): number {
  if (value == null || value === 0) return 128;
  return value;
}

function resolveZ(value: number | null | undefined): number {
  if (value == null) return 0;
  return value;
}

export function viewerProtocolUrl(
  regionName: string,
  x: number | null | undefined,
  y: number | null | undefined,
  z: number | null | undefined,
): string {
  const rx = resolvePosition(x);
  const ry = resolvePosition(y);
  const rz = resolveZ(z);
  // Region kept raw — see module-level doc. The viewer accepts spaces and
  // other URL-unsafe characters unescaped and is inconsistent about
  // decoding percent-escapes across builds.
  return `secondlife:///app/teleport/${regionName}/${rx}/${ry}/${rz}`;
}

export function mapUrl(
  regionName: string,
  x: number | null | undefined,
  y: number | null | undefined,
  z: number | null | undefined,
): string {
  const rx = resolvePosition(x);
  const ry = resolvePosition(y);
  const rz = resolveZ(z);
  return `https://maps.secondlife.com/secondlife/${encodeURIComponent(regionName)}/${rx}/${ry}/${rz}`;
}
