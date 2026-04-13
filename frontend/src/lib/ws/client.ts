// frontend/src/lib/ws/client.ts
"use client";

import { Client, type IMessage, type StompSubscription } from "@stomp/stompjs";
import SockJS from "sockjs-client";
import { ensureFreshAccessToken, RefreshFailedError } from "@/lib/auth/refresh";
import type { ConnectionState, Unsubscribe } from "./types";

const WS_URL = process.env.NEXT_PUBLIC_WS_URL ?? "http://localhost:8080/ws";
const DISCONNECT_GRACE_MS = 5_000;

let client: Client | null = null;
let subscriberCount = 0;
let disconnectTimer: ReturnType<typeof setTimeout> | null = null;
let connectionState: ConnectionState = { status: "disconnected" };
const stateListeners = new Set<(state: ConnectionState) => void>();

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
            detail: "Session expired — please sign in again",
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
    },
    onWebSocketClose: () => {
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
 * Deferral: if the client is not yet connected, the actual `client.subscribe`
 * call is deferred until `onConnect` fires. See FOOTGUNS §F.19.
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

  let stompSub: StompSubscription | null = null;

  const attach = () => {
    stompSub = c.subscribe(destination, (frame: IMessage) => {
      try {
        const parsed = JSON.parse(frame.body) as T;
        onMessage(parsed);
      } catch (err) {
        console.error(
          `[ws] Failed to parse message body on ${destination}:`,
          err,
          frame.body
        );
      }
    });
  };

  if (c.connected) {
    attach();
  } else {
    // KNOWN RACE (Epic 04 hardening followup, spec §14.7):
    //   If onConnect → onWebSocketClose fires in rapid succession, the
    //   listener sees "reconnecting" mid-cycle and keeps waiting through the
    //   reconnect. On the next successful connect the listener fires and
    //   attach() runs — subscription not lost, just delayed by one reconnect
    //   cycle. Acceptable for 01-09; Epic 04 will want a more robust
    //   re-attach strategy.
    const stateUnsub = subscribeToConnectionState((state) => {
      if (state.status === "connected") {
        attach();
        stateUnsub();
      }
    });
  }

  return () => {
    if (stompSub) stompSub.unsubscribe();
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
  connectionState = { status: "disconnected" };
}
