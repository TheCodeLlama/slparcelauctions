import { describe, expect, it, vi } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { SlGroupVerificationInstructionsCard } from "./SlGroupVerificationInstructionsCard";

describe("SlGroupVerificationInstructionsCard", () => {
  it("renders the verification code exactly as supplied", () => {
    renderWithProviders(
      <SlGroupVerificationInstructionsCard code="SLPA-1A2B3C4D5E6F" />,
    );
    const display = screen.getByTestId("verification-code-display");
    expect(display.textContent).toBe("SLPA-1A2B3C4D5E6F");
  });

  it("renders the founder-via-terminal verification option", () => {
    renderWithProviders(
      <SlGroupVerificationInstructionsCard code="SLPA-1A2B3C4D5E6F" />,
    );
    expect(screen.getByText(/Founder via terminal/)).toBeInTheDocument();
  });

  it("copies the verification code to the clipboard when the copy button is clicked", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.assign(navigator, {
      clipboard: { writeText },
    });

    renderWithProviders(
      <SlGroupVerificationInstructionsCard code="SLPA-1A2B3C4D5E6F" />,
    );
    await userEvent.click(screen.getByTestId("copy-code-button"));
    expect(writeText).toHaveBeenCalledWith("SLPA-1A2B3C4D5E6F");
  });
});
