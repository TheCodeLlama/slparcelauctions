// frontend/src/lib/ws/hooks.test.tsx

import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from "vitest";
import { render, renderHook, act } from "@testing-library/react";

// Track calls to the module primitives without pulling in stompjs.
// vi.hoisted keeps these accessible from the hoisted vi.mock factory below.
const { subscribeMock, subscribeToConnectionStateMock, getConnectionStateMock } =
  vi.hoisted(() => ({
    subscribeMock: vi.fn(),
    subscribeToConnectionStateMock: vi.fn(),
    getConnectionStateMock: vi.fn(() => ({ status: "disconnected" as const })),
  }));

vi.mock("./client", () => ({
  // Forward to the spy and return whatever its mockImplementation returns so
  // tests can override the unsubscribe closure per-case.
  subscribe: (...args: unknown[]) => subscribeMock(...args),
  subscribeToConnectionState: (
    listener: (state: { status: string }) => void
  ) => subscribeToConnectionStateMock(listener),
  getConnectionState: getConnectionStateMock,
}));

import { useConnectionState, useStompSubscription } from "./hooks";

describe("lib/ws/hooks", () => {
  beforeEach(() => {
    subscribeMock.mockReset();
    (subscribeMock as unknown as Mock).mockImplementation(() => () => {});
    subscribeToConnectionStateMock.mockReset();
    getConnectionStateMock.mockReset();
    getConnectionStateMock.mockReturnValue({ status: "disconnected" });
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it("useConnectionState renders the current state and reacts to updates", () => {
    let capturedListener: ((s: { status: string }) => void) | null = null;
    subscribeToConnectionStateMock.mockImplementation((listener) => {
      capturedListener = listener;
      listener({ status: "disconnected" });
      return () => {};
    });

    const { result } = renderHook(() => useConnectionState());
    expect(result.current.status).toBe("disconnected");

    act(() => {
      capturedListener!({ status: "connected" });
    });

    expect(result.current.status).toBe("connected");
  });

  it("useStompSubscription subscribes on mount and unsubscribes on unmount", () => {
    const unsubscribeMock = vi.fn();
    subscribeMock.mockImplementation(() => unsubscribeMock);

    function Probe() {
      useStompSubscription<{ msg: string }>("/topic/foo", () => {});
      return null;
    }

    const { unmount } = render(<Probe />);
    expect(subscribeMock).toHaveBeenCalledTimes(1);
    expect(subscribeMock.mock.calls[0][0]).toBe("/topic/foo");

    unmount();
    expect(unsubscribeMock).toHaveBeenCalledTimes(1);
  });

  it("useStompSubscription does not re-subscribe when the callback identity changes", () => {
    const unsubscribeMock = vi.fn();
    subscribeMock.mockImplementation(() => unsubscribeMock);

    let renderCount = 0;
    function Probe() {
      renderCount++;
      // Inline arrow creates a new function identity on every render.
      useStompSubscription<{ msg: string }>("/topic/foo", () => {
        // noop
      });
      return null;
    }

    const { rerender } = render(<Probe />);
    rerender(<Probe />);
    rerender(<Probe />);

    expect(renderCount).toBe(3);
    expect(subscribeMock).toHaveBeenCalledTimes(1);
  });
});
