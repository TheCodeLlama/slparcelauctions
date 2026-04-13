// frontend/src/components/ui/PasswordStrengthIndicator.test.tsx
import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { PasswordStrengthIndicator } from "./PasswordStrengthIndicator";

describe("PasswordStrengthIndicator", () => {
  it("renders nothing when password is empty", () => {
    renderWithProviders(<PasswordStrengthIndicator password="" />);
    expect(screen.queryByRole("progressbar")).toBeNull();
  });

  it("shows 'Weak' label and 1 filled bar for a short password", () => {
    renderWithProviders(<PasswordStrengthIndicator password="abc" />);
    expect(screen.getByText("Weak")).toBeInTheDocument();
    const progressbar = screen.getByRole("progressbar");
    expect(progressbar).toHaveAttribute("aria-valuenow", "1");
  });

  it("shows 'Good' label and 3 filled bars for a password meeting the backend regex", () => {
    renderWithProviders(<PasswordStrengthIndicator password="hunter22ab" />);
    expect(screen.getByText("Good")).toBeInTheDocument();
    const progressbar = screen.getByRole("progressbar");
    expect(progressbar).toHaveAttribute("aria-valuenow", "3");
  });

  it("shows 'Strong' label and 4 filled bars for a 14+ character password meeting the regex", () => {
    renderWithProviders(<PasswordStrengthIndicator password="hunter22abcdef" />);
    expect(screen.getByText("Strong")).toBeInTheDocument();
    const progressbar = screen.getByRole("progressbar");
    expect(progressbar).toHaveAttribute("aria-valuenow", "4");
  });
});
