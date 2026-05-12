import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, userEvent, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { bulkSuspendListingsHandlers } from "@/test/msw/handlers";
import { AdminGroupBulkListingsTab } from "./AdminGroupBulkListingsTab";

const GROUP_ID = "00000000-0000-0000-0000-000000000001";

describe("AdminGroupBulkListingsTab", () => {
  it("renders both cascade action buttons", () => {
    renderWithProviders(
      <AdminGroupBulkListingsTab groupPublicId={GROUP_ID} />,
    );
    expect(
      screen.getByTestId("admin-group-bulk-suspend-button"),
    ).toBeInTheDocument();
    expect(
      screen.getByTestId("admin-group-bulk-reinstate-button"),
    ).toBeInTheDocument();
  });

  it("bulk-suspends and surfaces the suspended-count result", async () => {
    server.use(
      bulkSuspendListingsHandlers.suspendAllSuccess({ suspendedCount: 5 }),
    );
    renderWithProviders(
      <AdminGroupBulkListingsTab groupPublicId={GROUP_ID} />,
    );
    await userEvent.click(
      screen.getByTestId("admin-group-bulk-suspend-button"),
    );
    await userEvent.type(
      screen.getByTestId("admin-group-bulk-suspend-reason"),
      "Cascading bulk-suspend test",
    );
    await userEvent.click(
      screen.getByTestId("admin-group-bulk-suspend-confirm"),
    );
    await waitFor(() =>
      expect(
        screen.getByTestId("admin-group-bulk-suspend-result"),
      ).toHaveTextContent(/Suspended 5 listings/),
    );
  });

  it("requires a reason before suspending", async () => {
    renderWithProviders(
      <AdminGroupBulkListingsTab groupPublicId={GROUP_ID} />,
    );
    await userEvent.click(
      screen.getByTestId("admin-group-bulk-suspend-button"),
    );
    await userEvent.click(
      screen.getByTestId("admin-group-bulk-suspend-confirm"),
    );
    await waitFor(() =>
      expect(
        screen.getByTestId("admin-group-bulk-suspend-error"),
      ).toBeInTheDocument(),
    );
  });

  it("surfaces a server error when reinstate-all fails", async () => {
    server.use(
      http.post(
        `*/api/v1/admin/realty-groups/${GROUP_ID}/listings/reinstate-all`,
        () =>
          HttpResponse.json(
            {
              status: 409,
              code: "BULK_REINSTATE_FAILED",
              title: "Reinstate failed",
              detail: "Could not reinstate listings.",
            },
            { status: 409 },
          ),
      ),
    );
    renderWithProviders(
      <AdminGroupBulkListingsTab groupPublicId={GROUP_ID} />,
    );
    await userEvent.click(
      screen.getByTestId("admin-group-bulk-reinstate-button"),
    );
    await userEvent.click(
      screen.getByTestId("admin-group-bulk-reinstate-confirm"),
    );
    await waitFor(() =>
      expect(
        screen.getByTestId("admin-group-bulk-reinstate-error"),
      ).toBeInTheDocument(),
    );
  });
});
