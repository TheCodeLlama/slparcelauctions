import { describe, expect, it, vi, beforeAll, afterAll, afterEach } from "vitest";
import { setupServer } from "msw/node";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { EditParcelTagCategoryModal } from "./EditParcelTagCategoryModal";
import type { AdminParcelTagCategoryDto } from "@/lib/admin/parcelTagCategories";

const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "bypass" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const category: AdminParcelTagCategoryDto = {
  code: "TERRAIN", label: "Terrain", description: "Old desc",
  active: true, createdAt: "x", updatedAt: "x",
};

describe("EditParcelTagCategoryModal", () => {
  it("renders code as read-only text", () => {
    renderWithProviders(
      <EditParcelTagCategoryModal open onClose={vi.fn()} category={category} />,
    );
    expect(screen.getByTestId("edit-parcel-tag-category-code")).toHaveTextContent("TERRAIN");
  });

  it("submits only the changed fields", async () => {
    let received: unknown = null;
    server.use(
      http.patch("*/api/v1/admin/parcel-tag-categories/TERRAIN", async ({ request }) => {
        received = await request.json();
        return HttpResponse.json({ ...category, label: "New label" });
      }),
    );
    const user = userEvent.setup();
    const onClose = vi.fn();
    renderWithProviders(
      <EditParcelTagCategoryModal open onClose={onClose} category={category} />,
    );
    const labelInput = screen.getByTestId("edit-parcel-tag-category-label") as HTMLInputElement;
    await user.clear(labelInput);
    await user.type(labelInput, "New label");
    await user.click(screen.getByTestId("edit-parcel-tag-category-submit"));
    await vi.waitFor(() => {
      expect(received).toEqual({ label: "New label" });
      expect(onClose).toHaveBeenCalled();
    });
  });
});
