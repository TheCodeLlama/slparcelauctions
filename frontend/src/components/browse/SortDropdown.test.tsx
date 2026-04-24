import { describe, it, expect, vi } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { SortDropdown } from "./SortDropdown";

describe("SortDropdown", () => {
  it("renders all six sort options", () => {
    renderWithProviders(
      <SortDropdown value="newest" onChange={() => {}} nearestEnabled={false} />,
    );
    expect(screen.getByRole("option", { name: /newest/i })).toBeInTheDocument();
    expect(
      screen.getByRole("option", { name: /ending soonest/i }),
    ).toBeInTheDocument();
    expect(screen.getByRole("option", { name: /most bids/i })).toBeInTheDocument();
    expect(
      screen.getByRole("option", { name: /lowest price/i }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("option", { name: /largest area/i }),
    ).toBeInTheDocument();
    expect(screen.getByRole("option", { name: /nearest/i })).toBeInTheDocument();
  });

  it("disables nearest option when no near_region", () => {
    renderWithProviders(
      <SortDropdown value="newest" onChange={() => {}} nearestEnabled={false} />,
    );
    expect(screen.getByRole("option", { name: /nearest/i })).toBeDisabled();
  });

  it("enables nearest when nearestEnabled is true", () => {
    renderWithProviders(
      <SortDropdown value="newest" onChange={() => {}} nearestEnabled={true} />,
    );
    expect(
      screen.getByRole("option", { name: /nearest/i }),
    ).not.toBeDisabled();
  });

  it("fires onChange with the new sort value", async () => {
    const onChange = vi.fn();
    renderWithProviders(
      <SortDropdown
        value="newest"
        onChange={onChange}
        nearestEnabled={false}
      />,
    );
    await userEvent.selectOptions(
      screen.getByLabelText(/sort/i),
      "ending_soonest",
    );
    expect(onChange).toHaveBeenCalledWith("ending_soonest");
  });
});
