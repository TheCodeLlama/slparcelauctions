import { describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, userEvent, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { realtyGroupSuspensionHandlers } from "@/test/msw/handlers";
import { AdminGroupSuspensionModal } from "./AdminGroupSuspensionModal";

const GROUP_ID = "00000000-0000-0000-0000-000000000001";

describe("AdminGroupSuspensionModal", () => {
  it("submits a permanent ban and calls onClose on success", async () => {
    server.use(realtyGroupSuspensionHandlers.issueSuccess());
    const onClose = vi.fn();
    renderWithProviders(
      <AdminGroupSuspensionModal
        open
        groupPublicId={GROUP_ID}
        onClose={onClose}
      />,
    );
    await userEvent.click(
      screen.getByTestId("admin-group-suspension-modal-mode-permanent"),
    );
    await userEvent.type(
      screen.getByTestId("admin-group-suspension-modal-notes"),
      "Fraud confirmed via dispute escalation.",
    );
    await userEvent.click(
      screen.getByTestId("admin-group-suspension-modal-submit"),
    );
    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  it("requires notes before allowing submission", async () => {
    const onClose = vi.fn();
    renderWithProviders(
      <AdminGroupSuspensionModal
        open
        groupPublicId={GROUP_ID}
        onClose={onClose}
      />,
    );
    await userEvent.click(
      screen.getByTestId("admin-group-suspension-modal-mode-permanent"),
    );
    await userEvent.click(
      screen.getByTestId("admin-group-suspension-modal-submit"),
    );
    await waitFor(() =>
      expect(
        screen.getByTestId("admin-group-suspension-modal-error"),
      ).toBeInTheDocument(),
    );
    expect(onClose).not.toHaveBeenCalled();
  });

  it("surfaces a server error inline", async () => {
    server.use(
      http.post(
        `*/api/v1/admin/realty-groups/${GROUP_ID}/suspensions`,
        () =>
          HttpResponse.json(
            {
              status: 409,
              code: "GROUP_ALREADY_SUSPENDED",
              title: "Group already suspended",
              detail: "This group already has an active suspension.",
            },
            { status: 409 },
          ),
      ),
    );
    const onClose = vi.fn();
    renderWithProviders(
      <AdminGroupSuspensionModal
        open
        groupPublicId={GROUP_ID}
        onClose={onClose}
      />,
    );
    await userEvent.click(
      screen.getByTestId("admin-group-suspension-modal-mode-permanent"),
    );
    await userEvent.type(
      screen.getByTestId("admin-group-suspension-modal-notes"),
      "Stacking suspension attempt.",
    );
    await userEvent.click(
      screen.getByTestId("admin-group-suspension-modal-submit"),
    );
    await waitFor(() =>
      expect(
        screen.getByTestId("admin-group-suspension-modal-error"),
      ).toBeInTheDocument(),
    );
    expect(onClose).not.toHaveBeenCalled();
  });
});
