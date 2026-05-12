import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { AdminGroupAuditTab } from "./AdminGroupAuditTab";

const GROUP_ID = "00000000-0000-0000-0000-000000000001";

function seedAudit(rows: unknown[]) {
  server.use(
    http.get(`*/api/v1/admin/audit`, () =>
      HttpResponse.json({
        content: rows,
        totalElements: rows.length,
        totalPages: 1,
        number: 0,
        size: 25,
      }),
    ),
  );
}

describe("AdminGroupAuditTab", () => {
  it("renders the empty state when no audit rows exist for the group", async () => {
    seedAudit([]);
    renderWithProviders(<AdminGroupAuditTab groupPublicId={GROUP_ID} />);
    await waitFor(() =>
      expect(
        screen.getByTestId("admin-group-audit-empty"),
      ).toBeInTheDocument(),
    );
  });

  it("renders one row per audit entry", async () => {
    seedAudit([
      {
        actionId: 101,
        actionType: "REALTY_GROUP_SUSPENDED",
        adminDisplayName: "Admin User",
        notes: "Initial suspension",
        createdAt: "2026-05-12T12:00:00Z",
      },
      {
        actionId: 102,
        actionType: "REALTY_GROUP_BULK_LISTINGS_SUSPENDED",
        adminDisplayName: "Admin User",
        notes: null,
        createdAt: "2026-05-12T12:05:00Z",
      },
    ]);
    renderWithProviders(<AdminGroupAuditTab groupPublicId={GROUP_ID} />);
    await waitFor(() =>
      expect(
        screen.getByTestId("admin-group-audit-row-101"),
      ).toBeInTheDocument(),
    );
    expect(
      screen.getByTestId("admin-group-audit-row-102"),
    ).toBeInTheDocument();
  });

  it("shows the error state when the audit query fails", async () => {
    server.use(
      http.get(`*/api/v1/admin/audit`, () =>
        HttpResponse.json({ status: 500 }, { status: 500 }),
      ),
    );
    renderWithProviders(<AdminGroupAuditTab groupPublicId={GROUP_ID} />);
    await waitFor(() =>
      expect(
        screen.getByTestId("admin-group-audit-error"),
      ).toBeInTheDocument(),
    );
  });
});
