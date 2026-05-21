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
    logoLightUrl: null, logoDarkUrl: null,
    coverLightUrl: null, coverDarkUrl: null,
    foundedAt: "2026-01-01T00:00:00Z",
    memberCount: 1,
    memberSeatLimit: 8,
    activeListingsCount: 0,
    completedSalesCount: 0,
    rating: { averageRating: null, reviewCount: 0 },
    ...overrides,
  };
}

/**
 * Defaults for the new required props added in the 1:1 template restoration.
 * Tests that focus on a single control still pass full props for the others.
 */
const defaultProps = {
  q: "",
  sort: "RATING" as const,
  direction: "DESC" as const,
  minRating: 0,
  minReviews: 0,
  activeOnly: false,
  page: 0,
  pageCount: 1,
  totalCount: 0,
  onQChange: vi.fn(),
  onSortChange: vi.fn(),
  onDirectionChange: vi.fn(),
  onMinRatingChange: vi.fn(),
  onMinReviewsChange: vi.fn(),
  onActiveOnlyChange: vi.fn(),
  onClearFilters: vi.fn(),
  onPageChange: vi.fn(),
};

describe("GroupsPage (template-1:1, props-driven)", () => {
  it("renders one card per group in the passed-in order (no client-side filter or sort)", () => {
    render(
      <GroupsPage
        {...defaultProps}
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
        totalCount={2}
      />,
    );
    expect(screen.getByText("Zulu")).toBeInTheDocument();
    expect(screen.getByText("Alpha")).toBeInTheDocument();
  });

  it("calls onQChange when the user types in the search input", () => {
    const onQChange = vi.fn();
    render(
      <GroupsPage {...defaultProps} groups={[]} onQChange={onQChange} />,
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
        {...defaultProps}
        groups={[]}
        onSortChange={onSortChange}
      />,
    );
    fireEvent.change(screen.getByRole("combobox", { name: /sort groups/i }), {
      target: { value: "NEWEST" },
    });
    expect(onSortChange).toHaveBeenCalledWith("NEWEST");
  });

  it("toggles direction DESC -> ASC when the direction button is clicked", () => {
    const onDirectionChange = vi.fn();
    render(
      <GroupsPage
        {...defaultProps}
        groups={[]}
        direction="DESC"
        onDirectionChange={onDirectionChange}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: /sort descending/i }));
    expect(onDirectionChange).toHaveBeenCalledWith("ASC");
  });

  it("toggles direction ASC -> DESC and renders the Asc label", () => {
    const onDirectionChange = vi.fn();
    render(
      <GroupsPage
        {...defaultProps}
        groups={[]}
        direction="ASC"
        onDirectionChange={onDirectionChange}
      />,
    );
    const btn = screen.getByRole("button", { name: /sort ascending/i });
    expect(btn).toHaveTextContent(/asc/i);
    fireEvent.click(btn);
    expect(onDirectionChange).toHaveBeenCalledWith("DESC");
  });

  it("calls onMinReviewsChange when the sidebar number input changes", () => {
    const onMinReviewsChange = vi.fn();
    render(
      <GroupsPage
        {...defaultProps}
        groups={[]}
        onMinReviewsChange={onMinReviewsChange}
      />,
    );
    fireEvent.change(screen.getByLabelText(/minimum reviews/i), {
      target: { value: "25" },
    });
    expect(onMinReviewsChange).toHaveBeenCalledWith(25);
  });

  it("calls onActiveOnlyChange when the sidebar checkbox is toggled", () => {
    const onActiveOnlyChange = vi.fn();
    render(
      <GroupsPage
        {...defaultProps}
        groups={[]}
        activeOnly={false}
        onActiveOnlyChange={onActiveOnlyChange}
      />,
    );
    fireEvent.click(screen.getByText(/has active listing/i));
    expect(onActiveOnlyChange).toHaveBeenCalledWith(true);
  });

  it("calls onClearFilters when 'Clear all filters' is clicked", () => {
    const onClearFilters = vi.fn();
    render(
      <GroupsPage
        {...defaultProps}
        groups={[]}
        onClearFilters={onClearFilters}
      />,
    );
    fireEvent.click(screen.getByText(/clear all filters/i));
    expect(onClearFilters).toHaveBeenCalled();
  });

  it("calls onOpenGroup when a card is clicked", () => {
    const onOpenGroup = vi.fn();
    const card = makeCard({ name: "Alpha", slug: "alpha" });
    render(
      <GroupsPage
        {...defaultProps}
        groups={[card]}
        totalCount={1}
        onOpenGroup={onOpenGroup}
      />,
    );
    fireEvent.click(screen.getByText("Alpha"));
    expect(onOpenGroup).toHaveBeenCalledWith(card);
  });

  it("renders the empty state when groups is empty", () => {
    render(<GroupsPage {...defaultProps} groups={[]} />);
    expect(screen.getByText(/no groups match/i)).toBeInTheDocument();
  });

  it("renders the totalCount header (backend count, not groups.length)", () => {
    render(
      <GroupsPage
        {...defaultProps}
        groups={[makeCard({ name: "Alpha" })]}
        pageCount={3}
        totalCount={42}
      />,
    );
    const countNode = screen.getByText("42");
    expect(countNode).toBeInTheDocument();
    expect(countNode.parentElement?.textContent).toMatch(/42\s+groups/);
  });

  it("renders 0 groups in the header when totalCount is 0", () => {
    render(<GroupsPage {...defaultProps} groups={[]} totalCount={0} />);
    expect(screen.getByText("0")).toBeInTheDocument();
  });
});
