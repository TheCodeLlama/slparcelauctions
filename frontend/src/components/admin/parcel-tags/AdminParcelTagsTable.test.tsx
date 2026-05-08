import { describe, expect, it, vi, beforeAll, afterAll, afterEach } from "vitest";
import { setupServer } from "msw/node";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { AdminParcelTagsTable } from "./AdminParcelTagsTable";
import type { AdminParcelTagDto } from "@/lib/admin/parcelTags";

const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "bypass" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const tags: AdminParcelTagDto[] = [
  {
    code: "WATERFRONT", label: "Waterfront", category: "Terrain",
    description: null, sortOrder: 1, active: true,
    createdAt: "x", updatedAt: "x",
  },
  {
    code: "SNOW", label: "Snow", category: "Terrain",
    description: null, sortOrder: 2, active: false,
    createdAt: "x", updatedAt: "x",
  },
  {
    code: "STREETFRONT", label: "Streetfront", category: "Roads",
    description: null, sortOrder: 1, active: true,
    createdAt: "x", updatedAt: "x",
  },
];

describe("AdminParcelTagsTable", () => {
  it("groups rows by category and surfaces inactive rows visually muted", () => {
    renderWithProviders(<AdminParcelTagsTable tags={tags} onEdit={vi.fn()} />);
    expect(screen.getByTestId("admin-parcel-tags-group-Terrain")).toBeInTheDocument();
    expect(screen.getByTestId("admin-parcel-tags-group-Roads")).toBeInTheDocument();
    const snowRow = screen.getByTestId("admin-parcel-tag-row-SNOW");
    expect(snowRow).toHaveAttribute("data-active", "false");
  });

  it("renders empty state when no tags", () => {
    renderWithProviders(<AdminParcelTagsTable tags={[]} onEdit={vi.fn()} />);
    expect(screen.getByTestId("admin-parcel-tags-empty")).toBeInTheDocument();
  });

  it("Edit button calls onEdit with the row's tag", async () => {
    const user = userEvent.setup();
    const onEdit = vi.fn();
    renderWithProviders(<AdminParcelTagsTable tags={tags} onEdit={onEdit} />);
    await user.click(screen.getByTestId("admin-parcel-tag-edit-WATERFRONT"));
    expect(onEdit).toHaveBeenCalledWith(tags[0]);
  });

  it("toggle button hits PATCH endpoint", async () => {
    let called = false;
    server.use(
      http.post("*/api/v1/admin/parcel-tags/SNOW/toggle-active", () => {
        called = true;
        return HttpResponse.json({ ...tags[1], active: true });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<AdminParcelTagsTable tags={tags} onEdit={vi.fn()} />);
    await user.click(screen.getByTestId("admin-parcel-tag-toggle-SNOW"));
    await vi.waitFor(() => expect(called).toBe(true));
  });
});
