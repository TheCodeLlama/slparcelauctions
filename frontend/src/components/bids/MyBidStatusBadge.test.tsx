import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import type { MyBidStatus } from "@/types/auction";
import { MyBidStatusBadge } from "./MyBidStatusBadge";

const cases: Array<{ status: MyBidStatus; label: string }> = [
  { status: "WINNING", label: "Winning" },
  { status: "OUTBID", label: "Outbid" },
  { status: "WON", label: "Won" },
  { status: "LOST", label: "Lost" },
  { status: "RESERVE_NOT_MET", label: "Reserve not met" },
  { status: "CANCELLED", label: "Cancelled" },
  { status: "SUSPENDED", label: "Suspended" },
];

describe("MyBidStatusBadge", () => {
  it.each(cases)("renders the $label label for $status", ({ status, label }) => {
    render(<MyBidStatusBadge status={status} />);
    expect(screen.getByText(label)).toBeInTheDocument();
  });
});
