import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { EscrowDeadlineBadge } from "./EscrowDeadlineBadge";

describe("EscrowDeadlineBadge", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-05-01T12:00:00Z"));
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it("renders neutral tone when more than 24 hours remain", () => {
    // 48h in future
    const deadline = new Date("2026-05-03T12:00:00Z").toISOString();
    renderWithProviders(<EscrowDeadlineBadge deadline={deadline} />);
    const node = screen.getByText(/left/i);
    expect(node).toHaveAttribute("data-urgency", "neutral");
  });

  it("renders warning tone when 6-24 hours remain", () => {
    // 10h in future
    const deadline = new Date("2026-05-01T22:00:00Z").toISOString();
    renderWithProviders(<EscrowDeadlineBadge deadline={deadline} />);
    const node = screen.getByText(/left/i);
    expect(node).toHaveAttribute("data-urgency", "warning");
  });

  it("renders urgent tone when fewer than 6 hours remain", () => {
    // 2h in future
    const deadline = new Date("2026-05-01T14:00:00Z").toISOString();
    renderWithProviders(<EscrowDeadlineBadge deadline={deadline} />);
    const node = screen.getByText(/left/i);
    expect(node).toHaveAttribute("data-urgency", "urgent");
  });

  it("renders past tone when deadline is in the past", () => {
    const deadline = new Date("2026-04-30T12:00:00Z").toISOString();
    renderWithProviders(<EscrowDeadlineBadge deadline={deadline} />);
    const node = screen.getByText(/past deadline/i);
    expect(node).toHaveAttribute("data-urgency", "past");
  });

  it("accepts a custom label prop", () => {
    const deadline = new Date("2026-05-03T12:00:00Z").toISOString();
    renderWithProviders(
      <EscrowDeadlineBadge deadline={deadline} label="until funding deadline" />,
    );
    expect(
      screen.getByText(/until funding deadline/i),
    ).toBeInTheDocument();
  });
});
