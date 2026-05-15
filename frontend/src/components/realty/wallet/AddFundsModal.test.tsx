import { describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, waitFor, userEvent } from "@/test/render";
import { server } from "@/test/msw/server";
import { AddFundsModal } from "./AddFundsModal";

const GROUP = {
  publicId: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
  name: "Mainland Realty",
};

function renderModal(
  props: Partial<Parameters<typeof AddFundsModal>[0]> = {},
) {
  return renderWithProviders(
    <AddFundsModal
      open
      onClose={() => {}}
      group={GROUP}
      personalAvailable={10_000}
      {...props}
    />,
  );
}

describe("AddFundsModal", () => {
  it("renders the group name and the depositor's available balance", () => {
    renderModal();
    expect(
      screen.getByRole("dialog", { name: /Add funds to Mainland Realty/i }),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/Available: L\$10,000 \(your wallet\)/i),
    ).toBeInTheDocument();
  });

  it("shows inline error when amount exceeds personalAvailable", async () => {
    renderModal({ personalAvailable: 500 });
    await userEvent.type(
      screen.getByTestId("add-funds-amount-input"),
      "1000",
    );
    expect(
      screen.getByText(/Not enough funds in your wallet/i),
    ).toBeInTheDocument();
  });

  it("shows inline error when amount exceeds maxDepositL", async () => {
    renderModal({ personalAvailable: 10_000_000, maxDepositL: 500_000 });
    await userEvent.type(
      screen.getByTestId("add-funds-amount-input"),
      "600000",
    );
    expect(
      screen.getByText(/Maximum per deposit is L\$500,000/i),
    ).toBeInTheDocument();
  });

  it("submits successfully, fires the success toast, and calls onClose", async () => {
    server.use(
      http.post(
        `*/api/v1/realty/groups/${GROUP.publicId}/wallet/deposit`,
        () =>
          HttpResponse.json(
            {
              groupLedgerEntryId: 1,
              personalLedgerEntryId: 2,
              newGroupAvailable: 1500,
              newPersonalAvailable: 8500,
            },
            { status: 200 },
          ),
      ),
    );
    const onClose = vi.fn();
    renderModal({ onClose });

    await userEvent.type(
      screen.getByTestId("add-funds-amount-input"),
      "1500",
    );
    await userEvent.click(screen.getByTestId("add-funds-submit"));

    await waitFor(() =>
      expect(
        screen.getByText(/Deposited L\$1,500 to Mainland Realty/i),
      ).toBeInTheDocument(),
    );
    expect(onClose).toHaveBeenCalled();
  });

  it("maps a 400 INSUFFICIENT_BALANCE to an inline error and keeps the modal open", async () => {
    server.use(
      http.post(
        `*/api/v1/realty/groups/${GROUP.publicId}/wallet/deposit`,
        () =>
          HttpResponse.json(
            {
              status: 400,
              code: "INSUFFICIENT_BALANCE",
              title: "Insufficient balance",
            },
            { status: 400 },
          ),
      ),
    );
    const onClose = vi.fn();
    renderModal({ onClose });

    await userEvent.type(
      screen.getByTestId("add-funds-amount-input"),
      "1500",
    );
    await userEvent.click(screen.getByTestId("add-funds-submit"));

    await waitFor(() =>
      expect(
        screen.getByText(/Not enough funds in your wallet/i),
      ).toBeInTheDocument(),
    );
    expect(onClose).not.toHaveBeenCalled();
    // Modal remains open (the dialog is still in the DOM).
    expect(
      screen.getByRole("dialog", { name: /Add funds to Mainland Realty/i }),
    ).toBeInTheDocument();
  });

  it("maps a 409 USER_FROZEN to a toast and closes the modal", async () => {
    server.use(
      http.post(
        `*/api/v1/realty/groups/${GROUP.publicId}/wallet/deposit`,
        () =>
          HttpResponse.json(
            {
              status: 409,
              code: "USER_FROZEN",
              title: "User frozen",
            },
            { status: 409 },
          ),
      ),
    );
    const onClose = vi.fn();
    renderModal({ onClose });

    await userEvent.type(
      screen.getByTestId("add-funds-amount-input"),
      "1500",
    );
    await userEvent.click(screen.getByTestId("add-funds-submit"));

    await waitFor(() =>
      expect(
        screen.getByText(/Your wallet is currently frozen\./i),
      ).toBeInTheDocument(),
    );
    expect(onClose).toHaveBeenCalled();
  });
});
