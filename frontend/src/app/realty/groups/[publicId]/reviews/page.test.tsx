import { describe, it, expect, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/msw/server";
import { render, screen } from "@testing-library/react";
import { makeWrapper } from "@/test/render";

import GroupReviewsPage from "./page";

// The page is a server component. notFound() is wired to throw a
// synchronous marker so tests can assert the not-found branch with
// rejects.toThrow.
vi.mock("next/navigation", () => ({
  notFound: () => {
    throw new Error("NEXT_NOT_FOUND");
  },
}));

const GROUP_PUBLIC_ID = "abc-123";

function mockGroupOk(name = "Sunset Estates", rating: object | null = null) {
  server.use(
    http.get(`*/api/v1/realty-groups/${GROUP_PUBLIC_ID}`, () =>
      HttpResponse.json({
        publicId: GROUP_PUBLIC_ID,
        name,
        slug: "sunset-estates",
        description: null,
        website: null,
        logoUrl: null,
        coverUrl: null,
        memberSince: "2026-01-01T00:00:00Z",
        leader: {
          userPublicId: "leader-1",
          displayName: "Leader",
          avatarUrl: null,
          permissions: [],
        },
        agents: [],
        memberSeatLimit: 10,
        memberCount: 1,
        rating,
      }),
    ),
  );
}

describe("GroupReviewsPage", () => {
  it("renders the group name + rating badge + review rows", async () => {
    mockGroupOk("Sunset Estates", { averageRating: 4.5, reviewCount: 2 });
    server.use(
      http.get(`*/api/v1/realty/groups/${GROUP_PUBLIC_ID}/reviews`, () =>
        HttpResponse.json({
          content: [
            {
              reviewerPublicId: "u-1",
              reviewerDisplayName: "Alice",
              rating: 5,
              comment: "Lovely parcel",
              auctionPublicId: "a-1",
              auctionTitle: "Sunset Cove 256m",
              createdAt: "2026-05-10T12:00:00Z",
            },
            {
              reviewerPublicId: "u-2",
              reviewerDisplayName: "Bob",
              rating: 4,
              comment: null,
              auctionPublicId: "a-2",
              auctionTitle: "Coniston Hill 512m",
              createdAt: "2026-05-08T12:00:00Z",
            },
          ],
          totalElements: 2,
          totalPages: 1,
          number: 0,
          size: 20,
        }),
      ),
    );

    const rendered = await GroupReviewsPage({
      params: Promise.resolve({ publicId: GROUP_PUBLIC_ID }),
      searchParams: Promise.resolve({}),
    });
    render(rendered, { wrapper: makeWrapper() });

    expect(
      screen.getByRole("heading", { name: /Sunset Estates/i }),
    ).toBeInTheDocument();
    expect(screen.getByTestId("group-rating-badge")).toBeInTheDocument();
    expect(screen.getByText("Lovely parcel")).toBeInTheDocument();
    expect(screen.getByText("Sunset Cove 256m")).toBeInTheDocument();
    expect(screen.getByText("Alice")).toBeInTheDocument();
    expect(screen.getByText("Bob")).toBeInTheDocument();
    // Rating-only review (Bob) has no comment text on the page.
    expect(screen.getByText("Coniston Hill 512m")).toBeInTheDocument();
  });

  it("renders the empty state when there are no reviews", async () => {
    mockGroupOk("Empty Group", { averageRating: null, reviewCount: 0 });
    server.use(
      http.get(`*/api/v1/realty/groups/${GROUP_PUBLIC_ID}/reviews`, () =>
        HttpResponse.json({
          content: [],
          totalElements: 0,
          totalPages: 0,
          number: 0,
          size: 20,
        }),
      ),
    );

    const rendered = await GroupReviewsPage({
      params: Promise.resolve({ publicId: GROUP_PUBLIC_ID }),
      searchParams: Promise.resolve({}),
    });
    render(rendered, { wrapper: makeWrapper() });

    expect(
      screen.getByText(/This group has no reviews yet/i),
    ).toBeInTheDocument();
  });

  it("calls notFound() when the group endpoint 404s", async () => {
    server.use(
      http.get(`*/api/v1/realty-groups/${GROUP_PUBLIC_ID}`, () =>
        HttpResponse.json({ status: 404, title: "Not Found" }, { status: 404 }),
      ),
    );
    await expect(
      GroupReviewsPage({
        params: Promise.resolve({ publicId: GROUP_PUBLIC_ID }),
        searchParams: Promise.resolve({}),
      }),
    ).rejects.toThrow("NEXT_NOT_FOUND");
  });

  it("calls notFound() on an empty publicId", async () => {
    await expect(
      GroupReviewsPage({
        params: Promise.resolve({ publicId: "" }),
        searchParams: Promise.resolve({}),
      }),
    ).rejects.toThrow("NEXT_NOT_FOUND");
  });

  it("renders pagination controls when totalPages > 1", async () => {
    mockGroupOk("Big Group", { averageRating: 4, reviewCount: 25 });
    server.use(
      http.get(`*/api/v1/realty/groups/${GROUP_PUBLIC_ID}/reviews`, () =>
        HttpResponse.json({
          content: [
            {
              reviewerPublicId: "u-1",
              reviewerDisplayName: "Alice",
              rating: 5,
              comment: "Great",
              auctionPublicId: "a-1",
              auctionTitle: "Parcel A",
              createdAt: "2026-05-10T12:00:00Z",
            },
          ],
          totalElements: 25,
          totalPages: 2,
          number: 0,
          size: 20,
        }),
      ),
    );

    const rendered = await GroupReviewsPage({
      params: Promise.resolve({ publicId: GROUP_PUBLIC_ID }),
      searchParams: Promise.resolve({}),
    });
    render(rendered, { wrapper: makeWrapper() });

    expect(screen.getByRole("navigation", { name: /Pagination/i })).toBeInTheDocument();
    expect(screen.getByText(/Page 1 of 2/)).toBeInTheDocument();
    const nextLink = screen.getByRole("link", { name: /^Next$/ });
    expect(nextLink.getAttribute("href")).toBe(
      `/realty/groups/${GROUP_PUBLIC_ID}/reviews?page=1`,
    );
  });
});
