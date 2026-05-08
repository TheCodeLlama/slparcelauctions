import { describe, expect, it, vi } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { applyDragEnd, EditablePhotoGallery } from "./EditablePhotoGallery";
import type { AuctionPhotoDto } from "@/types/auction";

const photos: AuctionPhotoDto[] = [
  {
    publicId: "p1",
    url: "/api/v1/photos/p1",
    contentType: "image/webp",
    sizeBytes: 1,
    sortOrder: 1,
    uploadedAt: "x",
  },
  {
    publicId: "p2",
    url: "/api/v1/photos/p2",
    contentType: "image/webp",
    sizeBytes: 1,
    sortOrder: 2,
    uploadedAt: "x",
  },
  {
    publicId: "p3",
    url: "/api/v1/photos/p3",
    contentType: "image/webp",
    sizeBytes: 1,
    sortOrder: 3,
    uploadedAt: "x",
  },
];

describe("applyDragEnd", () => {
  it("moves last to first", () => {
    const next = applyDragEnd(photos, "p3", "p1");
    expect(next).toEqual(["p3", "p1", "p2"]);
  });

  it("returns null when activeId === overId", () => {
    expect(applyDragEnd(photos, "p1", "p1")).toBeNull();
  });

  it("returns null when overId is null", () => {
    expect(applyDragEnd(photos, "p1", null)).toBeNull();
  });

  it("moves middle to end", () => {
    const next = applyDragEnd(photos, "p2", "p3");
    expect(next).toEqual(["p1", "p3", "p2"]);
  });

  it("returns null when activeId is not found", () => {
    expect(applyDragEnd(photos, "stray", "p1")).toBeNull();
  });
});

describe("EditablePhotoGallery", () => {
  it("renders one tile per photo with delete + drag affordances", () => {
    renderWithProviders(
      <EditablePhotoGallery
        photos={photos}
        snapshotUrl={null}
        regionName="X"
        onReorder={vi.fn()}
        onDelete={vi.fn()}
        onAdd={vi.fn()}
      />,
    );
    expect(screen.getByTestId("editable-photo-tile-p1")).toBeInTheDocument();
    expect(screen.getByLabelText("Remove photo p1")).toBeInTheDocument();
    expect(
      screen.getByLabelText("Photo p1, drag to reorder"),
    ).toBeInTheDocument();
  });

  it("delete button opens confirm modal, confirm calls onDelete", async () => {
    const user = userEvent.setup();
    const onDelete = vi.fn().mockResolvedValue(undefined);
    renderWithProviders(
      <EditablePhotoGallery
        photos={photos}
        snapshotUrl={null}
        regionName="X"
        onReorder={vi.fn()}
        onDelete={onDelete}
        onAdd={vi.fn()}
      />,
    );
    await user.click(screen.getByLabelText("Remove photo p2"));
    await user.click(
      await screen.findByTestId("editable-photo-gallery-confirm-delete"),
    );
    expect(onDelete).toHaveBeenCalledWith("p2");
  });

  it("Add tile is hidden when at MAX_PHOTOS", () => {
    const tenPhotos = Array.from({ length: 10 }, (_, i) => ({
      ...photos[0],
      publicId: `p${i + 1}`,
      sortOrder: i + 1,
    })) as AuctionPhotoDto[];
    renderWithProviders(
      <EditablePhotoGallery
        photos={tenPhotos}
        snapshotUrl={null}
        regionName="X"
        onReorder={vi.fn()}
        onDelete={vi.fn()}
        onAdd={vi.fn()}
      />,
    );
    expect(screen.queryByTestId("editable-photo-gallery-add")).toBeNull();
  });
});
