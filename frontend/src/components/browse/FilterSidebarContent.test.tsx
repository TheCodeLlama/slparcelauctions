import { describe, it, expect, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { server } from "@/test/msw/server";
import { FilterSidebarContent } from "./FilterSidebarContent";

// TagSelector fetches /api/v1/parcel-tags — stub an empty catalogue so the
// selector renders without MSW complaining about an unhandled request.
function stubEmptyTags() {
  server.use(
    http.get("*/api/v1/parcel-tags", () => HttpResponse.json([])),
  );
}

describe("FilterSidebarContent — immediate mode", () => {
  it("fires onChange immediately on maturity toggle", async () => {
    stubEmptyTags();
    const onChange = vi.fn();
    renderWithProviders(
      <FilterSidebarContent
        mode="immediate"
        query={{}}
        onChange={onChange}
        hiddenGroups={["distance"]}
      />,
    );
    await userEvent.click(screen.getByLabelText(/general/i));
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ maturity: ["GENERAL"] }),
    );
  });

  it("hides the distance group when hiddenGroups includes 'distance'", () => {
    stubEmptyTags();
    renderWithProviders(
      <FilterSidebarContent
        mode="immediate"
        query={{}}
        onChange={() => {}}
        hiddenGroups={["distance"]}
      />,
    );
    expect(
      screen.queryByRole("button", { name: /distance search/i }),
    ).toBeNull();
  });

  it("shows distance group by default", () => {
    stubEmptyTags();
    renderWithProviders(
      <FilterSidebarContent
        mode="immediate"
        query={{}}
        onChange={() => {}}
      />,
    );
    expect(
      screen.getByRole("button", { name: /distance search/i }),
    ).toBeInTheDocument();
  });
});

describe("FilterSidebarContent — staged mode", () => {
  it("holds local state until Apply fires onCommit", async () => {
    stubEmptyTags();
    const onCommit = vi.fn();
    renderWithProviders(
      <FilterSidebarContent
        mode="staged"
        query={{}}
        onCommit={onCommit}
        hiddenGroups={["distance"]}
      />,
    );
    await userEvent.click(screen.getByLabelText(/general/i));
    expect(onCommit).not.toHaveBeenCalled();
    await userEvent.click(
      screen.getByRole("button", { name: /apply filters/i }),
    );
    expect(onCommit).toHaveBeenCalledWith(
      expect.objectContaining({ maturity: ["GENERAL"] }),
    );
  });

  it("renders in dark mode", () => {
    stubEmptyTags();
    renderWithProviders(
      <FilterSidebarContent
        mode="staged"
        query={{}}
        onCommit={() => {}}
        hiddenGroups={["distance"]}
      />,
      { theme: "dark", forceTheme: true },
    );
    expect(
      screen.getByRole("button", { name: /apply filters/i }),
    ).toBeInTheDocument();
  });

  it("reseeds local state from query on remount (close-without-apply discards)", () => {
    stubEmptyTags();
    const { unmount } = renderWithProviders(
      <FilterSidebarContent
        mode="staged"
        query={{ maturity: ["GENERAL"] }}
        onCommit={() => {}}
        hiddenGroups={["distance"]}
      />,
    );
    expect(screen.getByLabelText(/general/i)).toBeChecked();
    unmount();
    renderWithProviders(
      <FilterSidebarContent
        mode="staged"
        query={{ maturity: ["GENERAL"] }}
        onCommit={() => {}}
        hiddenGroups={["distance"]}
      />,
    );
    expect(screen.getByLabelText(/general/i)).toBeChecked();
  });
});
