// frontend/src/components/marketing/HowItWorksStep.test.tsx

import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { ShieldCheck } from "@/components/ui/icons";
import { HowItWorksStep } from "./HowItWorksStep";

describe("HowItWorksStep", () => {
  it("renders icon, title, and body", () => {
    renderWithProviders(
      <HowItWorksStep
        icon={<ShieldCheck data-testid="step-icon" />}
        title="Verify"
        body="Identity and land ownership verification."
      />
    );
    expect(screen.getByTestId("step-icon")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Verify" })).toBeInTheDocument();
    expect(
      screen.getByText(/identity and land ownership verification/i)
    ).toBeInTheDocument();
  });
});
