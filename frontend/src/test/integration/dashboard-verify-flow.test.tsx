import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { screen, waitFor, act } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/test/render";
import { server } from "@/test/msw/server";
import {
  authHandlers,
  userHandlers,
  verificationHandlers,
} from "@/test/msw/handlers";
import { UnverifiedVerifyFlow } from "@/components/user/UnverifiedVerifyFlow";

const replace = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({
    replace,
    push: vi.fn(),
    back: vi.fn(),
    refresh: vi.fn(),
  }),
  usePathname: () => "/dashboard/verify",
  useSearchParams: () => new URLSearchParams(),
}));

describe("Dashboard verify -> overview transition (integration smoke)", () => {
  beforeEach(() => {
    replace.mockReset();
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText: vi.fn(() => Promise.resolve()) },
      configurable: true,
    });
    vi.useFakeTimers({ shouldAdvanceTime: true });
    server.use(authHandlers.refreshSuccess());
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("user generates a code, copies it, simulates verification, and transitions to overview", async () => {
    // Setup: unverified user, no active code
    server.use(userHandlers.meUnverified(), verificationHandlers.activeNone());
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderWithProviders(<UnverifiedVerifyFlow />, { auth: "authenticated" });

    // Wait for initial render — explanatory copy and generate button
    await screen.findByText(/to bid, list parcels/i);
    await screen.findByRole("button", { name: /generate verification code/i });

    // Swap both handlers before clicking: generate returns a code, and the
    // subsequent invalidateQueries refetch of the active-code query also sees it.
    server.use(
      verificationHandlers.generateSuccess("654321", "2026-04-14T21:15:00Z"),
      verificationHandlers.activeExists("654321", "2026-04-14T21:15:00Z"),
    );
    await user.click(
      screen.getByRole("button", { name: /generate verification code/i }),
    );

    // Code should appear
    expect(await screen.findByText("654321")).toBeInTheDocument();

    // Copy the code
    await user.click(
      screen.getByRole("button", { name: /copy to clipboard/i }),
    );

    // Simulate verification: swap /me to verified
    server.use(userHandlers.meVerified());

    // Advance past the 5s poll interval
    await act(async () => {
      vi.advanceTimersByTime(5100);
    });

    // The useEffect should trigger router.replace
    await waitFor(() =>
      expect(replace).toHaveBeenCalledWith("/dashboard/overview"),
    );
  });
});
