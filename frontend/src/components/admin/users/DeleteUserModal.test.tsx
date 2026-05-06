import { describe, it, expect, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/test/render";
import { server } from "@/test/msw/server";
import { adminUserDeletionHandlers } from "@/test/msw/handlers";
import { DeleteUserModal } from "./DeleteUserModal";

const PID = "11111111-1111-1111-1111-111111111111";

describe("DeleteUserModal", () => {
  it("disables delete button when admin note is empty", () => {
    renderWithProviders(
      <DeleteUserModal publicId={PID} userLabel="user@example.com" onClose={() => {}} />
    );
    expect(screen.getByRole("button", { name: /Delete user/i })).toBeDisabled();
  });

  it("enables delete button once admin note is typed", async () => {
    renderWithProviders(<DeleteUserModal publicId={PID} onClose={() => {}} />);
    await userEvent.type(
      screen.getByPlaceholderText(/Reason for deletion/),
      "Test reason"
    );
    expect(screen.getByRole("button", { name: /Delete user/i })).not.toBeDisabled();
  });

  it("renders 409 precondition error inline", async () => {
    server.use(adminUserDeletionHandlers.delete409Auctions(PID, [100, 101]));
    renderWithProviders(<DeleteUserModal publicId={PID} onClose={() => {}} />);
    await userEvent.type(screen.getByPlaceholderText(/Reason for deletion/), "Test");
    await userEvent.click(screen.getByRole("button", { name: /Delete user/i }));
    expect(await screen.findByText(/ACTIVE_AUCTIONS/)).toBeInTheDocument();
    expect(screen.getByText(/#100/)).toBeInTheDocument();
    expect(screen.getByText(/#101/)).toBeInTheDocument();
  });

  it("calls onClose on success", async () => {
    server.use(adminUserDeletionHandlers.deleteSuccess(PID));
    const onClose = vi.fn();
    renderWithProviders(<DeleteUserModal publicId={PID} onClose={onClose} />);
    await userEvent.type(
      screen.getByPlaceholderText(/Reason for deletion/),
      "GDPR request"
    );
    await userEvent.click(screen.getByRole("button", { name: /Delete user/i }));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  it("includes label in heading when provided", () => {
    renderWithProviders(
      <DeleteUserModal publicId={PID} userLabel="alice@example.com" onClose={() => {}} />
    );
    expect(screen.getByText(/Delete user alice@example\.com/i)).toBeInTheDocument();
  });

  it("closes when backdrop is clicked", async () => {
    const onClose = vi.fn();
    renderWithProviders(<DeleteUserModal publicId={PID} onClose={onClose} />);
    await userEvent.click(screen.getByRole("button", { name: /Cancel/i }));
    expect(onClose).toHaveBeenCalled();
  });
});
