import { beforeEach, describe, expect, it, vi } from "vitest";
import { useState } from "react";
import {
  renderWithProviders,
  screen,
  userEvent,
} from "@/test/render";
import type { StagedPhoto } from "@/lib/listing/photoStaging";
import { PhotoUploader } from "./PhotoUploader";

// jsdom implements URL.createObjectURL as undefined. Stub it so stagePhoto
// doesn't throw when the test adds a file, and spy on revoke for lifecycle
// assertions.
beforeEach(() => {
  let counter = 0;
  URL.createObjectURL = vi.fn(() => `blob:mock-${counter++}`);
  URL.revokeObjectURL = vi.fn();
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

  it("flags an oversized image with an error overlay", async () => {
    renderWithProviders(<Harness />);
    const input = screen.getByTestId("drop-zone-input") as HTMLInputElement;
    // 3 MB — exceeds the 2 MB ceiling.
    const big = new File([new Uint8Array(3 * 1024 * 1024)], "big.png", {
      type: "image/png",
    });
    await userEvent.upload(input, big);
    const item = await screen.findByTestId("staged-photo");
    expect(item).toHaveAttribute("data-photo-error", "true");
    expect(screen.getByText(/2 MB or smaller/i)).toBeInTheDocument();
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
