import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, userEvent, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import type { RealtyGroupRowDto } from "@/types/realty";
import { AdminRealtyGroupActionModal } from "./AdminRealtyGroupActionModal";

function makeRow(overrides: Partial<RealtyGroupRowDto> = {}): RealtyGroupRowDto {
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

describe("AdminRealtyGroupActionModal (edit)", () => {
  it("renders no dialog when closed", () => {
    renderWithProviders(
      <AdminRealtyGroupActionModal
        open={false}
        action="edit"
        row={makeRow()}
        onClose={() => {}}
      />,
    );
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("shows the cooldown bypass banner and pre-fills the name", () => {
    renderWithProviders(
      <AdminRealtyGroupActionModal
        open
        action="edit"
        row={makeRow()}
        onClose={() => {}}
      />,
    );
    expect(screen.getByText(/bypass the 30-day rename cooldown/i)).toBeInTheDocument();
    expect(screen.getByTestId("admin-realty-edit-name")).toHaveValue("Mainland Realty");
  });

  it("disables submit when the name has not changed", () => {
    renderWithProviders(
      <AdminRealtyGroupActionModal
        open
        action="edit"
        row={makeRow()}
        onClose={() => {}}
      />,
    );
    expect(screen.getByTestId("admin-realty-edit-submit")).toBeDisabled();
  });

  it("calls the admin update endpoint on submit", async () => {
    let called = false;
    server.use(
      http.patch(
        "*/api/v1/admin/realty-groups/00000000-0000-0000-0000-000000000001",
        async ({ request }) => {
          called = true;
          const body = (await request.json()) as { name: string };
          return HttpResponse.json({
            publicId: "00000000-0000-0000-0000-000000000001",
            name: body.name,
            slug: "mainland-realty",
            description: null,
            website: null,
            logoLightUrl: null, logoDarkUrl: null,
            coverLightUrl: null, coverDarkUrl: null,
            memberSince: "2026-04-01T10:00:00Z",
            leader: {
              userPublicId: "11111111-1111-1111-1111-111111111111",
              displayName: "Leader Lee",
              avatarUrl: null,
            },
            agents: [],
            memberSeatLimit: 50,
            memberCount: 1,
          });
        },
      ),
    );
    renderWithProviders(
      <AdminRealtyGroupActionModal
        open
        action="edit"
        row={makeRow()}
        onClose={() => {}}
      />,
    );
    const input = screen.getByTestId("admin-realty-edit-name");
    await userEvent.clear(input);
    await userEvent.type(input, "Renamed Group");
    await userEvent.click(screen.getByTestId("admin-realty-edit-submit"));
    await waitFor(() => expect(called).toBe(true));
  });
});

describe("AdminRealtyGroupActionModal (dissolve)", () => {
  it("disables the confirm button until the group name is typed", async () => {
    renderWithProviders(
      <AdminRealtyGroupActionModal
        open
        action="dissolve"
        row={makeRow({ name: "Tricky" })}
        onClose={() => {}}
      />,
    );
    const confirm = screen.getByTestId("admin-realty-dissolve-confirm");
    expect(confirm).toBeDisabled();
    await userEvent.type(
      screen.getByTestId("admin-realty-dissolve-input"),
      "Tricky",
    );
    expect(confirm).not.toBeDisabled();
  });

  it("calls the admin dissolve endpoint when confirmed", async () => {
    let called = false;
    server.use(
      http.delete(
        "*/api/v1/admin/realty-groups/00000000-0000-0000-0000-000000000001",
        () => {
          called = true;
          return new HttpResponse(null, { status: 204 });
        },
      ),
    );
    renderWithProviders(
      <AdminRealtyGroupActionModal
        open
        action="dissolve"
        row={makeRow({ name: "X" })}
        onClose={() => {}}
      />,
    );
    await userEvent.type(
      screen.getByTestId("admin-realty-dissolve-input"),
      "X",
    );
    await userEvent.click(screen.getByTestId("admin-realty-dissolve-confirm"));
    await waitFor(() => expect(called).toBe(true));
  });
});
