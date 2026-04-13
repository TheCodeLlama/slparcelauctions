// frontend/src/components/dev/WsTestHarness.tsx
"use client";

import { useState } from "react";
import { api } from "@/lib/api";
import { __devForceDisconnect, __devForceReconnect } from "@/lib/ws/client";
import { useConnectionState, useStompSubscription } from "@/lib/ws/hooks";
import type { ConnectionState } from "@/lib/ws/types";

// senderId is a Java Long on the backend, serialized as a JSON number, which
// parses to a plain JS number here. User IDs are well under 2^53 - 1. See
// spec §5.4 for the wire-type explanation.
type WsTestMessage = {
  message: string;
  senderId: number;
  timestamp: string;
};

const MAX_MESSAGES = 50;

export function WsTestHarness() {
  const state = useConnectionState();
  const [messages, setMessages] = useState<WsTestMessage[]>([]);
  const [input, setInput] = useState("");
  const [sending, setSending] = useState(false);
  const [sendError, setSendError] = useState<string | null>(null);

  useStompSubscription<WsTestMessage>("/topic/ws-test", (payload) => {
    setMessages((prev) => [payload, ...prev].slice(0, MAX_MESSAGES));
  });

  const sendBroadcast = async () => {
    const trimmed = input.trim();
    if (!trimmed) return;
    setSending(true);
    setSendError(null);
    try {
      await api.post("/api/ws-test/broadcast", { message: trimmed });
      setInput("");
    } catch (err) {
      setSendError(err instanceof Error ? err.message : "Failed to send");
    } finally {
      setSending(false);
    }
  };

  return (
    <main className="mx-auto max-w-3xl p-8">
      <h1 className="text-2xl font-semibold text-on-surface">
        WebSocket Test Harness
      </h1>
      <p className="mt-2 text-on-surface-variant">
        Dev-only page for verifying the STOMP pipe. Returns 404 in production
        builds.
      </p>

      <div className="mt-6">
        <ConnectionBadge state={state} />
      </div>

      <div className="mt-4 flex gap-2">
        <button
          type="button"
          onClick={__devForceDisconnect}
          className="rounded-md bg-surface-container px-3 py-2 text-sm text-on-surface hover:bg-surface-container-high"
        >
          Force Disconnect
        </button>
        <button
          type="button"
          onClick={__devForceReconnect}
          className="rounded-md bg-surface-container px-3 py-2 text-sm text-on-surface hover:bg-surface-container-high"
        >
          Force Reconnect
        </button>
      </div>

      <form
        onSubmit={(e) => {
          e.preventDefault();
          void sendBroadcast();
        }}
        className="mt-8"
      >
        <label
          htmlFor="ws-test-input"
          className="block text-sm font-medium text-on-surface"
        >
          Send test message
        </label>
        <div className="mt-2 flex gap-2">
          <input
            id="ws-test-input"
            type="text"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            placeholder="Type a message"
            className="flex-1 rounded-md bg-surface-container px-3 py-2 text-on-surface placeholder:text-on-surface-variant"
          />
          <button
            type="submit"
            disabled={sending || !input.trim()}
            className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-on-primary disabled:opacity-50"
          >
            {sending ? "Sending..." : "Send"}
          </button>
        </div>
        {sendError ? (
          <p className="mt-2 text-sm text-error">{sendError}</p>
        ) : null}
      </form>

      <section className="mt-8">
        <h2 className="text-lg font-medium text-on-surface">
          Received ({messages.length})
        </h2>
        <ol className="mt-2 space-y-2">
          {messages.map((m, i) => (
            <li
              key={`${m.timestamp}-${i}`}
              className="rounded-md bg-surface-container p-3"
            >
              <div className="flex items-baseline justify-between">
                <span className="text-on-surface">{m.message}</span>
                <span className="font-mono text-xs text-on-surface-variant">
                  {m.timestamp}
                </span>
              </div>
              <div className="mt-1 text-xs text-on-surface-variant">
                from userId {m.senderId}
              </div>
            </li>
          ))}
          {messages.length === 0 ? (
            <li className="rounded-md bg-surface-container p-3 text-sm text-on-surface-variant">
              No messages yet. Send one or trigger a broadcast from another
              tab / curl.
            </li>
          ) : null}
        </ol>
      </section>
    </main>
  );
}

function ConnectionBadge({ state }: { state: ConnectionState }) {
  const label = {
    disconnected: "Disconnected",
    connecting: "Connecting...",
    connected: "Connected",
    reconnecting: "Reconnecting...",
    error: "Error",
  }[state.status];

  const tone = {
    disconnected: "bg-surface-container text-on-surface-variant",
    connecting: "bg-tertiary-container text-on-tertiary-container",
    connected: "bg-primary-container text-on-primary-container",
    reconnecting: "bg-tertiary-container text-on-tertiary-container",
    error: "bg-error-container text-on-error-container",
  }[state.status];

  return (
    <div className={`inline-flex flex-col rounded-md px-3 py-2 text-sm ${tone}`}>
      <span className="font-medium">{label}</span>
      {state.status === "error" ? (
        <span className="mt-1 text-xs">{state.detail}</span>
      ) : null}
    </div>
  );
}
