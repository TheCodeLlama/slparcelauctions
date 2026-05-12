import { describe, expect, it } from "vitest";
import { renderWithProviders, screen, userEvent, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { realtySlGroupHandlers } from "@/test/msw/handlers";
import { RegisterSlGroupModal } from "./RegisterSlGroupModal";

const GROUP_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

function renderModal(
  props: Partial<Parameters<typeof RegisterSlGroupModal>[0]> = {},
) {
  return renderWithProviders(
    <RegisterSlGroupModal
      open
      onClose={() => {}}
      groupPublicId={GROUP_ID}
      {...props}
    />,
  );
}

describe("RegisterSlGroupModal", () => {
  it("renders the input stage when open", () => {
    renderModal();
    expect(
      screen.getByRole("dialog", { name: /Register SL Group/i }),
    ).toBeInTheDocument();
    expect(screen.getByTestId("register-input-stage")).toBeInTheDocument();
    expect(screen.getByTestId("register-uuid-input")).toBeInTheDocument();
  });

  it("does not render when closed", () => {
    renderModal({ open: false });
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("shows a validation error when submit is clicked with empty input", async () => {
    renderModal();
    await userEvent.click(screen.getByTestId("register-submit-button"));
    expect(
      screen.getByText(/Enter an SL group UUID/i),
    ).toBeInTheDocument();
  });

  it("transitions to the instructions stage on 201", async () => {
    server.use(realtySlGroupHandlers.registerSuccess());
    renderModal();
    await userEvent.type(
      screen.getByTestId("register-uuid-input"),
      "22222222-2222-2222-2222-222222222222",
    );
    await userEvent.click(screen.getByTestId("register-submit-button"));
    await waitFor(() =>
      expect(
        screen.getByTestId("register-instructions-stage"),
      ).toBeInTheDocument(),
    );
    expect(
      screen.getByTestId("verification-code-display").textContent,
    ).toBe("SLPA-1A2B3C4D5E6F");
    expect(screen.getByTestId("register-done-button")).toBeInTheDocument();
  });

  it("shows an inline error when the SL group is already registered (409)", async () => {
    server.use(realtySlGroupHandlers.registerAlreadyRegistered());
    renderModal();
    await userEvent.type(
      screen.getByTestId("register-uuid-input"),
      "22222222-2222-2222-2222-222222222222",
    );
    await userEvent.click(screen.getByTestId("register-submit-button"));
    await waitFor(() =>
      expect(
        screen.getByText(/already registered/i),
      ).toBeInTheDocument(),
    );
    // Should remain on the input stage.
    expect(screen.getByTestId("register-input-stage")).toBeInTheDocument();
  });

  it("shows an inline error when the caller lacks permission (403)", async () => {
    server.use(realtySlGroupHandlers.registerForbidden());
    renderModal();
    await userEvent.type(
      screen.getByTestId("register-uuid-input"),
      "22222222-2222-2222-2222-222222222222",
    );
    await userEvent.click(screen.getByTestId("register-submit-button"));
    await waitFor(() =>
      expect(
        screen.getByText(/do not have permission/i),
      ).toBeInTheDocument(),
    );
  });
});
