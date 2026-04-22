import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { EscrowStepper } from "./EscrowStepper";
import { fakeEscrow } from "@/test/fixtures/escrow";

describe("EscrowStepper", () => {
  it("renders Payment as active for ESCROW_PENDING", () => {
    renderWithProviders(
      <EscrowStepper escrow={fakeEscrow({ state: "ESCROW_PENDING" })} />,
    );
    expect(screen.getByText(/payment/i)).toBeInTheDocument();
    expect(screen.getByText(/^transfer$/i)).toBeInTheDocument();
    expect(screen.getByText(/^complete$/i)).toBeInTheDocument();
    // Payment node should have aria-current="step"
    const paymentNode = screen.getByText(/payment/i).closest("li");
    expect(paymentNode).toHaveAttribute("aria-current", "step");
  });

  it("renders Payment complete + Transfer active when funded", () => {
    renderWithProviders(
      <EscrowStepper
        escrow={fakeEscrow({
          state: "TRANSFER_PENDING",
          fundedAt: "2026-05-01T14:30:00Z",
        })}
      />,
    );
    const transferNode = screen.getByText(/^transfer$/i).closest("li");
    expect(transferNode).toHaveAttribute("aria-current", "step");
    // Payment timestamp visible under the completed node. Locale-independent
    // match: any H:MM timestamp is acceptable (en-US renders "2:30 PM",
    // some locales produce "14:30", etc.).
    expect(screen.getByText(/\d{1,2}:\d{2}/)).toBeInTheDocument();
  });

  it("renders first two complete + Complete active when transferConfirmedAt stamped", () => {
    renderWithProviders(
      <EscrowStepper
        escrow={fakeEscrow({
          state: "TRANSFER_PENDING",
          fundedAt: "2026-05-01T14:30:00Z",
          transferConfirmedAt: "2026-05-02T10:00:00Z",
        })}
      />,
    );
    const completeNode = screen.getByText(/^complete$/i).closest("li");
    expect(completeNode).toHaveAttribute("aria-current", "step");
  });

  it("renders all three complete for COMPLETED state", () => {
    renderWithProviders(
      <EscrowStepper
        escrow={fakeEscrow({
          state: "COMPLETED",
          fundedAt: "2026-05-01T14:30:00Z",
          transferConfirmedAt: "2026-05-02T10:00:00Z",
          completedAt: "2026-05-02T10:00:05Z",
        })}
      />,
    );
    // No aria-current — everything is complete
    const items = screen.getAllByRole("listitem");
    items.forEach((li) => expect(li).not.toHaveAttribute("aria-current"));
  });

  it("renders interrupt node for DISPUTED state", () => {
    renderWithProviders(
      <EscrowStepper
        escrow={fakeEscrow({
          state: "DISPUTED",
          disputedAt: "2026-05-01T15:00:00Z",
        })}
      />,
    );
    expect(screen.getByText(/disputed/i)).toBeInTheDocument();
  });

  it("renders interrupt node for FROZEN state", () => {
    renderWithProviders(
      <EscrowStepper
        escrow={fakeEscrow({
          state: "FROZEN",
          fundedAt: "2026-05-01T14:30:00Z",
          frozenAt: "2026-05-02T09:00:00Z",
        })}
      />,
    );
    expect(screen.getByText(/frozen/i)).toBeInTheDocument();
    // Payment stays complete (it happened before the freeze)
    expect(screen.getByText(/payment/i)).toBeInTheDocument();
  });

  it("renders interrupt node for EXPIRED state (payment timeout)", () => {
    renderWithProviders(
      <EscrowStepper
        escrow={fakeEscrow({
          state: "EXPIRED",
          fundedAt: null,
          expiredAt: "2026-05-03T12:00:00Z",
        })}
      />,
    );
    expect(screen.getByText(/expired/i)).toBeInTheDocument();
  });
});
