import { describe, expect, it } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import {
  ActivateStatusStepper,
  statusToStepperIndex,
} from "./ActivateStatusStepper";

describe("statusToStepperIndex", () => {
  it("maps each pre-active status to the correct step", () => {
    expect(statusToStepperIndex("DRAFT")).toBe(0);
    expect(statusToStepperIndex("DRAFT_PAID")).toBe(1);
    expect(statusToStepperIndex("VERIFICATION_FAILED")).toBe(1);
    expect(statusToStepperIndex("VERIFICATION_PENDING")).toBe(2);
    expect(statusToStepperIndex("ACTIVE")).toBe(3);
  });
});

describe("ActivateStatusStepper", () => {
  it("marks the Paid step as current for DRAFT_PAID", () => {
    renderWithProviders(<ActivateStatusStepper status="DRAFT_PAID" />);
    const current = screen
      .getAllByRole("listitem")
      .find((li) => li.getAttribute("aria-current") === "step");
    expect(current?.textContent).toMatch(/Paid/);
  });

  it("pins back to Paid on VERIFICATION_FAILED (regression visual)", () => {
    renderWithProviders(<ActivateStatusStepper status="VERIFICATION_FAILED" />);
    const current = screen
      .getAllByRole("listitem")
      .find((li) => li.getAttribute("aria-current") === "step");
    expect(current?.textContent).toMatch(/Paid/);
  });
});
