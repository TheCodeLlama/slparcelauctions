import { describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, userEvent, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";

const mockReplace = vi.fn();

vi.mock("next/navigation", () => ({
  useRouter: () => ({
    push: vi.fn(),
    replace: mockReplace,
    refresh: vi.fn(),
    back: vi.fn(),
    forward: vi.fn(),
    prefetch: vi.fn(),
  }),
  usePathname: () => "/admin/groups",
  useSearchParams: () => new URLSearchParams(),
}));

import { AdminRealtyGroupsPage } from "./AdminRealtyGroupsPage";

function pageOf(rows: unknown[], total = rows.length) {
  return {
    content: rows,
    totalElements: total,
    totalPages: Math.max(1, Math.ceil(total / 25)),
    number: 0,
    size: 25,
    first: true,
    last: true,
    numberOfElements: rows.length,
    empty: rows.length === 0,
  };
}

function makeRow(overrides: Record<string, unknown> = {}) {
  return {
    publicId: "00000000-0000-0000-0000-000000000001",
    name: "Mainland Realty",
    slug: "mainland-realty",
    leaderPublicId: "11111111-1111-1111-1111-111111111111",
    leaderDisplayName: "Leader Lee",
    memberCount: 3,
    dissolved: false,
    createdAt: "2026-04-01T10:00:00Z",
    dissolvedAt: null,
    ...overrides,
  };
}

describe("AdminRealtyGroupsPage", () => {
  it("renders the heading and an empty state when no rows match", async () => {
    server.use(
      http.get("*/api/v1/admin/realty-groups", () =>
        HttpResponse.json(pageOf([])),
      ),
    );
    renderWithProviders(<AdminRealtyGroupsPage />);
    expect(
      screen.getByRole("heading", { name: "Realty Groups" }),
    ).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.getByTestId("admin-realty-empty")).toBeInTheDocument(),
    );
  });

  it("renders rows returned by the admin list endpoint", async () => {
    server.use(
      http.get("*/api/v1/admin/realty-groups", () =>
        HttpResponse.json(pageOf([makeRow()])),
      ),
    );
    renderWithProviders(<AdminRealtyGroupsPage />);
    await waitFor(() =>
      expect(
        screen.getByTestId(
          "admin-realty-row-00000000-0000-0000-0000-000000000001",
        ),
      ).toBeInTheDocument(),
    );
  });

  it("writes the chosen status to the URL when a chip is clicked", async () => {
    mockReplace.mockReset();
    server.use(
      http.get("*/api/v1/admin/realty-groups", () =>
        HttpResponse.json(pageOf([])),
      ),
    );
    renderWithProviders(<AdminRealtyGroupsPage />);
    await waitFor(() =>
      expect(screen.getByTestId("admin-realty-status-dissolved")).toBeInTheDocument(),
    );
    await userEvent.click(screen.getByTestId("admin-realty-status-dissolved"));
    expect(mockReplace).toHaveBeenCalledWith(
      expect.stringContaining("status=dissolved"),
    );
  });

  it("requests the admin endpoint with the active status by default", async () => {
    let lastQuery = "";
    server.use(
      http.get("*/api/v1/admin/realty-groups", ({ request }) => {
        lastQuery = new URL(request.url).search;
        return HttpResponse.json(pageOf([]));
      }),
    );
    renderWithProviders(<AdminRealtyGroupsPage />);
    await waitFor(() => expect(lastQuery).toContain("status=active"));
  });
});
