import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { GroupsPage } from "./GroupsPage";
import type { RealtyGroupCard } from "@/types/realty";

function makeCard(overrides: Partial<RealtyGroupCard> = {}): RealtyGroupCard {
  return {
    publicId: "00000000-0000-0000-0000-000000000001",
    name: "Mainland Realty",
    slug: "mainland-realty",
    tagline: "Premium Mainland brokerage.",
    logoUrl: null,
    coverUrl: null,
    foundedAt: "2026-01-01T00:00:00Z",
    memberCount: 1,
    memberSeatLimit: 8,
    activeListingsCount: 0,
    completedSalesCount: 0,
    rating: { averageRating: null, reviewCount: 0 },
    ...overrides,
  };
}

describe("GroupsPage (forked, props-driven)", () => {
  it("renders one card per group in the passed-in order (no client-side filter or sort)", () => {
    render(
      <GroupsPage
        groups={[
          makeCard({
            name: "Zulu",
            publicId: "00000000-0000-0000-0000-00000000zzzz",
            slug: "zulu",
          }),
          makeCard({
            name: "Alpha",
            publicId: "00000000-0000-0000-0000-00000000aaaa",
            slug: "alpha",
          }),
        ]}
        q=""
        sort="RATING"
        page={0}
        pageCount={1}
        totalCount={2}
        onQChange={vi.fn()}
        onSortChange={vi.fn()}
        onPageChange={vi.fn()}
      />,
    );
    expect(screen.getByText("Zulu")).toBeInTheDocument();
    expect(screen.getByText("Alpha")).toBeInTheDocument();
  });

  it("calls onQChange when the user types in the search input", () => {
    const onQChange = vi.fn();
    render(
      <GroupsPage
        groups={[]}
        q=""
        sort="RATING"
        page={0}
        pageCount={1}
        totalCount={0}
        onQChange={onQChange}
        onSortChange={vi.fn()}
        onPageChange={vi.fn()}
      />,
    );
    fireEvent.change(screen.getByPlaceholderText(/search groups/i), {
      target: { value: "mainland" },
    });
    expect(onQChange).toHaveBeenCalledWith("mainland");
  });

  it("calls onSortChange when the sort select changes", () => {
    const onSortChange = vi.fn();
    render(
      <GroupsPage
        groups={[]}
        q=""
        sort="RATING"
        page={0}
        pageCount={1}
        totalCount={0}
        onQChange={vi.fn()}
        onSortChange={onSortChange}
        onPageChange={vi.fn()}
      />,
    );
    fireEvent.change(screen.getByRole("combobox", { name: /sort groups/i }), {
      target: { value: "NEWEST" },
    });
    expect(onSortChange).toHaveBeenCalledWith("NEWEST");
  });

  it("calls onOpenGroup when a card is clicked", () => {
    const onOpenGroup = vi.fn();
    const card = makeCard({ name: "Alpha", slug: "alpha" });
    render(
      <GroupsPage
        groups={[card]}
        q=""
        sort="RATING"
        page={0}
        pageCount={1}
        totalCount={1}
        onQChange={vi.fn()}
        onSortChange={vi.fn()}
        onPageChange={vi.fn()}
        onOpenGroup={onOpenGroup}
      />,
    );
    fireEvent.click(screen.getByText("Alpha"));
    expect(onOpenGroup).toHaveBeenCalledWith(card);
  });

  it("renders a skeleton grid (no empty state) when isLoading is true", () => {
    render(
      <GroupsPage
        groups={[]}
        q=""
        sort="RATING"
        page={0}
        pageCount={1}
        totalCount={0}
        onQChange={vi.fn()}
        onSortChange={vi.fn()}
        onPageChange={vi.fn()}
        isLoading
      />,
    );
    expect(screen.getByLabelText(/loading groups/i)).toBeInTheDocument();
    // EmptyGroups is suppressed while loading.
    expect(screen.queryByText(/no groups match/i)).not.toBeInTheDocument();
  });

  it("renders the empty state when groups is empty and isLoading is false", () => {
    render(
      <GroupsPage
        groups={[]}
        q=""
        sort="RATING"
        page={0}
        pageCount={1}
        totalCount={0}
        onQChange={vi.fn()}
        onSortChange={vi.fn()}
        onPageChange={vi.fn()}
      />,
    );
    expect(screen.queryByLabelText(/loading groups/i)).not.toBeInTheDocument();
    expect(screen.getByText(/no groups match/i)).toBeInTheDocument();
  });

  it("renders the totalCount header (backend count, not groups.length)", () => {
    render(
      <GroupsPage
        groups={[makeCard({ name: "Alpha" })]}
        q=""
        sort="RATING"
        page={0}
        pageCount={3}
        totalCount={42}
        onQChange={vi.fn()}
        onSortChange={vi.fn()}
        onPageChange={vi.fn()}
      />,
    );
    // Header uses the backend totalCount, not groups.length (which is 1 here).
    const countNode = screen.getByText("42");
    expect(countNode).toBeInTheDocument();
    expect(countNode.parentElement?.textContent).toMatch(/42\s+groups/);
  });

  it("renders 'No groups' in the header when totalCount is 0", () => {
    render(
      <GroupsPage
        groups={[]}
        q=""
        sort="RATING"
        page={0}
        pageCount={1}
        totalCount={0}
        onQChange={vi.fn()}
        onSortChange={vi.fn()}
        onPageChange={vi.fn()}
      />,
    );
    expect(screen.getByText("No groups")).toBeInTheDocument();
  });
});
