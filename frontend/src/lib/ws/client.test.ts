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
  // One-shot hook: when set to a destination string, the next `client.subscribe`
  // call for that destination synchronously invokes the handler with `{body:"{}"}`
  // before returning the subscription. Used to model "replayed frame delivered
  // mid-attach", which lets a test exercise the onConnect sweep's mid-iteration
  // Map mutations. Cleared after firing so re-entrant subscribes are safe.
  _invokeHandlerSyncOnce: string | null;
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
        // One-shot synchronous handler invocation hook (see type def). Consumed
        // by the first matching subscribe call so re-entrant subscribe() paths
        // triggered by the handler don't re-enter this branch recursively.
        if (instance._invokeHandlerSyncOnce === destination) {
          instance._invokeHandlerSyncOnce = null;
          handler({ body: "{}" });
        }
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
      _invokeHandlerSyncOnce: null,
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
    // This test verifies that when an `onMessage` handler triggered during
    // the onConnect sweep synchronously subscribes to a new topic, the new
    // entry gets attached in the same sweep pass due to live-Map iteration.
    //
    // Scenario: the sweep iterates `entries.values()`. A handler fires
    // synchronously inside `client.subscribe("/topic/a", ...)` (modeling a
    // replayed frame delivered during attach). The handler calls
    // `subscribe("/topic/b-late")` — but first it flips `client.connected`
    // to false and back, which short-circuits subscribe()'s direct
    // `ensureAttached` path. That forces `/topic/b-late` to rely ENTIRELY on
    // the sweep's visit for attachment:
    //   - Live-Map iteration: sweep yields the newly-added entry-b-late and
    //     calls ensureAttached → `client.subscribe("/topic/b-late")` fires,
    //     b-late ends up in `_subs`.
    //   - Snapshot iteration (`Array.from(entries.values())`): sweep already
    //     finished its snapshot with only [entry-a], so b-late is orphaned,
    //     NOT in `_subs`. A second reconnect would be needed to rescue it.
    //
    // Swapping the production sweep to `Array.from(...)` makes the final
    // assertion fail — which is how we prove the test genuinely covers the
    // live-iteration invariant.

    let reentrantSubscribeFired = false;
    let unsub2: () => void = () => {};

    // Pre-existing subscription. When its handler fires synchronously during
    // the sweep (via the one-shot mock hook below), it momentarily flips
    // `connected` to false so subscribe()'s direct `ensureAttached` is a
    // no-op — forcing the sweep's visit to be the sole path that attaches
    // `/topic/b-late`.
    const unsub1 = subscribe<unknown>("/topic/a", () => {
      reentrantSubscribeFired = true;
      const wasConnected = mockClientInstance!.connected;
      mockClientInstance!.connected = false;
      try {
        unsub2 = subscribe<unknown>("/topic/b-late", () => {});
      } finally {
        mockClientInstance!.connected = wasConnected;
      }
    });

    // Arm the one-shot hook so the sweep's `client.subscribe("/topic/a", ...)`
    // call fires the handler synchronously — the handler's reentrant
    // `subscribe("/topic/b-late")` happens INSIDE the sweep's iteration.
    mockClientInstance!._invokeHandlerSyncOnce = "/topic/a";

    // Single triggerConnect — no reconnect cycle.
    mockClientInstance!.triggerConnect();
    await flushAll();

    expect(reentrantSubscribeFired).toBe(true);

    // Critical assertion: `/topic/b-late` must be in `_subs` after this ONE
    // triggerConnect. Because subscribe()'s direct attach was no-op'd, the
    // only way for b-late to reach `_subs` is via the sweep visiting the
    // Map entry that was added DURING iteration — i.e. live-Map iteration.
    // Snapshotting `entries.values()` would orphan it.
    const activeDestinations = mockClientInstance!._subs
      .filter((s) => s.active)
      .map((s) => s.destination);
    expect(activeDestinations).toContain("/topic/a");
    expect(activeDestinations).toContain("/topic/b-late");

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
