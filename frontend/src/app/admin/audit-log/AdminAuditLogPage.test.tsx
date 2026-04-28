import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { server } from "@/test/msw/server";
import { adminAuditLogHandlers } from "@/test/msw/handlers";
import { AdminAuditLogPage } from "./AdminAuditLogPage";
import userEvent from "@testing-library/user-event";

describe("AdminAuditLogPage", () => {
  it("renders empty state", async () => {
    server.use(adminAuditLogHandlers.listEmpty());
    renderWithProviders(<AdminAuditLogPage />);
    expect(
      await screen.findByText(/No audit log entries match/i)
    ).toBeInTheDocument();
  });

  it("renders rows + expands details on click", async () => {
    server.use(
      adminAuditLogHandlers.listWithItems([
        {
          id: 1,
          occurredAt: "2026-04-27T14:22:00Z",
          actionType: "DISPUTE_RESOLVED",
          adminUserId: 1,
          adminEmail: "admin@example.com",
          targetType: "DISPUTE",
          targetId: 47,
          notes: "Verified payment.",
          details: { foo: "bar" },
        },
      ])
    );
    renderWithProviders(<AdminAuditLogPage />);
    // Use getAllByText — the action type appears in both the filter <option> and the table <td>.
    const cells = await screen.findAllByText("DISPUTE_RESOLVED");
    const tableCell = cells.find((el) => el.tagName === "TD");
    expect(tableCell).toBeInTheDocument();
    await userEvent.click(tableCell!);
    expect(await screen.findByText(/"foo"/)).toBeInTheDocument();
  });
});
