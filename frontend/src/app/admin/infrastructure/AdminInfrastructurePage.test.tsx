import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { server } from "@/test/msw/server";
import {
  adminBotPoolHandlers,
  adminTerminalsHandlers,
  adminReconciliationHandlers,
  adminWithdrawalsHandlers,
} from "@/test/msw/handlers";
import { AdminInfrastructurePage } from "./AdminInfrastructurePage";

describe("AdminInfrastructurePage", () => {
  it("renders all 5 sections with empty data", async () => {
    server.use(
      adminBotPoolHandlers.healthEmpty(),
      adminTerminalsHandlers.listEmpty(),
      adminReconciliationHandlers.runsEmpty(),
      adminWithdrawalsHandlers.listEmpty(),
      adminWithdrawalsHandlers.available(10000),
    );
    renderWithProviders(<AdminInfrastructurePage />);
    expect(await screen.findByText("Infrastructure")).toBeInTheDocument();
    expect(await screen.findByText("Bot pool")).toBeInTheDocument();
    expect(screen.getByText("Terminals")).toBeInTheDocument();
    expect(screen.getByText("Available to withdraw")).toBeInTheDocument();
    expect(screen.getByText("Daily balance reconciliation")).toBeInTheDocument();
  });

  it("shows empty state messages when no data", async () => {
    server.use(
      adminBotPoolHandlers.healthEmpty(),
      adminTerminalsHandlers.listEmpty(),
      adminReconciliationHandlers.runsEmpty(),
      adminWithdrawalsHandlers.listEmpty(),
      adminWithdrawalsHandlers.available(0),
    );
    renderWithProviders(<AdminInfrastructurePage />);
    expect(await screen.findByText("No bots registered yet.")).toBeInTheDocument();
    expect(screen.getByText("No terminals registered yet.")).toBeInTheDocument();
    expect(screen.getByText("No reconciliation runs yet.")).toBeInTheDocument();
  });

  it("shows available balance from API", async () => {
    server.use(
      adminBotPoolHandlers.healthEmpty(),
      adminTerminalsHandlers.listEmpty(),
      adminReconciliationHandlers.runsEmpty(),
      adminWithdrawalsHandlers.listEmpty(),
      adminWithdrawalsHandlers.available(75000),
    );
    renderWithProviders(<AdminInfrastructurePage />);
    expect(await screen.findByText("L$ 75,000")).toBeInTheDocument();
  });
});
