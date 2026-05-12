import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { GroupStatusPill } from "./GroupStatusPill";

describe("GroupStatusPill", () => {
  it("renders nothing when status is null", () => {
    renderWithProviders(<GroupStatusPill status={null} />);
    expect(screen.queryByTestId("group-status-pill")).not.toBeInTheDocument();
  });

  it("renders nothing when status is LIFTED", () => {
    renderWithProviders(<GroupStatusPill status="LIFTED" />);
    expect(screen.queryByTestId("group-status-pill")).not.toBeInTheDocument();
  });

  it("renders nothing when status is EXPIRED", () => {
    renderWithProviders(<GroupStatusPill status="EXPIRED" />);
    expect(screen.queryByTestId("group-status-pill")).not.toBeInTheDocument();
  });

  it("renders a 'Suspended until <formatted-date>' pill for ACTIVE_TIMED", () => {
    // 2026-06-15T12:00:00Z — toLocaleDateString in a Node test environment
    // typically renders as "Jun 15, 2026" (en-US default).
    renderWithProviders(
      <GroupStatusPill
        status="ACTIVE_TIMED"
        expiresAt="2026-06-15T12:00:00Z"
      />,
    );
    const pill = screen.getByTestId("group-status-pill");
    expect(pill).toBeInTheDocument();
    expect(pill.textContent).toMatch(/^Suspended until /);
    // Formatted as "Mon DD, YYYY" via toLocaleDateString.
    expect(pill.textContent).toMatch(/Jun \d{1,2}, 2026/);
  });

  it("renders 'Banned' for ACTIVE_PERMANENT", () => {
    renderWithProviders(<GroupStatusPill status="ACTIVE_PERMANENT" />);
    const pill = screen.getByTestId("group-status-pill");
    expect(pill).toBeInTheDocument();
    expect(pill.textContent).toBe("Banned");
  });

  it("shows the reason in the tooltip (title attribute) when provided", () => {
    renderWithProviders(
      <GroupStatusPill
        status="ACTIVE_PERMANENT"
        reason="Repeat fraud reports"
      />,
    );
    const pill = screen.getByTestId("group-status-pill");
    expect(pill.getAttribute("title")).toBe("Repeat fraud reports");
  });

  it("omits the title attribute when no reason is provided", () => {
    renderWithProviders(<GroupStatusPill status="ACTIVE_PERMANENT" />);
    const pill = screen.getByTestId("group-status-pill");
    expect(pill.getAttribute("title")).toBeNull();
  });

  it("falls back to 'Suspended' when ACTIVE_TIMED has no expiresAt", () => {
    renderWithProviders(<GroupStatusPill status="ACTIVE_TIMED" />);
    const pill = screen.getByTestId("group-status-pill");
    expect(pill.textContent).toBe("Suspended");
  });
});
