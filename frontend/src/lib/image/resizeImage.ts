import imageCompression from "browser-image-compression";

export type ResizeOptions = {
  maxDim: number;
};

/**
 * Resize an image File so its longest edge is at most {@code maxDim} px.
 * Preserves aspect ratio, preserves the input MIME type, and strips EXIF
 * (including GPS / device metadata). Runs in a Web Worker so the main
 * thread keeps repainting during a multi-megapixel encode.
 *
 * <p>Returns a new {@link File} with the original name + type intact, so
 * the caller can hand it directly to {@code FormData.append("file", ...)}.
 */
export async function resizeImage(file: File, opts: ResizeOptions): Promise<File> {
  return imageCompression(file, {
    maxWidthOrHeight: opts.maxDim,
    useWebWorker: true,
    initialQuality: 0.85,
    fileType: file.type,
  });
}
