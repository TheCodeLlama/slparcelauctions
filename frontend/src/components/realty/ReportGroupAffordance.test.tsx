import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { ReportGroupAffordance } from "./ReportGroupAffordance";

const GROUP_ID = "00000000-0000-0000-0000-0000000000aa";
const GROUP_SLUG = "mainland-realty";

describe("ReportGroupAffordance", () => {
  it("renders nothing for anonymous visitors", () => {
    renderWithProviders(
      <ReportGroupAffordance
        groupPublicId={GROUP_ID}
        groupSlug={GROUP_SLUG}
      />,
      { auth: "anonymous" },
    );
    expect(screen.queryByTestId("report-group-button")).not.toBeInTheDocument();
  });

  it("renders the Report button when the authenticated viewer is not a member", async () => {
    server.use(
      http.get("*/api/v1/me/realty-groups", () =>
        HttpResponse.json([]),
      ),
    );
    renderWithProviders(
      <ReportGroupAffordance
        groupPublicId={GROUP_ID}
        groupSlug={GROUP_SLUG}
      />,
      { auth: "authenticated" },
    );
    await waitFor(() =>
      expect(screen.getByTestId("report-group-button")).toBeInTheDocument(),
    );
  });

  it("renders nothing when the authenticated viewer is a member", async () => {
    server.use(
      http.get("*/api/v1/me/realty-groups", () =>
        HttpResponse.json([
          {
            publicId: GROUP_ID,
            name: "Mainland Realty",
            slug: GROUP_SLUG,
            logoLightUrl: null, logoDarkUrl: null,
            memberCount: 1,
            memberSince: "2026-04-01T10:00:00Z",
          },
        ]),
      ),
    );
    renderWithProviders(
      <ReportGroupAffordance
        groupPublicId={GROUP_ID}
        groupSlug={GROUP_SLUG}
      />,
      { auth: "authenticated" },
    );
    // Give the my-groups query a tick to resolve and confirm the button
    // stays hidden.
    await waitFor(() => {
      // Use queryBy to assert non-presence after the data loaded.
      expect(screen.queryByTestId("report-group-button")).not.toBeInTheDocument();
    });
  });
});
