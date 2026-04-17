import { describe, expect, it } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import type { PendingVerification } from "@/types/auction";
import { VerificationMethodSaleToBot } from "./VerificationMethodSaleToBot";

function pending(
  overrides: Partial<PendingVerification> = {},
): PendingVerification {
  return {
    method: "SALE_TO_BOT",
    code: null,
    codeExpiresAt: null,
    botTaskId: 9,
    instructions: null,
    ...overrides,
  };
}

describe("VerificationMethodSaleToBot", () => {
  it("renders the step-by-step SL Land instructions", () => {
    renderWithProviders(
      <VerificationMethodSaleToBot pending={pending()} />,
    );
    // The account name appears both in the heading and in the <strong>
    // list item — both are intentional. Just assert at least one is shown.
    expect(screen.getAllByText(/SLPAEscrow Resident/).length).toBeGreaterThan(0);
    expect(screen.getByText(/L\$999,999,999/)).toBeInTheDocument();
  });

  it("strips a leading 'Bot: ' prefix from the backend instructions", () => {
    renderWithProviders(
      <VerificationMethodSaleToBot
        pending={pending({ instructions: "Bot: PARCEL_LOCKED" })}
      />,
    );
    expect(screen.getByText("PARCEL_LOCKED")).toBeInTheDocument();
    expect(screen.queryByText("Bot: PARCEL_LOCKED")).toBeNull();
  });

  it("renders plain instructions unchanged when no prefix", () => {
    renderWithProviders(
      <VerificationMethodSaleToBot
        pending={pending({
          instructions: "Waiting for the bot to pick up your task.",
        })}
      />,
    );
    expect(
      screen.getByText("Waiting for the bot to pick up your task."),
    ).toBeInTheDocument();
  });
});
