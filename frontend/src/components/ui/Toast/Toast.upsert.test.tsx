import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, act, within, fireEvent } from "@testing-library/react";
import { renderWithProviders } from "@/test/render";
import { useToast } from "./useToast";

function UpsertTrigger({
  id,
  title,
  onClick,
}: {
  id: string;
  title: string;
  onClick?: () => void;
}) {
  const toast = useToast();
  return (
    <button
      data-testid={`trigger-${title}`}
      onClick={() => {
        if (onClick) onClick();
        toast.upsert(id, "info", { title });
      }}
    >
      {title}
    </button>
  );
}

describe("Toast.upsert", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("upsert with new id pushes a fresh toast", () => {
    function Comp() {
      const toast = useToast();
      return (
        <button
          data-testid="trigger"
          onClick={() => toast.upsert("a", "info", { title: "first" })}
        >
          go
        </button>
      );
    }

    renderWithProviders(<Comp />);
    fireEvent.click(screen.getByTestId("trigger"));

    const stack = screen.getByTestId("toast-stack");
    expect(within(stack).getByText("first")).toBeInTheDocument();
  });

  it("upsert with existing id replaces payload in place (no duplicates)", () => {
    function Comp() {
      const toast = useToast();
      return (
        <>
          <button
            data-testid="first"
            onClick={() => toast.upsert("a", "info", { title: "first" })}
          >
            first
          </button>
          <button
            data-testid="second"
            onClick={() => toast.upsert("a", "info", { title: "second" })}
          >
            second
          </button>
        </>
      );
    }

    renderWithProviders(<Comp />);
    fireEvent.click(screen.getByTestId("first"));
    fireEvent.click(screen.getByTestId("second"));

    const stack = screen.getByTestId("toast-stack");
    const toasts = within(stack).getAllByRole("status");
    expect(toasts).toHaveLength(1);
    expect(toasts[0]).toHaveTextContent("second");
  });

  it("upsert resets auto-dismiss timer on existing id", async () => {
    function Comp() {
      const toast = useToast();
      return (
        <>
          <button
            data-testid="first"
            onClick={() => toast.upsert("a", "info", { title: "first" })}
          >
            first
          </button>
          <button
            data-testid="second"
            onClick={() => toast.upsert("a", "info", { title: "second" })}
          >
            second
          </button>
        </>
      );
    }

    renderWithProviders(<Comp />);

    // Push initial toast; timer starts at t=0.
    fireEvent.click(screen.getByTestId("first"));
    expect(screen.getByTestId("toast-stack")).toHaveTextContent("first");

    // Advance 2500ms — timer should NOT have fired yet (3000ms total).
    act(() => { vi.advanceTimersByTime(2500); });
    expect(screen.getByTestId("toast-stack")).toHaveTextContent("first");

    // Upsert at t=2500 resets the timer to another 3000ms from now.
    fireEvent.click(screen.getByTestId("second"));
    expect(screen.getByTestId("toast-stack")).toHaveTextContent("second");

    // Advance another 2500ms (t=5000 total, but only 2500ms since reset).
    act(() => { vi.advanceTimersByTime(2500); });
    // Still visible — the 3s would have expired at t=3000 without the reset.
    expect(screen.getByTestId("toast-stack")).toHaveTextContent("second");

    // Advance the remaining 500ms to cross the 3000ms mark since last upsert.
    act(() => { vi.advanceTimersByTime(600); });
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("upsert respects MAX_VISIBLE cap on fresh ids", () => {
    function Comp() {
      const toast = useToast();
      return (
        <>
          {["a", "b", "c", "d"].map((id) => (
            <button
              key={id}
              data-testid={`btn-${id}`}
              onClick={() => toast.upsert(id, "info", { title: id })}
            >
              {id}
            </button>
          ))}
        </>
      );
    }

    renderWithProviders(<Comp />);
    fireEvent.click(screen.getByTestId("btn-a"));
    fireEvent.click(screen.getByTestId("btn-b"));
    fireEvent.click(screen.getByTestId("btn-c"));
    fireEvent.click(screen.getByTestId("btn-d"));

    const stack = screen.getByTestId("toast-stack");
    const toasts = within(stack).getAllByRole("status");
    expect(toasts).toHaveLength(3);
    // Oldest ("a") dropped; b, c, d remain.
    expect(toasts[0]).toHaveTextContent("b");
    expect(toasts[1]).toHaveTextContent("c");
    expect(toasts[2]).toHaveTextContent("d");
  });
});
