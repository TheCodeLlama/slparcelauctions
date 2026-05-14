import { describe, it, expect } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import type { RealtyGroupSummaryDto } from "@/types/realty";
import { EditGroupAffordance } from "./EditGroupAffordance";

function summary(
  overrides: Partial<RealtyGroupSummaryDto> = {},
): RealtyGroupSummaryDto {
  return {
    publicId: "00000000-0000-0000-0000-000000000001",
    name: "Mainland Realty",
    slug: "mainland-realty",
    logoUrl: null,
    memberCount: 1,
    memberSince: "2026-04-01T10:00:00Z",
    ...overrides,
  };
}

describe("EditGroupAffordance", () => {
  it("renders the manage link when the caller belongs to the group", async () => {
    server.use(
      http.get("*/api/v1/me/realty-groups", () =>
        HttpResponse.json([summary({ slug: "mainland-realty" })]),
      ),
    );
    renderWithProviders(<EditGroupAffordance slug="mainland-realty" />);
    const link = await screen.findByTestId("edit-group-affordance");
    expect(link.tagName).toBe("A");
    expect(link.getAttribute("href")).toBe(
      "/groups/mainland-realty/profile",
    );
  });

  it("renders nothing when the caller is not a member", async () => {
    server.use(
      http.get("*/api/v1/me/realty-groups", () =>
        HttpResponse.json([summary({ slug: "other-group" })]),
      ),
    );
    renderWithProviders(<EditGroupAffordance slug="mainland-realty" />);
    await waitFor(() => {
      // wait for the query to settle
    });
    expect(
      screen.queryByTestId("edit-group-affordance"),
    ).not.toBeInTheDocument();
  });

  it("renders nothing when the affiliations request errors (anonymous viewer)", async () => {
    server.use(
      http.get("*/api/v1/me/realty-groups", () =>
        HttpResponse.json({ status: 401, title: "Unauthorized" }, { status: 401 }),
      ),
    );
    renderWithProviders(<EditGroupAffordance slug="mainland-realty" />);
    await waitFor(() => {
      // settle
    });
    expect(
      screen.queryByTestId("edit-group-affordance"),
    ).not.toBeInTheDocument();
  });
});
