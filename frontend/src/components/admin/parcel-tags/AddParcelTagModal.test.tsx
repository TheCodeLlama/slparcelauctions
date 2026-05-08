import { describe, expect, it, vi, beforeAll, afterAll, afterEach } from "vitest";
import { setupServer } from "msw/node";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { AddParcelTagModal } from "./AddParcelTagModal";

const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "bypass" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const categoriesHandler = http.get(
  "*/api/v1/admin/parcel-tag-categories",
  () =>
    HttpResponse.json([
      { code: "TERRAIN", label: "Terrain", description: null, active: true,
        createdAt: "x", updatedAt: "x" },
    ]),
);

describe("AddParcelTagModal", () => {
  it("uppercases the code field as the user types", async () => {
    server.use(categoriesHandler);
    const user = userEvent.setup();
    renderWithProviders(<AddParcelTagModal open onClose={vi.fn()} />);
    const codeInput = screen.getByTestId("add-parcel-tag-code") as HTMLInputElement;
    await user.type(codeInput, "BeachFront!");
    expect(codeInput.value).toBe("BEACHFRONT");
  });

  it("Submit button disabled until code, label, category present", async () => {
    server.use(categoriesHandler);
    const user = userEvent.setup();
    renderWithProviders(<AddParcelTagModal open onClose={vi.fn()} />);
    const submit = screen.getByTestId("add-parcel-tag-submit");
    expect(submit).toBeDisabled();
    await user.type(screen.getByTestId("add-parcel-tag-code"), "X");
    await user.type(screen.getByTestId("add-parcel-tag-label"), "X");
    // Wait for the category dropdown to populate from the MSW handler.
    await screen.findByRole("option", { name: "Terrain" });
    await user.selectOptions(
      screen.getByTestId("add-parcel-tag-category"),
      "TERRAIN",
    );
    expect(submit).not.toBeDisabled();
  });

  it("submits POST with categoryCode and closes on success", async () => {
    let received: unknown = null;
    server.use(
      categoriesHandler,
      http.post("*/api/v1/admin/parcel-tags", async ({ request }) => {
        received = await request.json();
        return HttpResponse.json({
          code: "X", label: "X",
          category: { code: "TERRAIN", label: "Terrain", active: true },
          description: null, active: true, createdAt: "x", updatedAt: "x",
        });
      }),
    );
    const user = userEvent.setup();
    const onClose = vi.fn();
    renderWithProviders(<AddParcelTagModal open onClose={onClose} />);
    await user.type(screen.getByTestId("add-parcel-tag-code"), "X");
    await user.type(screen.getByTestId("add-parcel-tag-label"), "X");
    await screen.findByRole("option", { name: "Terrain" });
    await user.selectOptions(
      screen.getByTestId("add-parcel-tag-category"),
      "TERRAIN",
    );
    await user.click(screen.getByTestId("add-parcel-tag-submit"));
    await vi.waitFor(() => {
      expect(received).toMatchObject({
        code: "X", label: "X", categoryCode: "TERRAIN",
      });
      expect(onClose).toHaveBeenCalled();
    });
  });

  it("surfaces 409 conflict in the form error slot", async () => {
    server.use(
      categoriesHandler,
      http.post("*/api/v1/admin/parcel-tags", () =>
        HttpResponse.json(
          {
            status: 409,
            title: "Parcel Tag Code Conflict",
            detail: "A parcel tag with this code already exists.",
            code: "PARCEL_TAG_CODE_CONFLICT",
          },
          { status: 409 },
        )),
    );
    const user = userEvent.setup();
    renderWithProviders(<AddParcelTagModal open onClose={vi.fn()} />);
    await user.type(screen.getByTestId("add-parcel-tag-code"), "X");
    await user.type(screen.getByTestId("add-parcel-tag-label"), "X");
    await screen.findByRole("option", { name: "Terrain" });
    await user.selectOptions(
      screen.getByTestId("add-parcel-tag-category"),
      "TERRAIN",
    );
    await user.click(screen.getByTestId("add-parcel-tag-submit"));
    expect(
      await screen.findByText(/already exists/i),
    ).toBeInTheDocument();
  });
});
