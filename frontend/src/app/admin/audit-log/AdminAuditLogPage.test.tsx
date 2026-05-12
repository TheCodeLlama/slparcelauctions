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
    // The renderer now consumes labelFor(row.actionType) so the human label
    // appears in the row's <td> instead of the raw enum name.
    const tableCell = await screen.findByText("Resolve dispute");
    expect(tableCell.tagName).toBe("TD");
    await userEvent.click(tableCell);
    expect(await screen.findByText(/"foo"/)).toBeInTheDocument();
  });
});
