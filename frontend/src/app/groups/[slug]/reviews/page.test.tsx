import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import GroupReviewsPage from "./page";

vi.mock("@/lib/api/realtyGroups", () => ({
  realtyGroupsApi: {
    getGroupBySlug: vi.fn().mockResolvedValue({
      publicId: "g-1",
      slug: "sunset-realty",
      name: "Sunset Realty",
      rating: { averageRating: 4.5, reviewCount: 2 },
    }),
  },
}));
vi.mock("@/lib/api/realtyGroupReviews", () => ({
  fetchGroupReviews: vi.fn().mockResolvedValue({
    content: [],
    totalElements: 0,
    totalPages: 0,
    number: 0,
    size: 20,
  }),
}));
vi.mock("@/lib/api", () => ({ isApiError: () => false }));

describe("/groups/[slug]/reviews", () => {
  it("renders the reviews header for the group", async () => {
    const ui = await GroupReviewsPage({
      params: Promise.resolve({ slug: "sunset-realty" }),
      searchParams: Promise.resolve({}),
    } as never);
    render(ui as React.ReactElement);
    expect(
      screen.getByRole("heading", { name: /sunset realty/i }),
    ).toBeInTheDocument();
  });
});
