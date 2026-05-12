import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import type { RealtyGroupSlGroup } from "@/types/realty";
import { AdminGroupSlGroupsTab } from "./AdminGroupSlGroupsTab";

const GROUP_ID = "00000000-0000-0000-0000-000000000001";
const SL_GROUP_ID = "11111111-1111-1111-1111-111111111111";

const ROW: RealtyGroupSlGroup = {
  publicId: SL_GROUP_ID,
  slGroupUuid: "22222222-2222-2222-2222-222222222222",
  slGroupName: "Sunset Estates",
  verified: true,
  verifiedAt: "2026-05-01T12:00:00Z",
  verifiedVia: "FOUNDER_TERMINAL",
  pending: null,
  founderAvatarUuid: "33333333-3333-3333-3333-333333333333",
};

function seedList(rows: RealtyGroupSlGroup[]) {
  server.use(
    http.get(`*/api/v1/realty/groups/${GROUP_ID}/sl-groups`, () =>
      HttpResponse.json(rows),
    ),
  );
}

describe("AdminGroupSlGroupsTab", () => {
  it("renders the empty state when no SL groups are registered", async () => {
    seedList([]);
    renderWithProviders(
      <AdminGroupSlGroupsTab groupPublicId={GROUP_ID} />,
    );
    await waitFor(() =>
      expect(
        screen.getByTestId("admin-group-sl-groups-empty"),
      ).toBeInTheDocument(),
    );
  });

  it("renders a row per registration with admin actions", async () => {
    seedList([ROW]);
    renderWithProviders(
      <AdminGroupSlGroupsTab groupPublicId={GROUP_ID} />,
    );
    await waitFor(() =>
      expect(
        screen.getByTestId(`admin-sl-group-row-${SL_GROUP_ID}`),
      ).toBeInTheDocument(),
    );
    expect(
      screen.getByTestId(`admin-sl-group-recheck-${SL_GROUP_ID}`),
    ).toBeInTheDocument();
    expect(
      screen.getByTestId(`admin-sl-group-force-${SL_GROUP_ID}`),
    ).toBeInTheDocument();
  });

  it("shows an error message when the list query fails", async () => {
    server.use(
      http.get(`*/api/v1/realty/groups/${GROUP_ID}/sl-groups`, () =>
        HttpResponse.json({ status: 500 }, { status: 500 }),
      ),
    );
    renderWithProviders(
      <AdminGroupSlGroupsTab groupPublicId={GROUP_ID} />,
    );
    await waitFor(() =>
      expect(
        screen.getByTestId("admin-group-sl-groups-error"),
      ).toBeInTheDocument(),
    );
  });
});
