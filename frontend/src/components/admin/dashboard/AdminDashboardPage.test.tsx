import { describe, it, expect } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { AdminDashboardPage } from "./AdminDashboardPage";
import { server } from "@/test/msw/server";
import { adminHandlers } from "@/test/msw/handlers";

function wrap() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <AdminDashboardPage />
    </QueryClientProvider>
  );
}

describe("AdminDashboardPage", () => {
  it("renders all 9 numbers from the API response", async () => {
    server.use(
      adminHandlers.statsSuccess({
        queues: { openFraudFlags: 7, pendingPayments: 3, activeDisputes: 1 },
        platform: {
          activeListings: 42,
          totalUsers: 381,
          activeEscrows: 12,
          completedSales: 156,
          lindenGrossVolume: 4_827_500,
          lindenCommissionEarned: 241_375,
        },
      })
    );

    wrap();

    await waitFor(() => expect(screen.queryByText("7")).not.toBeNull());
    expect(screen.getByText("3")).toBeInTheDocument();
    expect(screen.getByText("1")).toBeInTheDocument();
    expect(screen.getByText("42")).toBeInTheDocument();
    expect(screen.getByText("381")).toBeInTheDocument();
    expect(screen.getByText("12")).toBeInTheDocument();
    expect(screen.getByText("156")).toBeInTheDocument();
    expect(screen.getByText("L$ 4,827,500")).toBeInTheDocument();
    expect(screen.getByText("L$ 241,375")).toBeInTheDocument();
  });
});
