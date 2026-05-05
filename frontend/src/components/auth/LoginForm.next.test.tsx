// frontend/src/components/auth/LoginForm.next.test.tsx
// Isolated file so the top-level vi.mock can provide useSearchParams("next=/auction/42")
// without conflicting with the module cache in LoginForm.test.tsx.
import { describe, it, expect, vi } from "vitest";
import { renderWithProviders, screen, userEvent, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { authHandlers } from "@/test/msw/handlers";
import { LoginForm } from "./LoginForm";

const mockPush = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: mockPush }),
  useSearchParams: () => new URLSearchParams("next=/auction/42"),
  usePathname: () => "/login",
}));

describe("LoginForm — next-redirect", () => {
  it("redirects to next param after login when present", async () => {
    const user = userEvent.setup();
    server.use(authHandlers.loginSuccess());

    renderWithProviders(<LoginForm />);
    await user.type(screen.getByLabelText(/username/i), "alice");
    await user.type(screen.getByLabelText(/password/i), "anything");
    await user.click(screen.getByRole("button", { name: /sign in/i }));

    await waitFor(() => {
      expect(mockPush).toHaveBeenCalledWith("/auction/42");
    });
  });
});
