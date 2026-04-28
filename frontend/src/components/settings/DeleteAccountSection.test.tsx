import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/test/render";
import { server } from "@/test/msw/server";
import { userDeletionHandlers } from "@/test/msw/handlers";
import { DeleteAccountSection } from "./DeleteAccountSection";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn() }),
}));

describe("DeleteAccountSection", () => {
  beforeEach(() => {
    server.use(userDeletionHandlers.deleteSelfSuccess());
  });

  it("renders collapsed by default", () => {
    renderWithProviders(<DeleteAccountSection />);
    expect(screen.getByRole("button", { name: /Delete account/i })).toBeInTheDocument();
    expect(screen.queryByPlaceholderText(/Enter your password/i)).not.toBeInTheDocument();
  });

  it("expands on click", async () => {
    renderWithProviders(<DeleteAccountSection />);
    await userEvent.click(screen.getByRole("button", { name: /Delete account/i }));
    expect(screen.getByPlaceholderText(/Enter your password to confirm/i)).toBeInTheDocument();
  });

  it("disables delete button when password is empty", async () => {
    renderWithProviders(<DeleteAccountSection />);
    await userEvent.click(screen.getByRole("button", { name: /Delete account/i }));
    const button = screen.getByRole("button", { name: /Delete my account/i });
    expect(button).toBeDisabled();
  });

  it("enables delete button when password is typed", async () => {
    renderWithProviders(<DeleteAccountSection />);
    await userEvent.click(screen.getByRole("button", { name: /Delete account/i }));
    await userEvent.type(screen.getByPlaceholderText(/Enter your password to confirm/i), "mypassword");
    const button = screen.getByRole("button", { name: /Delete my account/i });
    expect(button).not.toBeDisabled();
  });

  it("renders precondition error inline on 409", async () => {
    server.use(userDeletionHandlers.deleteSelf409Auctions([100, 101]));
    renderWithProviders(<DeleteAccountSection />);
    await userEvent.click(screen.getByRole("button", { name: /Delete account/i }));
    await userEvent.type(screen.getByPlaceholderText(/Enter your password to confirm/i), "pw");
    await userEvent.click(screen.getByRole("button", { name: /Delete my account/i }));
    expect(await screen.findByText(/ACTIVE_AUCTIONS/)).toBeInTheDocument();
    expect(screen.getByText(/#100/)).toBeInTheDocument();
    expect(screen.getByText(/#101/)).toBeInTheDocument();
  });

  it("renders 'Incorrect password' on 403", async () => {
    server.use(userDeletionHandlers.deleteSelf403WrongPassword());
    renderWithProviders(<DeleteAccountSection />);
    await userEvent.click(screen.getByRole("button", { name: /Delete account/i }));
    await userEvent.type(screen.getByPlaceholderText(/Enter your password to confirm/i), "wrong");
    await userEvent.click(screen.getByRole("button", { name: /Delete my account/i }));
    expect(await screen.findByText(/Incorrect password/i)).toBeInTheDocument();
  });
});
