import { describe, expect, it, vi } from "vitest";

vi.mock("browser-image-compression", () => ({
  default: vi.fn(async (file: File) => {
    const blob = new Blob([new Uint8Array(8)], { type: file.type });
    return new File([blob], file.name, { type: file.type });
  }),
}));

import imageCompression from "browser-image-compression";
import { resizeImage } from "./resizeImage";

describe("resizeImage", () => {
  it("calls browser-image-compression with maxWidthOrHeight, useWebWorker, and the input MIME", async () => {
    const file = new File([new Uint8Array(16)], "x.jpg", { type: "image/jpeg" });

    await resizeImage(file, { maxDim: 2048 });

    expect(imageCompression).toHaveBeenCalledWith(file, {
      maxWidthOrHeight: 2048,
      useWebWorker: true,
      initialQuality: 0.85,
      fileType: "image/jpeg",
    });
  });

  it("preserves the input file name and type on the returned File", async () => {
    const file = new File([new Uint8Array(16)], "photo.png", { type: "image/png" });

    const out = await resizeImage(file, { maxDim: 1024 });

    expect(out.name).toBe("photo.png");
    expect(out.type).toBe("image/png");
  });
});
