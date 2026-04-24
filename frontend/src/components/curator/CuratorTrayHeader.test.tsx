import { describe, it, expect, vi } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { CuratorTrayHeader } from "./CuratorTrayHeader";
import type { AuctionSearchQuery } from "@/types/search";

const baseQuery: AuctionSearchQuery = {
  sort: "newest",
  statusFilter: "active_only",
};

describe("CuratorTrayHeader", () => {
  it("renders the saved count in the title", () => {
    renderWithProviders(
      <CuratorTrayHeader
        count={42}
        query={baseQuery}
        onQueryChange={() => {}}
      />,
    );
    expect(
      screen.getByRole("heading", { name: /your curator tray \(42 saved\)/i }),
    ).toBeInTheDocument();
  });

  it("reports sort changes through onQueryChange (page resets to 0)", async () => {
    const onQueryChange = vi.fn();
    renderWithProviders(
      <CuratorTrayHeader
        count={0}
        query={{ ...baseQuery, page: 4 }}
        onQueryChange={onQueryChange}
      />,
    );
    await userEvent.selectOptions(screen.getByLabelText("Sort"), "ending_soonest");
    expect(onQueryChange).toHaveBeenCalled();
    const next = onQueryChange.mock.calls.at(-1)![0];
    expect(next.sort).toBe("ending_soonest");
    expect(next.page).toBe(0);
  });

  it("reports status-filter changes through onQueryChange", async () => {
    const onQueryChange = vi.fn();
    renderWithProviders(
      <CuratorTrayHeader
        count={0}
        query={baseQuery}
        onQueryChange={onQueryChange}
      />,
    );
    await userEvent.selectOptions(screen.getByLabelText("Status"), "ended_only");
    const next = onQueryChange.mock.calls.at(-1)![0];
    expect(next.statusFilter).toBe("ended_only");
  });

  it("renders in dark mode without crashing", () => {
    renderWithProviders(
      <CuratorTrayHeader
        count={0}
        query={baseQuery}
        onQueryChange={() => {}}
      />,
      { theme: "dark", forceTheme: true },
    );
    expect(
      screen.getByRole("heading", { name: /curator tray/i }),
    ).toBeInTheDocument();
  });
});
