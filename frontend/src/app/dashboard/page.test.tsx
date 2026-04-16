import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { authHandlers, userHandlers } from "@/test/msw/handlers";
import DashboardIndex from "./page";

const mockReplace = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: mockReplace }),
  usePathname: vi.fn(() => "/dashboard"),
  useSearchParams: () => new URLSearchParams(),
}));

describe("DashboardIndex", () => {
  beforeEach(() => {
    mockReplace.mockReset();
    server.use(authHandlers.refreshSuccess());
  });

  it("redirects verified users to /dashboard/overview", async () => {
    server.use(userHandlers.meVerified());
    renderWithProviders(<DashboardIndex />, { auth: "authenticated" });

    await waitFor(() => {
      expect(mockReplace).toHaveBeenCalledWith("/dashboard/overview");
    });
  });

  it("redirects unverified users to /dashboard/verify", async () => {
    server.use(userHandlers.meUnverified());
    renderWithProviders(<DashboardIndex />, { auth: "authenticated" });

    await waitFor(() => {
      expect(mockReplace).toHaveBeenCalledWith("/dashboard/verify");
    });
  });
});
