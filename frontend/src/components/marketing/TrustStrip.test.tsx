import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { TrustStrip } from "./TrustStrip";

describe("TrustStrip", () => {
  it("renders all four trust items", () => {
    render(<TrustStrip />);
    expect(screen.getByText(/Escrow on every sale/i)).toBeInTheDocument();
    expect(screen.getByText(/Verified sellers/i)).toBeInTheDocument();
    expect(screen.getByText(/Fair bidding/i)).toBeInTheDocument();
    expect(screen.getByText(/Money-back guarantee/i)).toBeInTheDocument();
  });
});
