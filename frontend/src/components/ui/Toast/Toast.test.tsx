import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, act, within, fireEvent } from "@testing-library/react";
import { renderWithProviders } from "@/test/render";
import { useToast } from "./useToast";

// Small helper that renders a button wired to the hook under test.
function ToastTrigger({
  kind,
  message,
}: {
  kind: "success" | "error";
  message: string;
}) {
  const toast = useToast();
  return (
    <button onClick={() => toast[kind](message)} data-testid="trigger">
      fire
    </button>
  );
}

/**
 * Helper: click a button and flush the deferred setToasts timer so the toast
 * appears in the DOM. The ToastProvider defers its state update via
 * setTimeout(0) to avoid interfering with React Query batching, so fake-timer
 * tests must advance by at least 1 ms after each push.
 */
function clickAndFlush(element: HTMLElement) {
  fireEvent.click(element);
  act(() => {
    vi.advanceTimersByTime(1);
  });
}

describe("Toast", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("useToast() throws outside ToastProvider", () => {
    // Suppress React error boundary console noise
    const spy = vi.spyOn(console, "error").mockImplementation(() => {});
    expect(() => render(<ToastTrigger kind="success" message="hi" />)).toThrow(
      "useToast must be used inside a ToastProvider",
    );
    spy.mockRestore();
  });

  it('toast.success(msg) displays a toast with role="status"', () => {
    renderWithProviders(
      <ToastTrigger kind="success" message="Profile saved" />,
    );

    clickAndFlush(screen.getByTestId("trigger"));

    const toast = screen.getByRole("status");
    expect(toast).toBeInTheDocument();
    expect(toast).toHaveTextContent("Profile saved");
  });

  it('toast.error(msg) displays a toast with role="alert"', () => {
    renderWithProviders(
      <ToastTrigger kind="error" message="Upload failed" />,
    );

    clickAndFlush(screen.getByTestId("trigger"));

    const alert = screen.getByRole("alert");
    expect(alert).toBeInTheDocument();
    expect(alert).toHaveTextContent("Upload failed");
  });

  it("auto-dismisses after 3 seconds", () => {
    renderWithProviders(
      <ToastTrigger kind="success" message="Saved" />,
    );

    clickAndFlush(screen.getByTestId("trigger"));
    expect(screen.getByRole("status")).toBeInTheDocument();

    // Advance just under 3 s -- toast should still be visible.
    // The auto-dismiss timer started at push time (the same setTimeout(0) tick),
    // so we subtract the 1 ms already advanced by clickAndFlush.
    act(() => {
      vi.advanceTimersByTime(2998);
    });
    expect(screen.getByRole("status")).toBeInTheDocument();

    // Advance past the 3 s mark
    act(() => {
      vi.advanceTimersByTime(2);
    });
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("max 3 visible -- 4th pushes out the oldest", () => {
    function MultiFire() {
      const toast = useToast();
      return (
        <>
          <button onClick={() => toast.success("A")} data-testid="a">A</button>
          <button onClick={() => toast.success("B")} data-testid="b">B</button>
          <button onClick={() => toast.success("C")} data-testid="c">C</button>
          <button onClick={() => toast.success("D")} data-testid="d">D</button>
        </>
      );
    }

    renderWithProviders(<MultiFire />);

    clickAndFlush(screen.getByTestId("a"));
    clickAndFlush(screen.getByTestId("b"));
    clickAndFlush(screen.getByTestId("c"));
    clickAndFlush(screen.getByTestId("d"));

    const stack = screen.getByTestId("toast-stack");
    const statuses = within(stack).getAllByRole("status");

    expect(statuses).toHaveLength(3);
    // Oldest ("A") should have been evicted; B, C, D remain.
    expect(statuses[0]).toHaveTextContent("B");
    expect(statuses[1]).toHaveTextContent("C");
    expect(statuses[2]).toHaveTextContent("D");
  });

  it("multiple toasts stack in order", () => {
    function StackTest() {
      const toast = useToast();
      return (
        <>
          <button onClick={() => toast.success("First")} data-testid="t1">1</button>
          <button onClick={() => toast.error("Second")} data-testid="t2">2</button>
          <button onClick={() => toast.success("Third")} data-testid="t3">3</button>
        </>
      );
    }

    renderWithProviders(<StackTest />);

    clickAndFlush(screen.getByTestId("t1"));
    clickAndFlush(screen.getByTestId("t2"));
    clickAndFlush(screen.getByTestId("t3"));

    const stack = screen.getByTestId("toast-stack");
    const toasts = Array.from(stack.querySelectorAll("[role]"));

    expect(toasts).toHaveLength(3);
    expect(toasts[0]).toHaveTextContent("First");
    expect(toasts[0]).toHaveAttribute("role", "status");
    expect(toasts[1]).toHaveTextContent("Second");
    expect(toasts[1]).toHaveAttribute("role", "alert");
    expect(toasts[2]).toHaveTextContent("Third");
    expect(toasts[2]).toHaveAttribute("role", "status");
  });
});
