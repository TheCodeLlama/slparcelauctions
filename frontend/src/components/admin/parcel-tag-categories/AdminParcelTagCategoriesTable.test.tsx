import { describe, expect, it, vi, beforeAll, afterAll, afterEach } from "vitest";
import { setupServer } from "msw/node";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { AdminParcelTagCategoriesTable } from "./AdminParcelTagCategoriesTable";
import type { AdminParcelTagCategoryDto } from "@/lib/admin/parcelTagCategories";

const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "bypass" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const categories: AdminParcelTagCategoryDto[] = [
  {
    code: "TERRAIN", label: "Terrain", description: null,
    active: true, createdAt: "x", updatedAt: "x",
  },
  {
    code: "RETIRED", label: "Retired", description: null,
    active: false, createdAt: "x", updatedAt: "x",
  },
];

describe("AdminParcelTagCategoriesTable", () => {
  it("renders rows with active state and edit/disable buttons", () => {
    renderWithProviders(
      <AdminParcelTagCategoriesTable categories={categories} onEdit={vi.fn()} />,
    );
    expect(screen.getByTestId("admin-parcel-tag-category-row-TERRAIN")).toBeInTheDocument();
    const retired = screen.getByTestId("admin-parcel-tag-category-row-RETIRED");
    expect(retired).toHaveAttribute("data-active", "false");
    expect(screen.getByTestId("admin-parcel-tag-category-edit-TERRAIN")).toBeInTheDocument();
  });

  it("renders empty state when no categories", () => {
    renderWithProviders(
      <AdminParcelTagCategoriesTable categories={[]} onEdit={vi.fn()} />,
    );
    expect(screen.getByTestId("admin-parcel-tag-categories-empty")).toBeInTheDocument();
  });

  it("Edit button calls onEdit", async () => {
    const user = userEvent.setup();
    const onEdit = vi.fn();
    renderWithProviders(
      <AdminParcelTagCategoriesTable categories={categories} onEdit={onEdit} />,
    );
    await user.click(screen.getByTestId("admin-parcel-tag-category-edit-TERRAIN"));
    expect(onEdit).toHaveBeenCalledWith(categories[0]);
  });

  it("Toggle button hits backend", async () => {
    let called = false;
    server.use(
      http.post("*/api/v1/admin/parcel-tag-categories/RETIRED/toggle-active", () => {
        called = true;
        return HttpResponse.json({ ...categories[1], active: true });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(
      <AdminParcelTagCategoriesTable categories={categories} onEdit={vi.fn()} />,
    );
    await user.click(screen.getByTestId("admin-parcel-tag-category-toggle-RETIRED"));
    await vi.waitFor(() => expect(called).toBe(true));
  });
});
