import { describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, userEvent, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import type { AuctionPhotoDto } from "@/types/auction";

vi.mock("@/lib/image/resizeImage", () => ({
  resizeImage: vi.fn(async (file: File) => file),
}));

import { resizeImage } from "@/lib/image/resizeImage";
import { PhotoVariantsModal } from "./PhotoVariantsModal";

const AUCTION_ID = "auction-1";

function coverPhoto(overrides: Partial<AuctionPhotoDto> = {}): AuctionPhotoDto {
  return {
    publicId: "ph-1",
    lightUrl: "/api/v1/photos/ph-1?variant=light",
    darkUrl: null,
    source: "USER_DEFAULT_COVER",
    sortOrder: 0,
    ...overrides,
  };
}

describe("PhotoVariantsModal", () => {
  it("renders both the light and dark slots", () => {
    renderWithProviders(
      <PhotoVariantsModal
        open
        onClose={vi.fn()}
        auctionPublicId={AUCTION_ID}
        photo={coverPhoto()}
      />,
    );
    expect(
      screen.getByTestId("photo-variants-light-slot"),
    ).toBeInTheDocument();
    expect(screen.getByTestId("photo-variants-dark-slot")).toBeInTheDocument();
  });

  it("renders the light slot as read-only with the explainer note and no upload control", () => {
    renderWithProviders(
      <PhotoVariantsModal
        open
        onClose={vi.fn()}
        auctionPublicId={AUCTION_ID}
        photo={coverPhoto()}
      />,
    );
    const note = screen.getByTestId("photo-variants-light-note");
    expect(note).toHaveTextContent(
      "Edit your profile default cover to change this",
    );
    // The light slot has no upload / delete affordance of its own.
    const lightSlot = screen.getByTestId("photo-variants-light-slot");
    expect(lightSlot.querySelector("button")).toBeNull();
    expect(lightSlot.querySelector("input")).toBeNull();
  });

  it("shows an upload drop zone when the dark variant is empty and uploads on pick", async () => {
    let uploadHit = false;
    server.use(
      http.post(
        `*/api/v1/auctions/${AUCTION_ID}/photos/ph-1/dark`,
        () => {
          uploadHit = true;
          return HttpResponse.json(
            coverPhoto({ darkUrl: "/api/v1/photos/ph-1?variant=dark" }),
          );
        },
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(
      <PhotoVariantsModal
        open
        onClose={vi.fn()}
        auctionPublicId={AUCTION_ID}
        photo={coverPhoto()}
      />,
    );
    expect(
      screen.getByTestId("photo-variants-dark-dropzone"),
    ).toBeInTheDocument();

    const input = screen.getByTestId(
      "photo-variants-dark-input",
    ) as HTMLInputElement;
    const file = new File([new Uint8Array(8)], "dark.webp", {
      type: "image/webp",
    });
    await user.upload(input, file);

    await waitFor(() => {
      expect(resizeImage).toHaveBeenCalledWith(file, { maxDim: 2048 });
    });
    await waitFor(() => {
      expect(uploadHit).toBe(true);
    });
  });

  it("shows Replace + Delete when the dark variant is set and deletes on click", async () => {
    let deleteHit = false;
    server.use(
      http.delete(
        `*/api/v1/auctions/${AUCTION_ID}/photos/ph-1/dark`,
        () => {
          deleteHit = true;
          return HttpResponse.json(coverPhoto({ darkUrl: null }));
        },
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(
      <PhotoVariantsModal
        open
        onClose={vi.fn()}
        auctionPublicId={AUCTION_ID}
        photo={coverPhoto({ darkUrl: "/api/v1/photos/ph-1?variant=dark" })}
      />,
    );
    expect(
      screen.getByTestId("photo-variants-dark-replace"),
    ).toBeInTheDocument();
    expect(
      screen.getByTestId("photo-variants-dark-image"),
    ).toBeInTheDocument();

    await user.click(screen.getByTestId("photo-variants-dark-delete"));

    await waitFor(() => {
      expect(deleteHit).toBe(true);
    });
    // After deletion the modal reflects the empty dark slot.
    await waitFor(() => {
      expect(
        screen.getByTestId("photo-variants-dark-dropzone"),
      ).toBeInTheDocument();
    });
  });

  it("closes on the Done affordance", async () => {
    const onClose = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(
      <PhotoVariantsModal
        open
        onClose={onClose}
        auctionPublicId={AUCTION_ID}
        photo={coverPhoto()}
      />,
    );
    await user.click(screen.getByTestId("photo-variants-modal-close"));
    expect(onClose).toHaveBeenCalled();
  });
});
