// frontend/src/lib/ws/types.ts
//
// Type-only module for the WebSocket layer. Separate from client.ts so tests
// can import the types without pulling in client.ts's module-level state.

/**
 * Discriminated union of the five states the WebSocket connection can be in.
 * Consumers branch on `status` and TypeScript narrows the shape.
 *
 *   disconnected  — initial state, or after the last subscriber unsubscribed
 *                   past the grace window.
 *   connecting    — beforeConnect has fired; awaiting STOMP CONNECTED.
 *   connected     — STOMP session is live, subscriptions can flow.
 *   reconnecting  — WebSocket closed unexpectedly and stompjs is retrying.
 *   error         — a STOMP ERROR frame arrived or auth refresh failed.
 */
export type ConnectionState =
  | { status: "disconnected" }
  | { status: "connecting" }
  | { status: "connected" }
  | { status: "reconnecting" }
  | { status: "error"; detail: string };

/**
 * Unsubscribe handle returned by `subscribe()` and `subscribeToConnectionState()`.
 * Calling the handle removes the subscription; idempotent.
 */
export type Unsubscribe = () => void;
