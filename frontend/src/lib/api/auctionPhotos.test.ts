import { describe, expect, it, beforeAll, afterAll, afterEach } from "vitest";
import { setupServer } from "msw/node";
import { http, HttpResponse } from "msw";
import { reorderPhotos } from "./auctionPhotos";

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
          { publicId: "p2", url: "/api/v1/photos/p2", contentType: "image/webp",
            sizeBytes: 100, sortOrder: 1, uploadedAt: "2026-05-07T00:00:00Z" },
          { publicId: "p1", url: "/api/v1/photos/p1", contentType: "image/webp",
            sizeBytes: 100, sortOrder: 2, uploadedAt: "2026-05-07T00:00:00Z" },
        ]);
      }),
    );
    const result = await reorderPhotos("abc-123", ["p2", "p1"]);
    expect(result).toHaveLength(2);
    expect(result[0].publicId).toBe("p2");
    expect(result[0].sortOrder).toBe(1);
  });
});
