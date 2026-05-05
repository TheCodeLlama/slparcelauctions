// frontend/src/components/auth/LoginForm.test.tsx
import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderWithProviders, screen, userEvent, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { authHandlers } from "@/test/msw/handlers";
import { LoginForm } from "./LoginForm";

const mockPush = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: mockPush }),
  useSearchParams: () => new URLSearchParams(),
  usePathname: () => "/login",
}));

describe("LoginForm", () => {
  beforeEach(() => {
    mockPush.mockReset();
  });

  it("submits valid credentials and redirects to /dashboard on success", async () => {
    const user = userEvent.setup();
    server.use(authHandlers.loginSuccess());

    renderWithProviders(<LoginForm />);

    await user.type(screen.getByLabelText(/username/i), "alice");
    await user.type(screen.getByLabelText(/password/i), "anything");
    await user.click(screen.getByRole("button", { name: /sign in/i }));

    await waitFor(() => {
      expect(mockPush).toHaveBeenCalledWith("/dashboard");
    });
  });

  it("shows form-level error on invalid credentials (NOT field-level)", async () => {
    const user = userEvent.setup();
    server.use(authHandlers.loginInvalidCredentials());

    renderWithProviders(<LoginForm />);

    await user.type(screen.getByLabelText(/username/i), "wrong");
    await user.type(screen.getByLabelText(/password/i), "wrong");
    await user.click(screen.getByRole("button", { name: /sign in/i }));

    // Form-level error appears in the alert region, not under a specific field.
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(/incorrect/i);
    expect(mockPush).not.toHaveBeenCalled();
  });

  it("blocks submission with empty fields", async () => {
    const user = userEvent.setup();
    renderWithProviders(<LoginForm />);

    await user.click(screen.getByRole("button", { name: /sign in/i }));

    expect(await screen.findByText(/at least 3 characters/i)).toBeInTheDocument();
  });

  it("renders the 'Signed in for 7 days on this device' helper text", () => {
    renderWithProviders(<LoginForm />);
    expect(screen.getByText(/signed in for 7 days on this device/i)).toBeInTheDocument();
  });

  // The ?next= redirect test lives in LoginForm.next.test.tsx because vi.doMock
  // cannot override a module that is already statically imported in the same file.
  // That file uses a top-level vi.mock to provide useSearchParams("next=/auction/42").
  it.todo("redirects to next param after login when present — see LoginForm.next.test.tsx");
});
