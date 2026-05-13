import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { realtyGroupReportHandlers } from "@/test/msw/handlers";
import type { AdminRealtyGroupReportRow } from "@/types/realty";
import { AdminGroupReportsTab } from "./AdminGroupReportsTab";

const GROUP_ID = "00000000-0000-0000-0000-000000000001";
const REPORT_ID = "cccccccc-cccc-cccc-cccc-cccccccccccc";

const ROW: AdminRealtyGroupReportRow = {
  publicId: REPORT_ID,
  groupPublicId: GROUP_ID,
  groupName: "Mainland Realty",
  reporter: {
    publicId: "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee",
    displayName: "Reporter User",
  },
  reason: "FRAUDULENT_LISTINGS",
  status: "OPEN",
  createdAt: "2026-05-12T12:00:00Z",
};

describe("AdminGroupReportsTab", () => {
  it("renders the empty state when no reports are filed", async () => {
    server.use(realtyGroupReportHandlers.adminListEmpty());
    renderWithProviders(
      <AdminGroupReportsTab groupPublicId={GROUP_ID} />,
    );
    await waitFor(() =>
      expect(
        screen.getByTestId("admin-group-reports-empty"),
      ).toBeInTheDocument(),
    );
  });

  it("lists reports filed against this group with a detail link", async () => {
    server.use(realtyGroupReportHandlers.adminListSuccess([ROW]));
    renderWithProviders(
      <AdminGroupReportsTab groupPublicId={GROUP_ID} />,
    );
    await waitFor(() =>
      expect(
        screen.getByTestId(`admin-group-report-row-${REPORT_ID}`),
      ).toBeInTheDocument(),
    );
    const link = screen.getByTestId(
      `admin-group-report-detail-link-${REPORT_ID}`,
    );
    expect(link).toHaveAttribute(
      "href",
      `/admin/groups/reports/${REPORT_ID}`,
    );
  });

  it("shows an error message when the list query fails", async () => {
    server.use(
      http.get(`*/api/v1/admin/realty-groups/reports`, () =>
        HttpResponse.json({ status: 500 }, { status: 500 }),
      ),
    );
    renderWithProviders(
      <AdminGroupReportsTab groupPublicId={GROUP_ID} />,
    );
    await waitFor(() =>
      expect(
        screen.getByTestId("admin-group-reports-error"),
      ).toBeInTheDocument(),
    );
  });
});
