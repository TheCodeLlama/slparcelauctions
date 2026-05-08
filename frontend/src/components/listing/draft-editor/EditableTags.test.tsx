import { describe, expect, it, vi, beforeAll, afterAll, afterEach } from "vitest";
import { setupServer } from "msw/node";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { EditableTags } from "./EditableTags";

const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "bypass" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe("EditableTags", () => {
  it("renders existing chips and the Edit tags trigger", () => {
    renderWithProviders(
      <EditableTags
        value={[{ code: "WATERFRONT", label: "Waterfront" }]}
        onSave={vi.fn()}
      />,
    );
    expect(screen.getByText("Waterfront")).toBeInTheDocument();
    expect(screen.getByTestId("editable-tags-trigger")).toBeInTheDocument();
  });

  it("opens the modal on trigger click and calls onSave on Done", async () => {
    server.use(
      http.get("*/api/v1/parcel-tags", () =>
        HttpResponse.json([
          {
            category: "Water",
            tags: [{ code: "WATERFRONT", label: "Waterfront" }],
          },
        ])),
    );
    const user = userEvent.setup();
    const onSave = vi.fn().mockResolvedValue(undefined);
    renderWithProviders(
      <EditableTags
        value={[{ code: "WATERFRONT", label: "Waterfront" }]}
        onSave={onSave}
      />,
    );
    await user.click(screen.getByTestId("editable-tags-trigger"));
    expect(await screen.findByRole("dialog")).toBeInTheDocument();
    await user.click(screen.getByTestId("editable-tags-done"));
    await vi.waitFor(() =>
      expect(onSave).toHaveBeenCalledWith(["WATERFRONT"]),
    );
  });
});
