import { describe, expect, test, vi, beforeEach } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { server } from "@/test/msw/server";
import { authHandlers, preferencesHandlers, resetPreferences } from "@/test/msw/handlers";
import NotificationSettingsPage from "./page";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => "/settings/notifications",
}));

describe("/settings/notifications page", () => {
  beforeEach(() => {
    resetPreferences();
    server.use(authHandlers.refreshSuccess(), ...preferencesHandlers);
  });

  test("renders the preferences page title (via layout) when authenticated", async () => {
    renderWithProviders(<NotificationSettingsPage />, { auth: "authenticated" });
    expect(
      await screen.findByText(/In-app and system notifications always deliver/i)
    ).toBeInTheDocument();
  });
});
