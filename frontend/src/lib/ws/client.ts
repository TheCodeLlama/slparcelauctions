// frontend/src/lib/ws/client.ts
"use client";

import { Client, type IMessage, type StompSubscription } from "@stomp/stompjs";
import SockJS from "sockjs-client";
import { ensureFreshAccessToken, RefreshFailedError } from "@/lib/auth/refresh";
import type { ConnectionState, Unsubscribe } from "./types";

const WS_URL = process.env.NEXT_PUBLIC_WS_URL ?? "http://localhost:8080/ws";
const DISCONNECT_GRACE_MS = 5_000;

/**
 * Internal registry entry. One per live `subscribe()` call. The `handle` is
 * null whenever the subscription is not currently attached to a STOMP session —
 * either we're still connecting, or the socket just closed and we're waiting
 * for onConnect to re-attach.
 */
interface SubscriptionEntry<T> {
  id: string;
  destination: string;
  onMessage: (payload: T) => void;
  handle: StompSubscription | null;
}

let client: Client | null = null;
let subscriberCount = 0;
let disconnectTimer: ReturnType<typeof setTimeout> | null = null;
let connectionState: ConnectionState = { status: "disconnected" };
const stateListeners = new Set<(state: ConnectionState) => void>();
// Keyed by entry.id so unsubscribe() can locate its entry in O(1) even if the
// same destination is subscribed multiple times.
const entries = new Map<string, SubscriptionEntry<unknown>>();

function setState(next: ConnectionState): void {
  connectionState = next;
  for (const listener of stateListeners) listener(next);
}

export function getConnectionState(): ConnectionState {
  return connectionState;
}

export function subscribeToConnectionState(
  listener: (state: ConnectionState) => void
): Unsubscribe {
  stateListeners.add(listener);
  // Fire immediately with current state so consumers have something to render.
  listener(connectionState);
  return () => {
    stateListeners.delete(listener);
  };
}

/**
 * Attach one registry entry to the live STOMP session, if possible.
 *
 * No-op when the client is not connected (subscribe deferral) or when the
 * entry is already attached (idempotent — safe to call from both subscribe()
 * and the onConnect bulk sweep).
 */
function ensureAttached(entry: SubscriptionEntry<unknown>): void {
  if (!client?.connected) return;
  if (entry.handle !== null) return;
  entry.handle = client.subscribe(entry.destination, (frame: IMessage) => {
    try {
      const parsed = JSON.parse(frame.body);
      entry.onMessage(parsed);
    } catch (err) {
      console.error(
        `[ws] Failed to parse message body on ${entry.destination}:`,
        err,
        frame.body
      );
    }
  });
}

function getOrCreateClient(): Client {
  if (client) return client;

  client = new Client({
    webSocketFactory: () => new SockJS(WS_URL),
    reconnectDelay: 5_000,
    heartbeatIncoming: 10_000,
    heartbeatOutgoing: 10_000,
    beforeConnect: async () => {
      if (!client) return;
      setState({ status: "connecting" });
      try {
        const token = await ensureFreshAccessToken();
        client.connectHeaders = { Authorization: `Bearer ${token}` };
      } catch (e) {
        // Do NOT throw from beforeConnect — stompjs handles a thrown
        // beforeConnect as a catastrophic failure. Instead, let stompjs
        // proceed without an Authorization header; the interceptor will
        // reject the CONNECT frame, onStompError will fire, and the
        // ConnectionState machine will surface the error.
        //
        // See FOOTGUNS §F.18.
        if (e instanceof RefreshFailedError) {
          setState({
            status: "error",
            detail: "Session expired. Please sign in again.",
          });
        } else {
          setState({
            status: "error",
            detail: "Could not refresh access token",
          });
        }
      }
    },
    onConnect: () => {
      setState({ status: "connected" });
      // LIVE-MAP SWEEP — critical invariant (spec §6):
      //
      // Iterate `entries` directly rather than a snapshot like
      // `Array.from(entries.values())`. Map iteration is spec-defined to visit
      // entries added during iteration *if they haven't been reached yet*. A
      // handler invoked inside ensureAttached could synchronously call
      // `subscribe()` (e.g. a React effect firing a nested subscription when
      // it receives its first replayed frame). Snapshotting would leave that
      // late-added entry unattached until the next reconnect cycle; iterating
      // the live Map attaches it in the same sweep.
      //
      // ensureAttached() is idempotent, so entries whose subscribe() path
      // already attached them (when `client.connected` was true at subscribe
      // time) are a no-op here.
      for (const entry of entries.values()) {
        ensureAttached(entry);
      }
    },
    onWebSocketClose: () => {
      // The underlying STOMP subs are already dead — calling unsubscribe() on
      // them would throw or no-op depending on stompjs internals, so we just
      // null the handles. The next onConnect sweep will re-attach every entry.
      for (const entry of entries.values()) {
        entry.handle = null;
      }
      if (client?.active) {
        setState({ status: "reconnecting" });
      } else {
        setState({ status: "disconnected" });
      }
    },
    onStompError: (frame) => {
      const detail = frame.headers["message"] ?? "STOMP error";
      setState({ status: "error", detail });
    },
  });

  return client;
}

/**
 * Subscribe to a STOMP destination. Returns an unsubscribe closure. The
 * callback receives the JSON-parsed message payload typed as `T`. Malformed
 * JSON is logged via `console.error` and the subscription stays alive.
 *
 * Reference counting: the first subscribe activates the singleton client;
 * the last unsubscribe schedules a `DISCONNECT_GRACE_MS` deactivation. A new
 * subscribe inside the grace window cancels the teardown.
 *
 * Re-attach model: every call registers an entry in the module-level `entries`
 * Map. If the client is connected, we attach immediately; otherwise the entry
 * stays with `handle === null` and is swept up by the next `onConnect`.
 * Disconnects null every handle without unsubscribing dead STOMP subs.
 */
export function subscribe<T>(
  destination: string,
  onMessage: (payload: T) => void
): Unsubscribe {
  const c = getOrCreateClient();
  subscriberCount += 1;

  if (disconnectTimer) {
    clearTimeout(disconnectTimer);
    disconnectTimer = null;
  }

  if (!c.active) {
    c.activate();
  }

  const entry: SubscriptionEntry<T> = {
    id: crypto.randomUUID(),
    destination,
    onMessage,
    handle: null,
  };
  entries.set(entry.id, entry as SubscriptionEntry<unknown>);

  ensureAttached(entry as SubscriptionEntry<unknown>);

  return () => {
    const currentEntry = entries.get(entry.id);
    if (!currentEntry) return; // idempotent — second call is a no-op
    if (currentEntry.handle && client?.connected) {
      try {
        currentEntry.handle.unsubscribe();
      } catch {
        // Tolerant — some stompjs paths throw if the session is mid-teardown.
      }
    }
    entries.delete(entry.id);
    subscriberCount = Math.max(0, subscriberCount - 1);
    if (subscriberCount === 0) {
      scheduleTeardown();
    }
  };
}

function scheduleTeardown(): void {
  if (disconnectTimer) return;
  disconnectTimer = setTimeout(() => {
    disconnectTimer = null;
    if (subscriberCount === 0 && client) {
      client.deactivate();
      setState({ status: "disconnected" });
    }
  }, DISCONNECT_GRACE_MS);
}

// Manual controls for the /dev/ws-test page's Disconnect / Reconnect buttons.
// Consumers should NOT use these in production code — they bypass reference
// counting and can leave other subscribers stranded.
export function __devForceDisconnect(): void {
  if (client?.active) client.deactivate();
  setState({ status: "disconnected" });
}

export function __devForceReconnect(): void {
  const c = getOrCreateClient();
  if (!c.active) c.activate();
}

// Test-only reset. Clears all module state so each test starts from scratch.
export function __resetWsClientForTests(): void {
  if (client) {
    try {
      client.deactivate();
    } catch {
      // Ignore — mocked client may not implement deactivate cleanly.
    }
  }
  client = null;
  subscriberCount = 0;
  if (disconnectTimer) {
    clearTimeout(disconnectTimer);
    disconnectTimer = null;
  }
  stateListeners.clear();
  entries.clear();
  connectionState = { status: "disconnected" };
}

// Test-only accessor for the registry. Used by memory-hygiene tests to verify
// unsubscribe() really removes entries.
export function __getRegistrySizeForTests(): number {
  return entries.size;
}
