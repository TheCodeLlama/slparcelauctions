import { describe, expect, it } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { ListingWizardLayout } from "./ListingWizardLayout";

describe("ListingWizardLayout", () => {
  it("renders stepper, title, body, and footer", () => {
    renderWithProviders(
      <ListingWizardLayout
        steps={["Configure", "Review"]}
        currentIndex={0}
        title="Create listing"
        description="Step one of two"
        footer={<button type="button">Continue</button>}
      >
        <div>Body content</div>
      </ListingWizardLayout>,
    );
    expect(screen.getByText("Configure")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: /Create listing/ })).toBeInTheDocument();
    expect(screen.getByText("Step one of two")).toBeInTheDocument();
    expect(screen.getByText("Body content")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Continue/i })).toBeInTheDocument();
  });

  it("omits the description when not provided", () => {
    renderWithProviders(
      <ListingWizardLayout
        steps={["A"]}
        currentIndex={0}
        title="T"
        footer={<span />}
      >
        <span>x</span>
      </ListingWizardLayout>,
    );
    // Header should exist but no paragraph beneath it.
    const heading = screen.getByRole("heading", { name: "T" });
    expect(heading.parentElement?.querySelector("p")).toBeNull();
  });
});
