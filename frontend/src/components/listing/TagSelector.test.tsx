import { describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { useState } from "react";
import {
  renderWithProviders,
  screen,
  userEvent,
} from "@/test/render";
import { server } from "@/test/msw/server";
import { TagSelector } from "./TagSelector";

const tagGroups = [
  {
    category: "Terrain",
    tags: [
      {
        code: "beach",
        label: "Beach",
        category: "Terrain",
        description: null,
        sortOrder: 1,
      },
      {
        code: "mountain",
        label: "Mountain",
        category: "Terrain",
        description: null,
        sortOrder: 2,
      },
    ],
  },
  {
    category: "Location",
    tags: [
      {
        code: "city",
        label: "City",
        category: "Location",
        description: null,
        sortOrder: 1,
      },
    ],
  },
];

describe("TagSelector", () => {
  it("renders categories with their tags", async () => {
    server.use(
      http.get("*/api/v1/parcel-tags", () => HttpResponse.json(tagGroups)),
    );
    renderWithProviders(<TagSelector value={[]} onChange={vi.fn()} />);
    await screen.findByRole("button", { name: /Beach/i });
    expect(screen.getByText("Terrain")).toBeInTheDocument();
    expect(screen.getByText("Location")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Mountain/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /City/i })).toBeInTheDocument();
  });

  it("toggles a tag on click", async () => {
    server.use(
      http.get("*/api/v1/parcel-tags", () => HttpResponse.json(tagGroups)),
    );
    const onChange = vi.fn();
    renderWithProviders(<TagSelector value={[]} onChange={onChange} />);
    await userEvent.click(
      await screen.findByRole("button", { name: /Beach/i }),
    );
    expect(onChange).toHaveBeenCalledWith(["beach"]);
  });

  it("enforces maxSelections by disabling remaining tag buttons", async () => {
    server.use(
      http.get("*/api/v1/parcel-tags", () => HttpResponse.json(tagGroups)),
    );
    renderWithProviders(
      <TagSelector
        value={["beach"]}
        onChange={vi.fn()}
        maxSelections={1}
      />,
    );
    const mountain = await screen.findByRole("button", { name: /Mountain/i });
    expect(mountain).toBeDisabled();
    expect(screen.getByText("1/1 selected")).toBeInTheDocument();
  });

  it("collapses a category when its header is clicked", async () => {
    server.use(
      http.get("*/api/v1/parcel-tags", () => HttpResponse.json(tagGroups)),
    );
    // The selector drives its own collapsed-state; we only need a stable
    // value/onChange pair here.
    const Harness = () => {
      const [v, setV] = useState<string[]>([]);
      return <TagSelector value={v} onChange={setV} />;
    };
    renderWithProviders(<Harness />);
    await screen.findByRole("button", { name: /Beach/i });
    const terrainHeader = screen.getByRole("button", { name: /^Terrain/ });
    await userEvent.click(terrainHeader);
    expect(
      screen.queryByRole("button", { name: /Beach/i }),
    ).not.toBeInTheDocument();
  });
});
