import { describe, expect, it, vi, beforeAll, afterAll, afterEach } from "vitest";
import { setupServer } from "msw/node";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { AddParcelTagCategoryModal } from "./AddParcelTagCategoryModal";

const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "bypass" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe("AddParcelTagCategoryModal", () => {
  it("uppercases the code field as the user types", async () => {
    const user = userEvent.setup();
    renderWithProviders(<AddParcelTagCategoryModal open onClose={vi.fn()} />);
    const codeInput = screen.getByTestId("add-parcel-tag-category-code") as HTMLInputElement;
    await user.type(codeInput, "Terrain!");
    expect(codeInput.value).toBe("TERRAIN");
  });

  it("Submit disabled until code + label present", async () => {
    const user = userEvent.setup();
    renderWithProviders(<AddParcelTagCategoryModal open onClose={vi.fn()} />);
    const submit = screen.getByTestId("add-parcel-tag-category-submit");
    expect(submit).toBeDisabled();
    await user.type(screen.getByTestId("add-parcel-tag-category-code"), "X");
    await user.type(screen.getByTestId("add-parcel-tag-category-label"), "X");
    expect(submit).not.toBeDisabled();
  });

  it("submits POST and closes on success", async () => {
    let received: unknown = null;
    server.use(
      http.post("*/api/v1/admin/parcel-tag-categories", async ({ request }) => {
        received = await request.json();
        return HttpResponse.json({
          code: "TERRAIN", label: "Terrain", description: null,
          active: true, createdAt: "x", updatedAt: "x",
        });
      }),
    );
    const user = userEvent.setup();
    const onClose = vi.fn();
    renderWithProviders(
      <AddParcelTagCategoryModal open onClose={onClose} />,
    );
    await user.type(screen.getByTestId("add-parcel-tag-category-code"), "TERRAIN");
    await user.type(screen.getByTestId("add-parcel-tag-category-label"), "Terrain");
    await user.click(screen.getByTestId("add-parcel-tag-category-submit"));
    await vi.waitFor(() => {
      expect(received).toMatchObject({ code: "TERRAIN", label: "Terrain" });
      expect(onClose).toHaveBeenCalled();
    });
  });
});
