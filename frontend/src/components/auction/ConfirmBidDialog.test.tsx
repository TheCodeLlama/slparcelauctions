import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import {
  ConfirmBidDialog,
  isConfirmDismissed,
} from "./ConfirmBidDialog";

describe("ConfirmBidDialog", () => {
  beforeEach(() => {
    if (typeof window !== "undefined") {
      window.sessionStorage.clear();
    }
  });

  it("does not render when isOpen is false", () => {
    renderWithProviders(
      <ConfirmBidDialog
        isOpen={false}
        title="Confirm"
        message="Are you sure?"
        onConfirm={vi.fn()}
        onClose={vi.fn()}
      />,
    );
    expect(screen.queryByTestId("confirm-bid-dialog")).not.toBeInTheDocument();
  });

  it("renders title and message when open", () => {
    renderWithProviders(
      <ConfirmBidDialog
        isOpen
        title="Confirm L$15,000"
        message="This will place a large bid."
        onConfirm={vi.fn()}
        onClose={vi.fn()}
      />,
    );
    expect(screen.getByTestId("confirm-bid-dialog")).toHaveTextContent(
      "Confirm L$15,000",
    );
    expect(screen.getByTestId("confirm-bid-dialog")).toHaveTextContent(
      "This will place a large bid.",
    );
  });

  it("fires onConfirm when confirm is clicked", async () => {
    const onConfirm = vi.fn();
    const onClose = vi.fn();
    renderWithProviders(
      <ConfirmBidDialog
        isOpen
        title="Confirm"
        message="Are you sure?"
        onConfirm={onConfirm}
        onClose={onClose}
      />,
    );
    await userEvent.click(screen.getByTestId("confirm-bid-dialog-confirm"));
    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(onClose).not.toHaveBeenCalled();
  });

  it("fires onClose when cancel is clicked", async () => {
    const onClose = vi.fn();
    renderWithProviders(
      <ConfirmBidDialog
        isOpen
        title="Confirm"
        message="Are you sure?"
        onConfirm={vi.fn()}
        onClose={onClose}
      />,
    );
    await userEvent.click(screen.getByTestId("confirm-bid-dialog-cancel"));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("shows the don't-ask-again checkbox only when a key is provided", () => {
    const { rerender } = renderWithProviders(
      <ConfirmBidDialog
        isOpen
        title="Confirm"
        message="Are you sure?"
        onConfirm={vi.fn()}
        onClose={vi.fn()}
      />,
    );
    expect(
      screen.queryByTestId("confirm-bid-dialog-dont-ask-again"),
    ).not.toBeInTheDocument();

    rerender(
      <ConfirmBidDialog
        isOpen
        title="Confirm"
        message="Are you sure?"
        onConfirm={vi.fn()}
        onClose={vi.fn()}
        dontAskAgainKey="slpa:bid:confirm:dismissed"
      />,
    );
    expect(
      screen.getByTestId("confirm-bid-dialog-dont-ask-again"),
    ).toBeInTheDocument();
  });

  it("writes to sessionStorage on confirm when the checkbox is checked", async () => {
    const onConfirm = vi.fn();
    renderWithProviders(
      <ConfirmBidDialog
        isOpen
        title="Confirm"
        message="Are you sure?"
        onConfirm={onConfirm}
        onClose={vi.fn()}
        dontAskAgainKey="slpa:test:dismissed"
      />,
    );
    await userEvent.click(
      screen.getByTestId("confirm-bid-dialog-dont-ask-again"),
    );
    await userEvent.click(screen.getByTestId("confirm-bid-dialog-confirm"));
    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(isConfirmDismissed("slpa:test:dismissed")).toBe(true);
  });

  it("does NOT write to sessionStorage when the checkbox is unchecked", async () => {
    const onConfirm = vi.fn();
    renderWithProviders(
      <ConfirmBidDialog
        isOpen
        title="Confirm"
        message="Are you sure?"
        onConfirm={onConfirm}
        onClose={vi.fn()}
        dontAskAgainKey="slpa:test:dismissed"
      />,
    );
    await userEvent.click(screen.getByTestId("confirm-bid-dialog-confirm"));
    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(isConfirmDismissed("slpa:test:dismissed")).toBe(false);
  });

  it("isConfirmDismissed returns false for undefined/missing keys", () => {
    expect(isConfirmDismissed(undefined)).toBe(false);
    expect(isConfirmDismissed("slpa:never-set")).toBe(false);
  });
});
