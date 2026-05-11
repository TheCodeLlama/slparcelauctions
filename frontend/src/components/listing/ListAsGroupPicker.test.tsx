import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ListAsGroupPicker } from "./ListAsGroupPicker";
import type { ListingEligibleGroup } from "@/types/realty";

const groups: ListingEligibleGroup[] = [
  { publicId: "g1", name: "Sunset Realty", slug: "sunset", logoUrl: null, agentFeeRate: 0.02 },
  { publicId: "g2", name: "Sunrise Lands", slug: "sunrise", logoUrl: null, agentFeeRate: 0.01 },
];

describe("ListAsGroupPicker", () => {
  it("renders Individual + group options when eligibleGroups is non-empty", () => {
    render(<ListAsGroupPicker eligibleGroups={groups} value={null} onChange={() => {}} />);
    expect(screen.getByLabelText(/Individual/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/Sunset Realty/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/Sunrise Lands/i)).toBeInTheDocument();
  });

  it("Individual is selected by default (value=null)", () => {
    render(<ListAsGroupPicker eligibleGroups={groups} value={null} onChange={() => {}} />);
    const individual = screen.getByLabelText(/Individual/i) as HTMLInputElement;
    expect(individual.checked).toBe(true);
  });

  it("calls onChange with publicId when a group radio is clicked", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<ListAsGroupPicker eligibleGroups={groups} value={null} onChange={onChange} />);
    await user.click(screen.getByLabelText(/Sunset Realty/i));
    expect(onChange).toHaveBeenCalledWith("g1");
  });

  it("calls onChange with null when Individual is clicked from a group selection", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<ListAsGroupPicker eligibleGroups={groups} value="g1" onChange={onChange} />);
    await user.click(screen.getByLabelText(/Individual/i));
    expect(onChange).toHaveBeenCalledWith(null);
  });

  it("renders nothing when eligibleGroups is empty", () => {
    const { container } = render(
      <ListAsGroupPicker eligibleGroups={[]} value={null} onChange={() => {}} />,
    );
    expect(container).toBeEmptyDOMElement();
  });
});
