// frontend/src/lib/ws/client.test.ts
//
// Unit tests for lib/ws/client.ts. Mocks @stomp/stompjs so tests run without
// a real WebSocket — the captured callbacks on the mock Client instance let
// us simulate onConnect, onWebSocketClose, onStompError etc. manually.

import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from "vitest";

// The mocked Client class. Captured callbacks and flags live on the instance
// so tests can trigger lifecycle events on demand.
type MockStompClient = {
  activate: Mock;
  deactivate: Mock;
  subscribe: Mock;
  connected: boolean;
  active: boolean;
  connectHeaders: Record<string, string>;
  beforeConnect?: () => void | Promise<void>;
  onConnect?: () => void;
  onWebSocketClose?: () => void;
  onStompError?: (frame: { headers: Record<string, string> }) => void;
};

let mockClientInstance: MockStompClient | null = null;

vi.mock("@stomp/stompjs", () => {
  // Use a regular function (not arrow) so `new Client(...)` works — vitest's
  // mocked arrow functions can't be used as constructors.
  function MockClient(this: unknown, config: Partial<MockStompClient>) {
    const instance: MockStompClient = {
      activate: vi.fn(() => {
        instance.active = true;
      }),
      deactivate: vi.fn(() => {
        instance.active = false;
        instance.connected = false;
      }),
      subscribe: vi.fn(() => ({ unsubscribe: vi.fn() })),
      connected: false,
      active: false,
      connectHeaders: {},
      beforeConnect: config.beforeConnect,
      onConnect: config.onConnect,
      onWebSocketClose: config.onWebSocketClose,
      onStompError: config.onStompError,
    };
    mockClientInstance = instance;
    return instance;
  }
  return {
    Client: MockClient,
  };
});

vi.mock("sockjs-client", () => ({
  default: vi.fn().mockImplementation(() => ({})),
}));

vi.mock("@/lib/auth/refresh", () => ({
  ensureFreshAccessToken: vi.fn(async () => "mock-access-token"),
  RefreshFailedError: class extends Error {
    readonly status: number;
    constructor(status: number) {
      super(`Refresh failed ${status}`);
      this.status = status;
    }
  },
}));

// Import UNDER TEST after the mocks are declared.
import {
  __resetWsClientForTests,
  getConnectionState,
  subscribe,
  subscribeToConnectionState,
} from "./client";
import { ensureFreshAccessToken } from "@/lib/auth/refresh";

describe("lib/ws/client", () => {
  beforeEach(() => {
    __resetWsClientForTests();
    mockClientInstance = null;
    vi.clearAllMocks();
  });

  afterEach(() => {
    __resetWsClientForTests();
  });

  it("first subscribe activates the client", () => {
    subscribe<unknown>("/topic/foo", () => {});

    expect(mockClientInstance).not.toBeNull();
    expect(mockClientInstance!.activate).toHaveBeenCalledTimes(1);
  });

  it("second subscribe reuses the existing client (single activate)", () => {
    subscribe<unknown>("/topic/foo", () => {});
    subscribe<unknown>("/topic/bar", () => {});

    expect(mockClientInstance!.activate).toHaveBeenCalledTimes(1);
  });

  it("last unsubscribe schedules disconnect with 5s grace period", () => {
    vi.useFakeTimers();
    try {
      const unsub = subscribe<unknown>("/topic/foo", () => {});

      unsub();
      // Not yet — grace period.
      expect(mockClientInstance!.deactivate).not.toHaveBeenCalled();

      vi.advanceTimersByTime(5_000);
      expect(mockClientInstance!.deactivate).toHaveBeenCalledTimes(1);
    } finally {
      vi.useRealTimers();
    }
  });

  it("resubscribe within grace window cancels the scheduled teardown", () => {
    vi.useFakeTimers();
    try {
      const unsub1 = subscribe<unknown>("/topic/foo", () => {});

      unsub1();
      vi.advanceTimersByTime(4_000);
      // Second subscribe within grace — teardown should cancel.
      subscribe<unknown>("/topic/bar", () => {});
      vi.advanceTimersByTime(5_000);

      expect(mockClientInstance!.deactivate).not.toHaveBeenCalled();
    } finally {
      vi.useRealTimers();
    }
  });

  it("beforeConnect calls ensureFreshAccessToken and sets Authorization header", async () => {
    subscribe<unknown>("/topic/foo", () => {});

    // Invoke the captured beforeConnect callback.
    await mockClientInstance!.beforeConnect!();

    expect(ensureFreshAccessToken).toHaveBeenCalledTimes(1);
    expect(mockClientInstance!.connectHeaders.Authorization).toBe("Bearer mock-access-token");
  });

  it("onConnect transitions ConnectionState to connected", () => {
    const states: string[] = [];
    subscribeToConnectionState((s) => states.push(s.status));

    subscribe<unknown>("/topic/foo", () => {});
    // Simulate the captured onConnect firing.
    mockClientInstance!.onConnect!();

    expect(getConnectionState().status).toBe("connected");
    expect(states).toContain("connected");
  });

  it("onWebSocketClose while active transitions ConnectionState to reconnecting", () => {
    subscribe<unknown>("/topic/foo", () => {});
    mockClientInstance!.active = true;

    mockClientInstance!.onWebSocketClose!();

    expect(getConnectionState().status).toBe("reconnecting");
  });

  it("onStompError transitions ConnectionState to error with the frame detail", () => {
    subscribe<unknown>("/topic/foo", () => {});

    mockClientInstance!.onStompError!({ headers: { message: "Auth failed" } });

    const state = getConnectionState();
    expect(state.status).toBe("error");
    if (state.status === "error") {
      expect(state.detail).toBe("Auth failed");
    }
  });

  it("subscribe before connected defers the stompjs subscribe until onConnect", () => {
    const handler = vi.fn();
    subscribe<{ msg: string }>("/topic/foo", handler);

    // Client is not connected yet.
    mockClientInstance!.connected = false;
    expect(mockClientInstance!.subscribe).not.toHaveBeenCalled();

    // Now connect — the deferred attach should fire.
    mockClientInstance!.connected = true;
    mockClientInstance!.onConnect!();

    expect(mockClientInstance!.subscribe).toHaveBeenCalledTimes(1);
    expect(mockClientInstance!.subscribe.mock.calls[0][0]).toBe("/topic/foo");
  });
});
