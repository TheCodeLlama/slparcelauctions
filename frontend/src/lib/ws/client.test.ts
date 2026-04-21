// frontend/src/lib/ws/client.test.ts
//
// Unit tests for lib/ws/client.ts. Mocks @stomp/stompjs so tests run without
// a real WebSocket — the captured callbacks on the mock Client instance let
// us simulate onConnect, onWebSocketClose, onStompError etc. manually.

import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from "vitest";

// The mocked Client class. Captured callbacks and flags live on the instance
// so tests can trigger lifecycle events on demand.
//
// The extra helpers below (triggerConnect / triggerClose / deliver) make the
// re-attach-list tests much easier to read: each models one STOMP lifecycle
// event without hand-written bookkeeping per test.
type MockStompSubscription = {
  destination: string;
  handler: (frame: { body: string }) => void;
  unsubscribe: Mock;
  active: boolean;
};

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
  // Test-only helpers.
  _subs: MockStompSubscription[];
  triggerConnect: () => void;
  triggerClose: () => void;
  deliver: (destination: string, payload: unknown) => void;
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
        for (const s of instance._subs) s.active = false;
      }),
      subscribe: vi.fn((destination: string, handler: (frame: { body: string }) => void) => {
        const sub: MockStompSubscription = {
          destination,
          handler,
          active: true,
          unsubscribe: vi.fn(() => {
            sub.active = false;
          }),
        };
        instance._subs.push(sub);
        return sub;
      }),
      connected: false,
      active: false,
      connectHeaders: {},
      beforeConnect: config.beforeConnect,
      onConnect: config.onConnect,
      onWebSocketClose: config.onWebSocketClose,
      onStompError: config.onStompError,
      _subs: [],
      triggerConnect: () => {
        instance.connected = true;
        instance.active = true;
        instance.onConnect?.();
      },
      triggerClose: () => {
        instance.connected = false;
        // Mark all existing subscription handles as dead — matches real stompjs
        // behavior where the underlying STOMP session is torn down.
        for (const s of instance._subs) s.active = false;
        instance.onWebSocketClose?.();
      },
      deliver: (destination: string, payload: unknown) => {
        for (const s of instance._subs) {
          if (s.active && s.destination === destination) {
            s.handler({ body: JSON.stringify(payload) });
          }
        }
      },
    };
    mockClientInstance = instance;
    return instance;
  }
  return {
    Client: MockClient,
  };
});

// Flush pending microtasks/timers so any queued async work (beforeConnect's
// token refresh, etc.) has a chance to run before assertions.
async function flushAll(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
}

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
  __getRegistrySizeForTests,
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

  // ---------------------------------------------------------------------------
  // Re-attach list model (spec §6). These tests exercise the registry-driven
  // sweep on onConnect and the handle-nulling on onWebSocketClose.
  // ---------------------------------------------------------------------------

  it("bulkReattach_restoresAllSubscriptionsOnReconnect", async () => {
    const msgs1: string[] = [];
    const msgs2: string[] = [];
    const msgs3: string[] = [];
    const unsub1 = subscribe<string>("/topic/a", (p) => msgs1.push(p));
    const unsub2 = subscribe<string>("/topic/b", (p) => msgs2.push(p));
    const unsub3 = subscribe<string>("/topic/c", (p) => msgs3.push(p));

    // Complete the initial connect so all three attach.
    mockClientInstance!.triggerConnect();
    await flushAll();

    // Force close + reconnect — every entry must be re-attached.
    mockClientInstance!.triggerClose();
    await flushAll();
    mockClientInstance!.triggerConnect();
    await flushAll();

    mockClientInstance!.deliver("/topic/a", "msg-a");
    mockClientInstance!.deliver("/topic/b", "msg-b");
    mockClientInstance!.deliver("/topic/c", "msg-c");

    expect(msgs1).toEqual(["msg-a"]);
    expect(msgs2).toEqual(["msg-b"]);
    expect(msgs3).toEqual(["msg-c"]);

    unsub1();
    unsub2();
    unsub3();
  });

  it("racePin_rapidDisconnectReconnectCycle_subscriptionSurvives", async () => {
    const msgs: string[] = [];
    const unsub = subscribe<string>("/topic/x", (p) => msgs.push(p));

    mockClientInstance!.triggerConnect();
    // Rapid cycle: close/connect/close/connect. After the final connect the
    // entry should be attached exactly once and deliver the message.
    mockClientInstance!.triggerClose();
    mockClientInstance!.triggerConnect();
    mockClientInstance!.triggerClose();
    mockClientInstance!.triggerConnect();
    await flushAll();

    mockClientInstance!.deliver("/topic/x", "survived");
    expect(msgs).toEqual(["survived"]);

    unsub();
  });

  it("unsubscribeDuringReconnect_entryRemovedFromRegistry", async () => {
    const msgs: string[] = [];
    const unsub = subscribe<string>("/topic/x", (p) => msgs.push(p));

    mockClientInstance!.triggerConnect();
    mockClientInstance!.triggerClose();

    // Registry has the entry right now.
    expect(__getRegistrySizeForTests()).toBe(1);

    // Unsubscribe while in reconnecting state — entry must be removed so the
    // next onConnect sweep does not re-attach it.
    unsub();
    expect(__getRegistrySizeForTests()).toBe(0);

    mockClientInstance!.triggerConnect();
    await flushAll();

    mockClientInstance!.deliver("/topic/x", "should-not-arrive");
    expect(msgs).toEqual([]);
  });

  it("subscribeDuringSweep_lateAddedEntryAttachedInSamePass", async () => {
    const msgs1: string[] = [];
    const msgs2: string[] = [];
    let unsub2: () => void = () => {};

    // First handler: synchronously subscribe to a second topic when the
    // re-attach sweep hits /topic/a. The sweep iterates the LIVE Map, so the
    // late-added entry must be visited in the same pass and be live before
    // the test delivers to /topic/b below.
    const unsub1 = subscribe<string>("/topic/a", () => {
      msgs1.push("tick");
      if (msgs1.length === 1) {
        unsub2 = subscribe<string>("/topic/b", (p) => msgs2.push(p));
      }
    });

    mockClientInstance!.triggerConnect();
    await flushAll();

    // Disconnect + reconnect. During the reconnect the sweep re-attaches
    // /topic/a. We then deliver a message to /topic/a — the handler runs
    // synchronously inside `client.subscribe`'s callback, and that handler
    // calls subscribe("/topic/b") mid-iteration. The new entry must be
    // attached during the same sweep (when onConnect reaches it) rather than
    // left orphaned until the next reconnect.
    mockClientInstance!.triggerClose();
    mockClientInstance!.triggerConnect();
    await flushAll();

    // Deliver to /topic/a — the handler synchronously calls subscribe("/topic/b").
    // Because the Map is live, the new entry is seen later in the same sweep.
    mockClientInstance!.deliver("/topic/a", "msg-1");
    await flushAll();

    mockClientInstance!.deliver("/topic/b", "msg-b");
    expect(msgs2).toEqual(["msg-b"]);

    unsub1();
    unsub2();
  });

  it("memoryHygiene_1000CyclesLeavesRegistryEmpty", async () => {
    for (let i = 0; i < 1000; i++) {
      const u = subscribe<unknown>("/topic/x", () => {});
      if (i % 100 === 0) {
        mockClientInstance!.triggerClose();
        mockClientInstance!.triggerConnect();
      }
      u();
    }
    await flushAll();
    expect(__getRegistrySizeForTests()).toBe(0);
  });
});
