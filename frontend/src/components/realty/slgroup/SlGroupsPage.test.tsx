import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, userEvent, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { realtySlGroupHandlers } from "@/test/msw/handlers";
import type { RealtyGroupSlGroup } from "@/types/realty";
import { SlGroupsPage } from "./SlGroupsPage";

const GROUP_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

function row(overrides: Partial<RealtyGroupSlGroup> = {}): RealtyGroupSlGroup {
  return {
    publicId: "11111111-1111-1111-1111-111111111111",
    slGroupUuid: "22222222-2222-2222-2222-222222222222",
    slGroupName: "Sunset Estates",
    verified: true,
    verifiedAt: "2026-05-12T20:00:00Z",
    verifiedVia: "ABOUT_TEXT",
    pending: null,
    founderAvatarUuid: "33333333-3333-3333-3333-333333333333",
    ...overrides,
  };
}

describe("SlGroupsPage", () => {
  it("renders the empty state when no SL groups are registered", async () => {
    // Default handler in defaultHandlers returns an empty list.
    renderWithProviders(<SlGroupsPage groupPublicId={GROUP_ID} />);
    await waitFor(() =>
      expect(screen.getByTestId("sl-groups-empty")).toBeInTheDocument(),
    );
    expect(
      screen.getByText(/No SL groups registered yet/i),
    ).toBeInTheDocument();
  });

  it("renders one row per registered SL group", async () => {
    server.use(
      realtySlGroupHandlers.listSuccess<RealtyGroupSlGroup>([
        row(),
        row({
          publicId: "44444444-4444-4444-4444-444444444444",
          slGroupName: null,
          verified: false,
          verifiedAt: null,
          verifiedVia: null,
          pending: {
            verificationCode: "9X8Y7Z",
            verificationCodeExpiresAt: new Date(
              Date.now() + 30 * 60 * 1000,
            ).toISOString(),
            lastPolledAt: null,
            pollAttempts: 0,
          },
          founderAvatarUuid: null,
        }),
      ]),
    );
    renderWithProviders(<SlGroupsPage groupPublicId={GROUP_ID} />);
    await waitFor(() =>
      expect(screen.getByTestId("sl-groups-table")).toBeInTheDocument(),
    );
    expect(screen.getAllByTestId("sl-group-row")).toHaveLength(2);
  });

  it("opens the register modal when the CTA is clicked", async () => {
    renderWithProviders(<SlGroupsPage groupPublicId={GROUP_ID} />);
    await waitFor(() =>
      expect(screen.getByTestId("register-sl-group-button")).toBeInTheDocument(),
    );
    await userEvent.click(screen.getByTestId("register-sl-group-button"));
    expect(
      screen.getByRole("dialog", { name: /Register SL Group/i }),
    ).toBeInTheDocument();
  });

  it("surfaces an error block when the list request fails", async () => {
    server.use(
      http.get("*/api/v1/realty/groups/:publicId/sl-groups", () =>
        HttpResponse.json(
          {
            status: 500,
            title: "Internal server error",
          },
          { status: 500 },
        ),
      ),
    );
    renderWithProviders(<SlGroupsPage groupPublicId={GROUP_ID} />);
    await waitFor(() =>
      expect(screen.getByTestId("sl-groups-error")).toBeInTheDocument(),
    );
  });
});
