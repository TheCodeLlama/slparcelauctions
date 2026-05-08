import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { authHandlers, userHandlers } from "@/test/msw/handlers";
import VerifiedDashboardLayout from "./layout";

const mockReplace = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: mockReplace }),
  usePathname: vi.fn(() => "/dashboard/overview"),
  useSearchParams: () => new URLSearchParams(),
}));

describe("VerifiedDashboardLayout", () => {
  beforeEach(() => {
    mockReplace.mockReset();
    server.use(authHandlers.refreshSuccess());
  });

  it("renders children for verified users (chrome moved to (onboarded) layout)", async () => {
    server.use(userHandlers.meVerified());
    renderWithProviders(
      <VerifiedDashboardLayout>
        <div data-testid="child-content">Child content</div>
      </VerifiedDashboardLayout>,
      { auth: "authenticated" },
    );

    expect(await screen.findByTestId("child-content")).toBeInTheDocument();
    // Tabs + h1 moved into (onboarded)/layout.tsx
    expect(screen.queryByText("Dashboard")).not.toBeInTheDocument();
    expect(screen.queryByRole("tab", { name: "Overview" })).not.toBeInTheDocument();
  });

  it("redirects unverified users to /dashboard/verify", async () => {
    server.use(userHandlers.meUnverified());
    renderWithProviders(
      <VerifiedDashboardLayout>
        <div>Should not appear</div>
      </VerifiedDashboardLayout>,
      { auth: "authenticated" },
    );

    await waitFor(() => {
      expect(mockReplace).toHaveBeenCalledWith("/dashboard/verify");
    });
  });
});
