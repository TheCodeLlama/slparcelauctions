import { describe, expect, it } from "vitest";
import { renderWithProviders, screen, userEvent, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { slGroupAdminHandlers } from "@/test/msw/handlers";
import type { AdminRealtyGroupSlGroup } from "@/types/realty";
import { AdminSlGroupDriftRow } from "./AdminSlGroupDriftRow";

const GROUP_ID = "00000000-0000-0000-0000-000000000001";
const SL_GROUP_ID = "11111111-1111-1111-1111-111111111111";

const HEALTHY_ROW: AdminRealtyGroupSlGroup = {
  publicId: SL_GROUP_ID,
  slGroupUuid: "22222222-2222-2222-2222-222222222222",
  slGroupName: "Sunset Estates",
  verified: true,
  verifiedAt: "2026-05-01T12:00:00Z",
  verifiedVia: "FOUNDER_TERMINAL",
  founderAvatarUuid: "33333333-3333-3333-3333-333333333333",
  currentFounderUuid: "33333333-3333-3333-3333-333333333333",
  lastRevalidatedAt: "2026-05-12T12:00:00Z",
  consecutiveFetchFailures: 0,
  driftDetectedAt: null,
  driftReason: null,
  driftAcknowledgedAt: null,
  driftAcknowledgedByAdmin: null,
  unregisteredAt: null,
  unregisteredByAdmin: null,
  unregisterReason: null,
};

const DRIFTED_ROW: AdminRealtyGroupSlGroup = {
  ...HEALTHY_ROW,
  currentFounderUuid: "44444444-4444-4444-4444-444444444444",
  driftDetectedAt: "2026-05-10T12:00:00Z",
  driftReason: "FOUNDER_CHANGED",
};

function renderRow(row: AdminRealtyGroupSlGroup) {
  return renderWithProviders(
    <table>
      <tbody>
        <AdminSlGroupDriftRow groupPublicId={GROUP_ID} row={row} />
      </tbody>
    </table>,
  );
}

describe("AdminSlGroupDriftRow", () => {
  it("renders the healthy status when no drift is detected", () => {
    renderRow(HEALTHY_ROW);
    expect(
      screen.getByTestId(`admin-sl-group-status-healthy-${SL_GROUP_ID}`),
    ).toBeInTheDocument();
  });

  it("shows the drift status pill and an Ack action when drift is detected", () => {
    renderRow(DRIFTED_ROW);
    expect(
      screen.getByTestId(`admin-sl-group-status-drift-${SL_GROUP_ID}`),
    ).toBeInTheDocument();
    expect(
      screen.getByTestId(`admin-sl-group-ack-${SL_GROUP_ID}`),
    ).toBeInTheDocument();
  });

  it("opens the force-unregister modal and requires a reason", async () => {
    renderRow(HEALTHY_ROW);
    await userEvent.click(
      screen.getByTestId(`admin-sl-group-force-${SL_GROUP_ID}`),
    );
    await userEvent.click(
      screen.getByTestId(`admin-sl-group-force-confirm-${SL_GROUP_ID}`),
    );
    await waitFor(() =>
      expect(
        screen.getByTestId(`admin-sl-group-force-error-${SL_GROUP_ID}`),
      ).toBeInTheDocument(),
    );
  });

  it("submits a recheck via the hook", async () => {
    server.use(slGroupAdminHandlers.recheckNoDrift());
    renderRow(HEALTHY_ROW);
    await userEvent.click(
      screen.getByTestId(`admin-sl-group-recheck-${SL_GROUP_ID}`),
    );
    // Hook fires without throwing — no row-level error appears.
    await waitFor(() =>
      expect(
        screen.queryByTestId(`admin-sl-group-row-error-${SL_GROUP_ID}`),
      ).not.toBeInTheDocument(),
    );
  });
});
