import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { AgentFeePreview } from "./AgentFeePreview";

describe("AgentFeePreview", () => {
  it("computes payout = startingBid - floor(startingBid * 0.05) - floor(startingBid * rate)", () => {
    // L$1000 - floor(50) - floor(20) = L$930
    render(<AgentFeePreview startingBid={1000} groupName="Sunset Realty" agentFeeRate={0.02} />);
    expect(screen.getByText(/Sunset Realty/i)).toBeInTheDocument();
    expect(screen.getByText(/L\$930/)).toBeInTheDocument();
    expect(screen.getByText(/2%/)).toBeInTheDocument();
  });

  it("shows 0% group fee when rate is 0", () => {
    render(<AgentFeePreview startingBid={1000} groupName="Free Group" agentFeeRate={0} />);
    expect(screen.getByText(/0%/)).toBeInTheDocument();
    expect(screen.getByText(/L\$950/)).toBeInTheDocument();
  });

  it("renders nothing for a non-positive startingBid", () => {
    const { container } = render(
      <AgentFeePreview startingBid={0} groupName="X" agentFeeRate={0.02} />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it("uses floor rounding (rate 0.0333 on L$1000 -> fee 33 -> payout 917)", () => {
    render(<AgentFeePreview startingBid={1000} groupName="G" agentFeeRate={0.0333} />);
    expect(screen.getByText(/L\$917/)).toBeInTheDocument();
  });
});
