import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import MyGroupsPage from "./page";

function seedMyGroups(items: unknown[]) {
  server.use(
    http.get("*/api/v1/me/realty-groups", () => HttpResponse.json(items)),
  );
}

describe("/groups/me", () => {
  it("renders the my-groups page shell once loaded", async () => {
    seedMyGroups([]);
    renderWithProviders(<MyGroupsPage />, { auth: "authenticated" });
    await waitFor(() =>
      expect(screen.getByTestId("my-groups-page")).toBeInTheDocument(),
    );
  });

  it("renders a row per group the caller belongs to", async () => {
    seedMyGroups([
      {
        publicId: "00000000-0000-0000-0000-000000000001",
        name: "Mainland Realty",
        slug: "mainland-realty",
        logoLightUrl: null, logoDarkUrl: null,
        memberCount: 4,
        memberSince: "2026-04-01T10:00:00Z",
      },
    ]);
    renderWithProviders(<MyGroupsPage />, { auth: "authenticated" });
    await waitFor(() =>
      expect(screen.getByText("Mainland Realty")).toBeInTheDocument(),
    );
  });
});
