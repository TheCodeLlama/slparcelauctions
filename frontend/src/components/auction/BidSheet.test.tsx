import { describe, expect, it, vi } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { BidSheet } from "./BidSheet";

describe("BidSheet", () => {
  it("renders its children inside the scrollable body when open", () => {
    renderWithProviders(
      <BidSheet isOpen onClose={vi.fn()}>
        <div data-testid="bid-sheet-test-child">sheet contents</div>
      </BidSheet>,
    );
    expect(screen.getByTestId("bid-sheet")).toBeInTheDocument();
    const child = screen.getByTestId("bid-sheet-test-child");
    expect(child).toBeInTheDocument();
    // Ensure the child mounts inside the sheet body (not somewhere in
    // the surrounding portal chrome).
    expect(
      screen.getByTestId("bid-sheet-body").contains(child),
    ).toBe(true);
  });

  it("does not render its children when closed", () => {
    renderWithProviders(
      <BidSheet isOpen={false} onClose={vi.fn()}>
        <div data-testid="bid-sheet-test-child">sheet contents</div>
      </BidSheet>,
    );
    expect(screen.queryByTestId("bid-sheet")).toBeNull();
    expect(screen.queryByTestId("bid-sheet-test-child")).toBeNull();
  });

  it("invokes onClose when the close button is clicked", async () => {
    const onClose = vi.fn();
    renderWithProviders(
      <BidSheet isOpen onClose={onClose}>
        <div>contents</div>
      </BidSheet>,
    );
    await userEvent.click(screen.getByTestId("bid-sheet-close"));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("invokes onClose when Escape is pressed (Headless UI Dialog default)", async () => {
    const onClose = vi.fn();
    renderWithProviders(
      <BidSheet isOpen onClose={onClose}>
        <div>contents</div>
      </BidSheet>,
    );
    await userEvent.keyboard("{Escape}");
    expect(onClose).toHaveBeenCalled();
  });

  it("renders a non-functional decorative drag handle with no handlers", () => {
    renderWithProviders(
      <BidSheet isOpen onClose={vi.fn()}>
        <div>contents</div>
      </BidSheet>,
    );
    const handle = screen.getByTestId("bid-sheet-drag-handle");
    expect(handle).toBeInTheDocument();
    // Decorative — must not be interactive, must not carry pointer /
    // touch handlers. `aria-hidden="true"` keeps it out of the
    // accessibility tree.
    expect(handle).toHaveAttribute("aria-hidden", "true");
    expect(handle.tagName.toLowerCase()).not.toBe("button");
    expect(handle).not.toHaveAttribute("onclick");
    expect(handle).not.toHaveAttribute("ontouchstart");
    expect(handle).not.toHaveAttribute("onpointerdown");
  });

  it("does not install any gesture / swipe listeners on the sheet body", () => {
    renderWithProviders(
      <BidSheet isOpen onClose={vi.fn()}>
        <div>contents</div>
      </BidSheet>,
    );
    const body = screen.getByTestId("bid-sheet-body");
    // Spec §11 explicitly excludes swipe-to-dismiss. The body element
    // must not carry any drag / touch handler attributes.
    expect(body).not.toHaveAttribute("ontouchstart");
    expect(body).not.toHaveAttribute("ontouchmove");
    expect(body).not.toHaveAttribute("onpointerdown");
    expect(body).not.toHaveAttribute("onpointermove");
    expect(body).not.toHaveAttribute("ondragstart");
  });
});
