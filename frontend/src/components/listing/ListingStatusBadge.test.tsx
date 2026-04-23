import { describe, expect, it } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import type { AuctionStatus } from "@/types/auction";
import { ListingStatusBadge } from "./ListingStatusBadge";

const ALL_STATUSES: AuctionStatus[] = [
  "DRAFT",
  "DRAFT_PAID",
  "VERIFICATION_PENDING",
  "VERIFICATION_FAILED",
  "ACTIVE",
  "ENDED",
  "ESCROW_PENDING",
  "ESCROW_FUNDED",
  "TRANSFER_PENDING",
  "COMPLETED",
  "CANCELLED",
  "EXPIRED",
  "DISPUTED",
  "SUSPENDED",
];

describe("ListingStatusBadge", () => {
  it.each(ALL_STATUSES)("renders a visible label for %s", (status) => {
    const { container } = renderWithProviders(
      <ListingStatusBadge status={status} />,
    );
    const badge = container.querySelector(`[data-status="${status}"]`);
    expect(badge).not.toBeNull();
    // Non-empty, trimmed label text.
    expect(badge?.textContent?.trim().length ?? 0).toBeGreaterThan(0);
  });

  it("marks cancelled with strike-through styling", () => {
    renderWithProviders(<ListingStatusBadge status="CANCELLED" />);
    expect(screen.getByText("Cancelled")).toHaveClass("line-through");
  });
});
