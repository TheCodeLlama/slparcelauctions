import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { EscrowStepper } from "./EscrowStepper";
import { fakeEscrow } from "@/test/fixtures/escrow";

describe("EscrowStepper", () => {
  it("renders the four happy-path node labels", () => {
    renderWithProviders(
      <EscrowStepper escrow={fakeEscrow({ state: "ESCROW_PENDING" })} />,
    );
    expect(screen.getByText(/^payment$/i)).toBeInTheDocument();
    expect(screen.getByText(/^set sell to$/i)).toBeInTheDocument();
    expect(screen.getByText(/^buy parcel$/i)).toBeInTheDocument();
    expect(screen.getByText(/^complete$/i)).toBeInTheDocument();
  });

  it("renders Payment as active for ESCROW_PENDING", () => {
    renderWithProviders(
      <EscrowStepper escrow={fakeEscrow({ state: "ESCROW_PENDING" })} />,
    );
    const paymentNode = screen.getByText(/^payment$/i).closest("li");
    expect(paymentNode).toHaveAttribute("aria-current", "step");
  });

  it("Payment complete + Set Sell To active when funded, sell-to not confirmed", () => {
    renderWithProviders(
      <EscrowStepper
        escrow={fakeEscrow({
          state: "TRANSFER_PENDING",
          fundedAt: "2026-05-01T14:30:00Z",
          sellToConfirmedAt: null,
        })}
      />,
    );
    const payment = screen.getByText(/^payment$/i).closest("li");
    expect(payment).toHaveAttribute("data-state", "complete");
    const setSellTo = screen.getByText(/^set sell to$/i).closest("li");
    expect(setSellTo).toHaveAttribute("aria-current", "step");
    const buyParcel = screen.getByText(/^buy parcel$/i).closest("li");
    expect(buyParcel).toHaveAttribute("data-state", "upcoming");
    const complete = screen.getByText(/^complete$/i).closest("li");
    expect(complete).toHaveAttribute("data-state", "upcoming");
    // Payment timestamp visible under the completed node.
    expect(screen.getByText(/\d{1,2}:\d{2}/)).toBeInTheDocument();
  });

  it("Set Sell To complete + Buy Parcel active when sellToConfirmedAt stamped", () => {
    renderWithProviders(
      <EscrowStepper
        escrow={fakeEscrow({
          state: "TRANSFER_PENDING",
          fundedAt: "2026-05-01T14:30:00Z",
          sellToConfirmedAt: "2026-05-02T09:00:00Z",
          transferConfirmedAt: null,
        })}
      />,
    );
    const setSellTo = screen.getByText(/^set sell to$/i).closest("li");
    expect(setSellTo).toHaveAttribute("data-state", "complete");
    const buyParcel = screen.getByText(/^buy parcel$/i).closest("li");
    expect(buyParcel).toHaveAttribute("aria-current", "step");
    const complete = screen.getByText(/^complete$/i).closest("li");
    expect(complete).toHaveAttribute("data-state", "upcoming");
  });

  it("first three complete + Complete active when transferConfirmedAt stamped", () => {
    renderWithProviders(
      <EscrowStepper
        escrow={fakeEscrow({
          state: "TRANSFER_PENDING",
          fundedAt: "2026-05-01T14:30:00Z",
          sellToConfirmedAt: "2026-05-02T09:00:00Z",
          transferConfirmedAt: "2026-05-02T10:00:00Z",
        })}
      />,
    );
    const completeNode = screen.getByText(/^complete$/i).closest("li");
    expect(completeNode).toHaveAttribute("aria-current", "step");
    const buyParcel = screen.getByText(/^buy parcel$/i).closest("li");
    expect(buyParcel).toHaveAttribute("data-state", "complete");
  });

  it("renders all four complete for COMPLETED state", () => {
    renderWithProviders(
      <EscrowStepper
        escrow={fakeEscrow({
          state: "COMPLETED",
          fundedAt: "2026-05-01T14:30:00Z",
          sellToConfirmedAt: "2026-05-02T09:00:00Z",
          transferConfirmedAt: "2026-05-02T10:00:00Z",
          completedAt: "2026-05-02T10:00:05Z",
        })}
      />,
    );
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

  it("FROZEN interrupt keeps preceding completed nodes including Set Sell To", () => {
    renderWithProviders(
      <EscrowStepper
        escrow={fakeEscrow({
          state: "FROZEN",
          fundedAt: "2026-05-01T14:30:00Z",
          sellToConfirmedAt: "2026-05-02T09:00:00Z",
          frozenAt: "2026-05-02T09:30:00Z",
        })}
      />,
    );
    expect(screen.getByText(/frozen/i)).toBeInTheDocument();
    // Payment and Set Sell To both happened before the freeze.
    expect(screen.getByText(/^payment$/i)).toBeInTheDocument();
    expect(screen.getByText(/^set sell to$/i)).toBeInTheDocument();
    // Buy Parcel never reached — it is not a preceding-complete node.
    expect(screen.queryByText(/^buy parcel$/i)).not.toBeInTheDocument();
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
