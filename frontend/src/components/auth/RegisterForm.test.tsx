// frontend/src/components/auth/RegisterForm.test.tsx
import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderWithProviders, screen, userEvent, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { authHandlers } from "@/test/msw/handlers";
import { RegisterForm } from "./RegisterForm";

const mockPush = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: mockPush }),
  useSearchParams: () => new URLSearchParams(),
  usePathname: () => "/register",
}));

describe("RegisterForm", () => {
  beforeEach(() => {
    mockPush.mockReset();
  });

  it("submits valid input and redirects to /dashboard on success", async () => {
    const user = userEvent.setup();
    server.use(authHandlers.registerSuccess());

    renderWithProviders(<RegisterForm />);

    await user.type(screen.getByLabelText(/username/i), "newuser");
    await user.type(screen.getByLabelText(/^password$/i), "hunter22ab");
    await user.type(screen.getByLabelText(/confirm password/i), "hunter22ab");
    await user.click(screen.getByLabelText(/terms/i));
    await user.click(screen.getByRole("button", { name: /create account/i }));

    await waitFor(() => {
      expect(mockPush).toHaveBeenCalledWith("/dashboard");
    });
  });

  it("shows username-exists error inline when backend returns 409", async () => {
    const user = userEvent.setup();
    server.use(authHandlers.registerUsernameExists());

    renderWithProviders(<RegisterForm />);

    await user.type(screen.getByLabelText(/username/i), "taken");
    await user.type(screen.getByLabelText(/^password$/i), "hunter22ab");
    await user.type(screen.getByLabelText(/confirm password/i), "hunter22ab");
    await user.click(screen.getByLabelText(/terms/i));
    await user.click(screen.getByRole("button", { name: /create account/i }));

    expect(await screen.findByText(/already taken/i)).toBeInTheDocument();
    expect(mockPush).not.toHaveBeenCalled();
  });

  it("blocks submission when passwords don't match", async () => {
    const user = userEvent.setup();
    renderWithProviders(<RegisterForm />);

    await user.type(screen.getByLabelText(/username/i), "test");
    await user.type(screen.getByLabelText(/^password$/i), "hunter22ab");
    await user.type(screen.getByLabelText(/confirm password/i), "different22ab");
    await user.click(screen.getByLabelText(/terms/i));
    await user.click(screen.getByRole("button", { name: /create account/i }));

    expect(await screen.findByText(/passwords don't match/i)).toBeInTheDocument();
    expect(mockPush).not.toHaveBeenCalled();
  });

  it("blocks submission when terms checkbox is not checked", async () => {
    const user = userEvent.setup();
    renderWithProviders(<RegisterForm />);

    await user.type(screen.getByLabelText(/username/i), "test");
    await user.type(screen.getByLabelText(/^password$/i), "hunter22ab");
    await user.type(screen.getByLabelText(/confirm password/i), "hunter22ab");
    // Skip the terms checkbox
    await user.click(screen.getByRole("button", { name: /create account/i }));

    expect(await screen.findByText(/must accept the terms/i)).toBeInTheDocument();
    expect(mockPush).not.toHaveBeenCalled();
  });

  it("shows the password strength indicator as the user types", async () => {
    const user = userEvent.setup();
    renderWithProviders(<RegisterForm />);

    await user.type(screen.getByLabelText(/^password$/i), "hunter22ab");

    expect(await screen.findByText("Good")).toBeInTheDocument();
  });
});
