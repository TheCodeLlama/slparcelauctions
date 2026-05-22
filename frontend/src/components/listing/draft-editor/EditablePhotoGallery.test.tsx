import { describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { server } from "@/test/msw/server";
import { applyDragEnd, EditablePhotoGallery } from "./EditablePhotoGallery";
import type { AuctionPhotoDto } from "@/types/auction";

const photos: AuctionPhotoDto[] = [
  {
    publicId: "p1",
    lightUrl: "/api/v1/photos/p1?variant=light",
    darkUrl: null,
    source: "SELLER_UPLOAD",
    sortOrder: 1,
  },
  {
    publicId: "p2",
    lightUrl: "/api/v1/photos/p2?variant=light",
    darkUrl: null,
    source: "SELLER_UPLOAD",
    sortOrder: 2,
  },
  {
    publicId: "p3",
    lightUrl: "/api/v1/photos/p3?variant=light",
    darkUrl: null,
    source: "SELLER_UPLOAD",
    sortOrder: 3,
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
        auctionPublicId="auction-1"
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
        auctionPublicId="auction-1"
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
        auctionPublicId="auction-1"
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

  it("does not show the Edit variants affordance on a SELLER_UPLOAD photo", () => {
    renderWithProviders(
      <EditablePhotoGallery
        auctionPublicId="auction-1"
        photos={photos}
        snapshotUrl={null}
        regionName="X"
        onReorder={vi.fn()}
        onDelete={vi.fn()}
        onAdd={vi.fn()}
      />,
    );
    expect(screen.queryByTestId("editable-photo-variants-p1")).toBeNull();
  });

  it("renders an Add dark badge on a default-cover photo with no dark variant", () => {
    const withCover: AuctionPhotoDto[] = [
      {
        publicId: "cover",
        lightUrl: "/api/v1/photos/cover?variant=light",
        darkUrl: null,
        source: "USER_DEFAULT_COVER",
        sortOrder: 0,
      },
      ...photos,
    ];
    renderWithProviders(
      <EditablePhotoGallery
        auctionPublicId="auction-1"
        photos={withCover}
        snapshotUrl={null}
        regionName="X"
        onReorder={vi.fn()}
        onDelete={vi.fn()}
        onAdd={vi.fn()}
      />,
    );
    const badge = screen.getByTestId("editable-photo-variants-cover");
    expect(badge).toBeInTheDocument();
    expect(badge).toHaveTextContent("Add dark");
  });

  it("renders a Light + Dark badge on a default-cover photo with a dark variant", () => {
    const withCover: AuctionPhotoDto[] = [
      {
        publicId: "cover",
        lightUrl: "/api/v1/photos/cover?variant=light",
        darkUrl: "/api/v1/photos/cover?variant=dark",
        source: "GROUP_DEFAULT_COVER",
        sortOrder: 0,
      },
      ...photos,
    ];
    renderWithProviders(
      <EditablePhotoGallery
        auctionPublicId="auction-1"
        photos={withCover}
        snapshotUrl={null}
        regionName="X"
        onReorder={vi.fn()}
        onDelete={vi.fn()}
        onAdd={vi.fn()}
      />,
    );
    expect(
      screen.getByTestId("editable-photo-variants-cover"),
    ).toHaveTextContent("Light + Dark");
  });

  it("opens the PhotoVariantsModal when the default-cover badge is clicked", async () => {
    server.use(
      http.get("*/api/v1/users/me", () =>
        HttpResponse.json({ publicId: "u1", displayName: "U" }),
      ),
    );
    const user = userEvent.setup();
    const withCover: AuctionPhotoDto[] = [
      {
        publicId: "cover",
        lightUrl: "/api/v1/photos/cover?variant=light",
        darkUrl: null,
        source: "USER_DEFAULT_COVER",
        sortOrder: 0,
      },
      ...photos,
    ];
    renderWithProviders(
      <EditablePhotoGallery
        auctionPublicId="auction-1"
        photos={withCover}
        snapshotUrl={null}
        regionName="X"
        onReorder={vi.fn()}
        onDelete={vi.fn()}
        onAdd={vi.fn()}
      />,
    );
    expect(screen.queryByTestId("photo-variants-modal")).toBeNull();
    await user.click(screen.getByTestId("editable-photo-variants-cover"));
    expect(
      await screen.findByTestId("photo-variants-modal"),
    ).toBeInTheDocument();
  });
});
