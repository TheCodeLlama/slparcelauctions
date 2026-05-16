import { describe, expect, it, vi } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { DraftActionBar } from "./DraftActionBar";

describe("DraftActionBar", () => {
  it("renders fee + wallet, List Parcel + Delete Draft buttons", () => {
    renderWithProviders(
      <DraftActionBar
        listingFee={100}
        walletBalance={5000}
        onListParcel={vi.fn()}
        onDeleteDraft={vi.fn()}
      />,
    );
    expect(screen.getByText(/Listing fee/i)).toBeInTheDocument();
    expect(screen.getByTestId("draft-action-bar-list")).toBeInTheDocument();
    expect(screen.getByTestId("draft-action-bar-delete")).toBeInTheDocument();
  });

  it("disables List Parcel while isListing=true", () => {
    renderWithProviders(
      <DraftActionBar
        listingFee={100}
        walletBalance={5000}
        isListing
        onListParcel={vi.fn()}
        onDeleteDraft={vi.fn()}
      />,
    );
    expect(screen.getByTestId("draft-action-bar-list")).toBeDisabled();
  });

  it("labels the balance as 'Group wallet' when isGroupListing is true", () => {
    renderWithProviders(
      <DraftActionBar
        listingFee={100}
        walletBalance={5000}
        isGroupListing
        onListParcel={vi.fn()}
        onDeleteDraft={vi.fn()}
      />,
    );
    expect(screen.getByTestId("draft-action-bar-wallet")).toHaveTextContent(
      /Group wallet:/i,
    );
    expect(screen.getByTestId("draft-action-bar-wallet")).not.toHaveTextContent(
      /^Wallet:/,
    );
  });

  it("low-funds copy reflects the group wallet when isGroupListing", () => {
    renderWithProviders(
      <DraftActionBar
        listingFee={100}
        walletBalance={50}
        insufficientFunds
        isGroupListing
        onListParcel={vi.fn()}
        onDeleteDraft={vi.fn()}
      />,
    );
    expect(
      screen.getByTestId("draft-action-bar-low-funds"),
    ).toHaveTextContent(/Top up group wallet to list/i);
  });

  it("disables List Parcel and shows low-funds hint when insufficientFunds", () => {
    renderWithProviders(
      <DraftActionBar
        listingFee={100}
        walletBalance={50}
        insufficientFunds
        onListParcel={vi.fn()}
        onDeleteDraft={vi.fn()}
      />,
    );
    expect(screen.getByTestId("draft-action-bar-list")).toBeDisabled();
    expect(screen.getByTestId("draft-action-bar-low-funds")).toBeInTheDocument();
  });
});
