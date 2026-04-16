import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import {
  authHandlers,
  userHandlers,
  verificationHandlers,
} from "@/test/msw/handlers";
import {
  mockUnverifiedCurrentUser,
  mockVerifiedCurrentUser,
} from "@/test/msw/fixtures";
import { UnverifiedVerifyFlow } from "./UnverifiedVerifyFlow";

const mockReplace = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: mockReplace }),
  usePathname: vi.fn(() => "/dashboard/verify"),
  useSearchParams: () => new URLSearchParams(),
}));

function setup() {
  server.use(authHandlers.refreshSuccess());
}

describe("UnverifiedVerifyFlow", () => {
  beforeEach(() => {
    mockReplace.mockReset();
  });

  it("renders explanatory copy and verification code display", async () => {
    setup();
    server.use(
      userHandlers.meUnverified(),
      verificationHandlers.activeNone(),
    );
    renderWithProviders(<UnverifiedVerifyFlow />, {
      auth: "authenticated",
    });

    // Wait for the inner VerificationCodeDisplay to finish loading and
    // render the "Generate" button (async query resolution through MSW).
    expect(
      await screen.findByRole("button", {
        name: /generate verification code/i,
      }),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/link your second life avatar/i),
    ).toBeInTheDocument();
    expect(screen.getByText(/how to verify/i)).toBeInTheDocument();
    expect(
      screen.getByText(
        /i've entered the code in-world/i,
      ),
    ).toBeInTheDocument();
  });

  it("transitions to /dashboard/overview when /me returns verified: true after polling", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    setup();
    server.use(
      userHandlers.meUnverified(),
      verificationHandlers.activeNone(),
    );
    renderWithProviders(<UnverifiedVerifyFlow />, {
      auth: "authenticated",
    });

    // Wait for initial unverified render.
    await waitFor(() => {
      expect(
        screen.getByText(/link your second life avatar/i),
      ).toBeInTheDocument();
    });

    // Swap the /me handler so the next poll returns a verified user.
    server.use(userHandlers.meVerified());

    // Advance past the 5000ms refetchInterval.
    await vi.advanceTimersByTimeAsync(5100);

    await waitFor(() => {
      expect(mockReplace).toHaveBeenCalledWith("/dashboard/overview");
    });

    vi.useRealTimers();
  });

  it("manual refresh button triggers immediate /me refetch", async () => {
    setup();

    // Start unverified, then swap to verified after the manual click.
    server.use(
      userHandlers.meUnverified(),
      verificationHandlers.activeNone(),
    );
    const { rerender } = renderWithProviders(<UnverifiedVerifyFlow />, {
      auth: "authenticated",
    });

    const refreshBtn = await screen.findByRole("button", {
      name: /refresh my status/i,
    });

    // Swap to verified before clicking refresh.
    server.use(userHandlers.meVerified());

    // Use native click to avoid userEvent timer issues.
    refreshBtn.click();

    await waitFor(() => {
      expect(mockReplace).toHaveBeenCalledWith("/dashboard/overview");
    });
  });
});
