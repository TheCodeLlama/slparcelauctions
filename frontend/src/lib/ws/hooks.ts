// frontend/src/lib/ws/hooks.ts
"use client";

import { useEffect, useRef, useState } from "react";
import {
  getConnectionState,
  subscribe,
  subscribeToConnectionState,
} from "./client";
import type { ConnectionState } from "./types";

/**
 * React-friendly accessor for the module-level ConnectionState. Re-renders
 * whenever the underlying state changes. Fires immediately on mount with the
 * current state (the listener is invoked synchronously inside
 * `subscribeToConnectionState`).
 */
export function useConnectionState(): ConnectionState {
  const [state, setState] = useState<ConnectionState>(() => getConnectionState());
  useEffect(() => {
    return subscribeToConnectionState(setState);
  }, []);
  return state;
}

/**
 * React-friendly wrapper around `subscribe()`. The callback is held in a ref
 * so re-renders with a new inline function identity do not re-subscribe; only
 * a change to the `destination` string triggers a new subscription.
 */
export function useStompSubscription<T>(
  destination: string,
  onMessage: (payload: T) => void
): void {
  const callbackRef = useRef(onMessage);
  // Intentional stable-callback pattern: assign the latest callback to the
  // ref during render so the effect below never closes over a stale value and
  // a new inline-function identity does NOT trigger a resubscribe. Moving
  // this into a useEffect would defeat the purpose (the effect runs AFTER
  // render, so the ref would lag by one render).
  // eslint-disable-next-line react-hooks/refs -- intentional stable-callback ref
  callbackRef.current = onMessage;

  useEffect(() => {
    const unsubscribe = subscribe<T>(destination, (payload) => {
      callbackRef.current(payload);
    });
    return unsubscribe;
  }, [destination]);
}
