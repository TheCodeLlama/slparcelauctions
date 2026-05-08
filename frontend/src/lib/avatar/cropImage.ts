import type { Area } from "react-easy-crop";

/**
 * Maximum edge length for the cropped avatar. We cap upload size by
 * downsampling the user's selection to 1024 px on its longest side
 * before encoding — anything bigger is wasted bytes since the backend
 * resamples to 64/128/256 PNGs anyway. Crops smaller than this are
 * encoded at their native size (no upsampling).
 */
const MAX_OUTPUT_SIZE = 1024;

/**
 * WebP quality factor for the cropped output. 0.85 hits the
 * sweet spot of "indistinguishable from lossless at avatar size" with
 * roughly 5-10x smaller wire bytes than PNG.
 */
const WEBP_QUALITY = 0.85;

/**
 * Crops {@code imageSrc} to the {@code areaPixels} rectangle returned
 * by react-easy-crop and re-renders into a WebP Blob via a 2D canvas.
 * The output is square and sized to {@code min(areaPixels.width, 1024)}
 * so we never upsample, never bloat the upload, and the backend can
 * always resample to its 64/128/256 derivatives.
 *
 * <p>Uses {@code crossOrigin = "anonymous"} on the source Image so
 * canvas tainting is avoided when the source is our backend-proxied SL
 * profile photo (same-origin) or a {@code blob:} URL from a file
 * upload (always canvas-safe).
 */
export async function getCroppedImg(imageSrc: string, areaPixels: Area): Promise<Blob> {
  const image = await loadImage(imageSrc);
  // areaPixels is the source-image rectangle the user framed; aspect=1
  // on the cropper guarantees width === height in practice.
  const outputSize = Math.min(Math.round(areaPixels.width), MAX_OUTPUT_SIZE);
  const canvas = document.createElement("canvas");
  canvas.width = outputSize;
  canvas.height = outputSize;
  const ctx = canvas.getContext("2d");
  if (!ctx) throw new Error("Canvas 2D context unavailable");
  ctx.drawImage(
    image,
    areaPixels.x,
    areaPixels.y,
    areaPixels.width,
    areaPixels.height,
    0,
    0,
    outputSize,
    outputSize,
  );
  return await new Promise<Blob>((resolve, reject) => {
    canvas.toBlob(
      (blob) => (blob ? resolve(blob) : reject(new Error("Canvas toBlob returned null"))),
      "image/webp",
      WEBP_QUALITY,
    );
  });
}

function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.crossOrigin = "anonymous";
    img.onload = () => resolve(img);
    img.onerror = () => reject(new Error(`Failed to load image: ${src}`));
    img.src = src;
  });
}
