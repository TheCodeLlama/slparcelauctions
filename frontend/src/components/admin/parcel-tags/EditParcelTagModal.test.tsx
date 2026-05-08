import { describe, expect, it, vi, beforeAll, afterAll, afterEach } from "vitest";
import { setupServer } from "msw/node";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { EditParcelTagModal } from "./EditParcelTagModal";
import type { AdminParcelTagDto } from "@/lib/admin/parcelTags";

const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "bypass" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const tag: AdminParcelTagDto = {
  code: "WATERFRONT",
  label: "Waterfront",
  category: { code: "TERRAIN", label: "Terrain", active: true },
  description: "Old desc",
  active: true,
  createdAt: "x",
  updatedAt: "x",
};

const categoriesHandler = http.get(
  "*/api/v1/admin/parcel-tag-categories",
  () =>
    HttpResponse.json([
      { code: "TERRAIN", label: "Terrain", description: null, active: true,
        createdAt: "x", updatedAt: "x" },
      { code: "ROADS", label: "Roads", description: null, active: true,
        createdAt: "x", updatedAt: "x" },
    ]),
);

describe("EditParcelTagModal", () => {
  it("renders code as read-only text", () => {
    server.use(categoriesHandler);
    renderWithProviders(<EditParcelTagModal open onClose={vi.fn()} tag={tag} />);
    expect(screen.getByTestId("edit-parcel-tag-code")).toHaveTextContent("WATERFRONT");
  });

  it("submits only the changed fields", async () => {
    let received: unknown = null;
    server.use(
      categoriesHandler,
      http.patch("*/api/v1/admin/parcel-tags/WATERFRONT", async ({ request }) => {
        received = await request.json();
        return HttpResponse.json({ ...tag, label: "New label" });
      }),
    );
    const user = userEvent.setup();
    const onClose = vi.fn();
    renderWithProviders(
      <EditParcelTagModal open onClose={onClose} tag={tag} />,
    );
    const labelInput = screen.getByTestId("edit-parcel-tag-label") as HTMLInputElement;
    await user.clear(labelInput);
    await user.type(labelInput, "New label");
    await user.click(screen.getByTestId("edit-parcel-tag-submit"));
    await vi.waitFor(() => {
      expect(received).toEqual({ label: "New label" });
      expect(onClose).toHaveBeenCalled();
    });
  });

  it("no-op submit just closes (no PATCH)", async () => {
    let called = false;
    server.use(
      categoriesHandler,
      http.patch("*/api/v1/admin/parcel-tags/WATERFRONT", () => {
        called = true;
        return HttpResponse.json(tag);
      }),
    );
    const user = userEvent.setup();
    const onClose = vi.fn();
    renderWithProviders(
      <EditParcelTagModal open onClose={onClose} tag={tag} />,
    );
    await user.click(screen.getByTestId("edit-parcel-tag-submit"));
    expect(onClose).toHaveBeenCalled();
    await new Promise((r) => setTimeout(r, 50));
    expect(called).toBe(false);
  });
});
