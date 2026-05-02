import { describe, it, expect, vi } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { Modal } from "./Modal";

describe("Modal", () => {
  it("renders nothing when closed", () => {
    renderWithProviders(
      <Modal open={false} title="Test Modal" onClose={() => {}}>
        <p>Content</p>
      </Modal>
    );
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("renders title and children when open", () => {
    renderWithProviders(
      <Modal open={true} title="Test Modal" onClose={() => {}}>
        <p>Modal content</p>
      </Modal>
    );
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(screen.getByText("Test Modal")).toBeInTheDocument();
    expect(screen.getByText("Modal content")).toBeInTheDocument();
  });

  it("calls onClose when Escape is pressed", async () => {
    const onClose = vi.fn();
    renderWithProviders(
      <Modal open={true} title="Test Modal" onClose={onClose}>
        <p>Content</p>
      </Modal>
    );
    await userEvent.keyboard("{Escape}");
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("renders footer slot when provided", () => {
    renderWithProviders(
      <Modal open={true} title="Test" onClose={() => {}} footer={<button>Confirm</button>}>
        <p>Content</p>
      </Modal>
    );
    expect(screen.getByRole("button", { name: "Confirm" })).toBeInTheDocument();
  });
});
