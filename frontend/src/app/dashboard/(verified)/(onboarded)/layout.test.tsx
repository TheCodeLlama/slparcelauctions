import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { authHandlers, userHandlers } from "@/test/msw/handlers";
import { mockVerifiedCurrentUser } from "@/test/msw/fixtures";
import OnboardedLayout from "./layout";

const mockReplace = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: mockReplace }),
  usePathname: vi.fn(() => "/dashboard/overview"),
  useSearchParams: () => new URLSearchParams(),
}));

describe("OnboardedLayout", () => {
  beforeEach(() => {
    mockReplace.mockReset();
    server.use(authHandlers.refreshSuccess());
  });

  it("renders dashboard chrome + children when both onboarding flags are true", async () => {
    server.use(userHandlers.meVerified({
      ...mockVerifiedCurrentUser,
      avatarStepCompleted: true,
      displayNameStepCompleted: true,
    }));

    renderWithProviders(
      <OnboardedLayout>
        <div data-testid="child-content">Child content</div>
      </OnboardedLayout>,
      { auth: "authenticated" },
    );

    expect(await screen.findByText("Dashboard")).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Overview" })).toBeInTheDocument();
    expect(screen.getByTestId("child-content")).toBeInTheDocument();
    expect(mockReplace).not.toHaveBeenCalled();
  });

  it("redirects to /dashboard/avatar when avatar step incomplete", async () => {
    server.use(userHandlers.meVerified({
      ...mockVerifiedCurrentUser,
      avatarStepCompleted: false,
      displayNameStepCompleted: false,
    }));

    renderWithProviders(
      <OnboardedLayout>
        <div>Should not appear</div>
      </OnboardedLayout>,
      { auth: "authenticated" },
    );

    await waitFor(() => {
      expect(mockReplace).toHaveBeenCalledWith("/dashboard/avatar");
    });
  });

  it("redirects to /dashboard/display-name when avatar done but name incomplete", async () => {
    server.use(userHandlers.meVerified({
      ...mockVerifiedCurrentUser,
      avatarStepCompleted: true,
      displayNameStepCompleted: false,
    }));

    renderWithProviders(
      <OnboardedLayout>
        <div>Should not appear</div>
      </OnboardedLayout>,
      { auth: "authenticated" },
    );

    await waitFor(() => {
      expect(mockReplace).toHaveBeenCalledWith("/dashboard/display-name");
    });
  });
});
