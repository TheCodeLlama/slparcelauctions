import { describe, expect, it, beforeAll, afterAll, afterEach } from "vitest";
import { setupServer } from "msw/node";
import { http, HttpResponse } from "msw";
import {
  reorderPhotos,
  uploadPhotoDarkVariant,
  deletePhotoDarkVariant,
} from "./auctionPhotos";

const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe("reorderPhotos", () => {
  it("PATCHes /api/v1/auctions/{id}/photos/order and returns the reordered DTO array", async () => {
    server.use(
      http.patch("*/api/v1/auctions/abc-123/photos/order", async ({ request }) => {
        const body = (await request.json()) as { photoPublicIds: string[] };
        expect(body.photoPublicIds).toEqual(["p2", "p1"]);
        return HttpResponse.json([
          { publicId: "p2", lightUrl: "/api/v1/photos/p2?variant=light",
            darkUrl: null, source: "SELLER_UPLOAD", sortOrder: 1 },
          { publicId: "p1", lightUrl: "/api/v1/photos/p1?variant=light",
            darkUrl: null, source: "USER_DEFAULT_COVER", sortOrder: 2 },
        ]);
      }),
    );
    const result = await reorderPhotos("abc-123", ["p2", "p1"]);
    expect(result).toHaveLength(2);
    expect(result[0].publicId).toBe("p2");
    expect(result[0].sortOrder).toBe(1);
  });
});

describe("uploadPhotoDarkVariant", () => {
  it("POSTs multipart to /photos/{photoId}/dark and returns the updated photo", async () => {
    let hitMethod = "";
    let hitContentType: string | null = null;
    server.use(
      http.post(
        "*/api/v1/auctions/abc-123/photos/ph-1/dark",
        ({ request }) => {
          hitMethod = request.method;
          hitContentType = request.headers.get("content-type");
          return HttpResponse.json({
            publicId: "ph-1",
            lightUrl: "/api/v1/photos/ph-1?variant=light",
            darkUrl: "/api/v1/photos/ph-1?variant=dark",
            source: "USER_DEFAULT_COVER",
            sortOrder: 0,
          });
        },
      ),
    );
    const file = new File([new Uint8Array(8)], "dark.webp", {
      type: "image/webp",
    });
    const result = await uploadPhotoDarkVariant("abc-123", "ph-1", file);
    expect(hitMethod).toBe("POST");
    // The api helper omits an explicit Content-Type so the browser sets the
    // multipart boundary itself.
    expect(hitContentType).toMatch(/multipart\/form-data/);
    expect(result.darkUrl).toContain("variant=dark");
  });
});

describe("deletePhotoDarkVariant", () => {
  it("DELETEs /photos/{photoId}/dark and returns the photo with darkUrl null", async () => {
    server.use(
      http.delete("*/api/v1/auctions/abc-123/photos/ph-1/dark", () =>
        HttpResponse.json({
          publicId: "ph-1",
          lightUrl: "/api/v1/photos/ph-1?variant=light",
          darkUrl: null,
          source: "USER_DEFAULT_COVER",
          sortOrder: 0,
        }),
      ),
    );
    const result = await deletePhotoDarkVariant("abc-123", "ph-1");
    expect(result.darkUrl).toBeNull();
  });
});
