import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/render";
import { server } from "@/test/msw/server";
import { adminHandlers, authHandlers } from "@/test/msw/handlers";
import { mockAdminUser } from "@/test/msw/fixtures";
import { AdminShell } from "./AdminShell";

vi.mock("next/navigation", () => ({
  usePathname: () => "/admin",
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}));

describe("AdminShell", () => {
  it("renders Dashboard and Fraud Flags nav links", async () => {
    server.use(
      authHandlers.refreshSuccess(mockAdminUser),
      adminHandlers.statsSuccess()
    );

    renderWithProviders(
      <AdminShell>
        <div>content</div>
      </AdminShell>,
      { auth: "authenticated", authUser: mockAdminUser }
    );

    expect(screen.getByRole("link", { name: "Dashboard" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Fraud Flags/i })).toBeInTheDocument();
  });

  it("shows ADMIN role chip in footer", async () => {
    server.use(
      authHandlers.refreshSuccess(mockAdminUser),
      adminHandlers.statsSuccess()
    );

    renderWithProviders(
      <AdminShell>
        <div>content</div>
      </AdminShell>,
      { auth: "authenticated", authUser: mockAdminUser }
    );

    expect(screen.getByText("ADMIN")).toBeInTheDocument();
  });

  it("renders fraud flag badge when open count is non-zero", async () => {
    server.use(
      authHandlers.refreshSuccess(mockAdminUser),
      adminHandlers.statsSuccess({
        queues: { openFraudFlags: 5, pendingPayments: 0, activeDisputes: 0 },
        platform: {
          activeListings: 0,
          totalUsers: 0,
          activeEscrows: 0,
          completedSales: 0,
          lindenGrossVolume: 0,
          lindenCommissionEarned: 0,
        },
      })
    );

    renderWithProviders(
      <AdminShell>
        <div>content</div>
      </AdminShell>,
      { auth: "authenticated", authUser: mockAdminUser }
    );

    expect(await screen.findByText("5")).toBeInTheDocument();
  });
});
