import { describe, expect, it, vi } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { ConfirmListParcelModal } from "./ConfirmListParcelModal";

describe("ConfirmListParcelModal", () => {
  it("renders fee, wallet, and balance-after when open", () => {
    renderWithProviders(
      <ConfirmListParcelModal
        open
        onClose={vi.fn()}
        onConfirm={vi.fn()}
        listingFee={100}
        walletBalance={5000}
      />,
    );
    expect(screen.getByText(/List this parcel\?/i)).toBeInTheDocument();
    expect(screen.getByText("L$100")).toBeInTheDocument();
    expect(screen.getByText("L$5,000")).toBeInTheDocument();
    expect(screen.getByText("L$4,900")).toBeInTheDocument();
  });

  it("Cancel calls onClose, Confirm calls onConfirm", async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    const onConfirm = vi.fn();
    renderWithProviders(
      <ConfirmListParcelModal
        open
        onClose={onClose}
        onConfirm={onConfirm}
        listingFee={100}
        walletBalance={5000}
      />,
    );
    await user.click(screen.getByTestId("confirm-list-parcel-cancel"));
    expect(onClose).toHaveBeenCalled();
    await user.click(screen.getByTestId("confirm-list-parcel-confirm"));
    expect(onConfirm).toHaveBeenCalled();
  });

  it("uses group-wallet language when isGroupListing is true", () => {
    renderWithProviders(
      <ConfirmListParcelModal
        open
        onClose={vi.fn()}
        onConfirm={vi.fn()}
        listingFee={100}
        walletBalance={5000}
        isGroupListing
      />,
    );
    expect(screen.getByText(/Group wallet balance/i)).toBeInTheDocument();
    expect(
      screen.getByText(/group's SLParcels wallet/i),
    ).toBeInTheDocument();
    // The personal-wallet variant copy must NOT appear in the group case.
    expect(
      screen.queryByText(/from your SLParcels wallet/i),
    ).not.toBeInTheDocument();
  });

  it("disables both buttons while isListing", () => {
    renderWithProviders(
      <ConfirmListParcelModal
        open
        onClose={vi.fn()}
        onConfirm={vi.fn()}
        listingFee={100}
        walletBalance={5000}
        isListing
      />,
    );
    expect(screen.getByTestId("confirm-list-parcel-cancel")).toBeDisabled();
    expect(screen.getByTestId("confirm-list-parcel-confirm")).toBeDisabled();
  });
});
