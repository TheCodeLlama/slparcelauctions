import { beforeEach, describe, expect, it, vi } from "vitest";
import { useState } from "react";
import {
  renderWithProviders,
  screen,
  userEvent,
} from "@/test/render";
import type { StagedPhoto } from "@/lib/listing/photoStaging";

vi.mock("@/lib/image/resizeImage", () => ({
  resizeImage: vi.fn(async (file: File) => file),
}));

import { resizeImage } from "@/lib/image/resizeImage";
import { applyStagedDragEnd, PhotoUploader } from "./PhotoUploader";

// jsdom implements URL.createObjectURL as undefined. Stub it so stagePhoto
// doesn't throw when the test adds a file, and spy on revoke for lifecycle
// assertions.
beforeEach(() => {
  let counter = 0;
  URL.createObjectURL = vi.fn(() => `blob:mock-${counter++}`);
  URL.revokeObjectURL = vi.fn();
  vi.mocked(resizeImage).mockClear();
});

function Harness({ initial = [] as StagedPhoto[], maxPhotos = 10 }) {
  const [staged, setStaged] = useState<StagedPhoto[]>(initial);
  return (
    <PhotoUploader
      staged={staged}
      onStagedChange={setStaged}
      maxPhotos={maxPhotos}
    />
  );
}

describe("PhotoUploader", () => {
  it("stages an accepted image when selected via the picker", async () => {
    renderWithProviders(<Harness />);
    const input = screen.getByTestId("drop-zone-input") as HTMLInputElement;
    const file = new File(["x"], "photo.png", { type: "image/png" });
    await userEvent.upload(input, file);
    const items = await screen.findAllByTestId("staged-photo");
    expect(items).toHaveLength(1);
    expect(items[0]).not.toHaveAttribute("data-photo-error", "true");
    expect(screen.getByText("staged")).toBeInTheDocument();
  });

  it("calls resizeImage with maxDim 2048 before staging an accepted image", async () => {
    renderWithProviders(<Harness />);
    const input = screen.getByTestId("drop-zone-input") as HTMLInputElement;
    const file = new File(["x"], "photo.png", { type: "image/png" });
    await userEvent.upload(input, file);
    await screen.findByTestId("staged-photo");
    expect(resizeImage).toHaveBeenCalledWith(file, { maxDim: 2048 });
  });

  it("removes a staged photo when the trash button is clicked", async () => {
    renderWithProviders(<Harness />);
    const input = screen.getByTestId("drop-zone-input") as HTMLInputElement;
    const file = new File(["x"], "photo.png", { type: "image/png" });
    await userEvent.upload(input, file);
    await screen.findByTestId("staged-photo");
    await userEvent.click(
      screen.getByRole("button", { name: /Remove photo/i }),
    );
    expect(screen.queryByTestId("staged-photo")).toBeNull();
  });

  it("disables the drop zone once the cap is reached", async () => {
    renderWithProviders(<Harness maxPhotos={1} />);
    const input = screen.getByTestId("drop-zone-input") as HTMLInputElement;
    const file = new File(["x"], "photo.png", { type: "image/png" });
    await userEvent.upload(input, file);
    await screen.findByTestId("staged-photo");
    const inputAfter = screen.getByTestId(
      "drop-zone-input",
    ) as HTMLInputElement;
    expect(inputAfter).toBeDisabled();
    expect(screen.getByText(/Maximum 1 photos reached/)).toBeInTheDocument();
  });
});

describe("applyStagedDragEnd", () => {
  function staged(id: string): StagedPhoto {
    return {
      id,
      file: new File(["x"], `${id}.png`, { type: "image/png" }),
      objectUrl: `blob:${id}`,
      error: null,
      uploadedPhotoId: null,
    };
  }

  it("moves last to first", () => {
    const next = applyStagedDragEnd(
      [staged("a"), staged("b"), staged("c")],
      "c",
      "a",
    );
    expect(next?.map((p) => p.id)).toEqual(["c", "a", "b"]);
  });

  it("returns null when activeId === overId", () => {
    expect(
      applyStagedDragEnd([staged("a"), staged("b")], "a", "a"),
    ).toBeNull();
  });

  it("returns null when overId is null", () => {
    expect(
      applyStagedDragEnd([staged("a"), staged("b")], "a", null),
    ).toBeNull();
  });

  it("moves middle to end", () => {
    const next = applyStagedDragEnd(
      [staged("a"), staged("b"), staged("c")],
      "b",
      "c",
    );
    expect(next?.map((p) => p.id)).toEqual(["a", "c", "b"]);
  });
});
