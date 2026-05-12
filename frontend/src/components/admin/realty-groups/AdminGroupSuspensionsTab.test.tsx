import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, userEvent, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { realtyGroupSuspensionHandlers } from "@/test/msw/handlers";
import type { RealtyGroupSuspension } from "@/types/realty";
import { AdminGroupSuspensionsTab } from "./AdminGroupSuspensionsTab";

const GROUP_ID = "00000000-0000-0000-0000-000000000001";
const SUSPENSION_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

const ACTIVE_TIMED_ROW: RealtyGroupSuspension = {
  publicId: SUSPENSION_ID,
  reason: "TOS_VIOLATION",
  notes: "Repeated TOS violations",
  issuedAt: "2026-05-12T12:00:00Z",
  expiresAt: "2026-05-19T12:00:00Z",
  liftedAt: null,
  liftedNotes: null,
  issuedByAdmin: {
    publicId: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
    displayName: "Admin User",
  },
  liftedByAdmin: null,
  status: "ACTIVE_TIMED",
};

describe("AdminGroupSuspensionsTab", () => {
  it("renders the empty state when the group has no suspensions", async () => {
    server.use(realtyGroupSuspensionHandlers.listEmpty());
    renderWithProviders(
      <AdminGroupSuspensionsTab groupPublicId={GROUP_ID} />,
    );
    await waitFor(() =>
      expect(
        screen.getByTestId("admin-group-suspensions-empty"),
      ).toBeInTheDocument(),
    );
    expect(
      screen.getByTestId("admin-group-suspensions-issue-button"),
    ).toBeInTheDocument();
  });

  it("renders the suspension history table and a Lift action for active rows", async () => {
    server.use(realtyGroupSuspensionHandlers.listSuccess([ACTIVE_TIMED_ROW]));
    renderWithProviders(
      <AdminGroupSuspensionsTab groupPublicId={GROUP_ID} />,
    );
    await waitFor(() =>
      expect(
        screen.getByTestId(`admin-group-suspension-row-${SUSPENSION_ID}`),
      ).toBeInTheDocument(),
    );
    expect(
      screen.getByTestId(`admin-group-suspension-lift-${SUSPENSION_ID}`),
    ).toBeInTheDocument();
  });

  it("shows an error message when the suspensions query fails", async () => {
    server.use(
      http.get(
        `*/api/v1/admin/realty-groups/${GROUP_ID}/suspensions`,
        () => HttpResponse.json({ status: 500 }, { status: 500 }),
      ),
    );
    renderWithProviders(
      <AdminGroupSuspensionsTab groupPublicId={GROUP_ID} />,
    );
    await waitFor(() =>
      expect(
        screen.getByTestId("admin-group-suspensions-error"),
      ).toBeInTheDocument(),
    );
  });

  it("opens the issue modal when the header button is clicked", async () => {
    server.use(realtyGroupSuspensionHandlers.listEmpty());
    renderWithProviders(
      <AdminGroupSuspensionsTab groupPublicId={GROUP_ID} />,
    );
    await waitFor(() =>
      expect(
        screen.getByTestId("admin-group-suspensions-issue-button"),
      ).toBeInTheDocument(),
    );
    await userEvent.click(
      screen.getByTestId("admin-group-suspensions-issue-button"),
    );
    expect(
      screen.getByTestId("admin-group-suspension-modal"),
    ).toBeInTheDocument();
  });
});
