import { describe, it, expect, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/test/render";
import { server } from "@/test/msw/server";
import { adminUserDeletionHandlers } from "@/test/msw/handlers";
import { DeleteUserModal } from "./DeleteUserModal";

describe("DeleteUserModal", () => {
  it("disables delete button when admin note is empty", () => {
    renderWithProviders(
      <DeleteUserModal userId={42} userEmail="user@example.com" onClose={() => {}} />
    );
    expect(screen.getByRole("button", { name: /Delete user/i })).toBeDisabled();
  });

  it("enables delete button once admin note is typed", async () => {
    renderWithProviders(<DeleteUserModal userId={42} onClose={() => {}} />);
    await userEvent.type(
      screen.getByPlaceholderText(/Reason for deletion/),
      "Test reason"
    );
    expect(screen.getByRole("button", { name: /Delete user/i })).not.toBeDisabled();
  });

  it("renders 409 precondition error inline", async () => {
    server.use(adminUserDeletionHandlers.delete409Auctions(42, [100, 101]));
    renderWithProviders(<DeleteUserModal userId={42} onClose={() => {}} />);
    await userEvent.type(screen.getByPlaceholderText(/Reason for deletion/), "Test");
    await userEvent.click(screen.getByRole("button", { name: /Delete user/i }));
    expect(await screen.findByText(/ACTIVE_AUCTIONS/)).toBeInTheDocument();
    expect(screen.getByText(/#100/)).toBeInTheDocument();
    expect(screen.getByText(/#101/)).toBeInTheDocument();
  });

  it("calls onClose on success", async () => {
    server.use(adminUserDeletionHandlers.deleteSuccess(42));
    const onClose = vi.fn();
    renderWithProviders(<DeleteUserModal userId={42} onClose={onClose} />);
    await userEvent.type(
      screen.getByPlaceholderText(/Reason for deletion/),
      "GDPR request"
    );
    await userEvent.click(screen.getByRole("button", { name: /Delete user/i }));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  it("includes email in heading when provided", () => {
    renderWithProviders(
      <DeleteUserModal userId={42} userEmail="alice@example.com" onClose={() => {}} />
    );
    expect(screen.getByText(/Delete user alice@example\.com/i)).toBeInTheDocument();
  });

  it("closes when backdrop is clicked", async () => {
    const onClose = vi.fn();
    renderWithProviders(<DeleteUserModal userId={42} onClose={onClose} />);
    // Click the backdrop (outermost div)
    await userEvent.click(screen.getByRole("button", { name: /Cancel/i }));
    expect(onClose).toHaveBeenCalled();
  });
});
