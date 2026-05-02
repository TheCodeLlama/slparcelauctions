"use client";

import Link from "next/link";
import type { ConnectionState } from "@/lib/ws/types";
import { AlertTriangle, Loader2 } from "@/components/ui/icons";

export interface ReconnectingBannerProps {
  state: ConnectionState;
}

/**
 * Inline banner surfaced inside the {@link BidPanel} bidder variant
 * whenever the WebSocket connection is not {@code connected}. Mirrors
 * spec §15's state table:
 *
 * <ul>
 *   <li>{@code connected}     → {@code null} (no banner).</li>
 *   <li>{@code connecting}    → {@code null} (initial load handled by
 *       the skeleton, not the banner).</li>
 *   <li>{@code reconnecting}  → "Reconnecting… bids paused." with a
 *       subtle spinner.</li>
 *   <li>{@code disconnected}  → "Connection lost…" with a reload
 *       button.</li>
 *   <li>{@code error}         → {@code state.detail}; when the detail
 *       suggests a session expiry, also render a sign-in link.</li>
 * </ul>
 *
 * Styling uses the M3-flavoured design tokens (see spec §15):
 * {@code rounded-default}, {@code bg-error-container},
 * {@code text-on-error-container}. Full-width inside the panel — the
 * banner pushes the form content below rather than overlaying it so the
 * bid inputs stay reachable by keyboard and screen reader.
 */
export function ReconnectingBanner({ state }: ReconnectingBannerProps) {
  switch (state.status) {
    case "connected":
    case "connecting":
      return null;
    case "reconnecting":
      return (
        <Banner tone="warning" testId="reconnecting-banner">
          <Loader2
            className="size-4 shrink-0 animate-spin"
            aria-hidden="true"
          />
          <span className="text-xs">
            Reconnecting… bids paused.
          </span>
        </Banner>
      );
    case "disconnected":
      return (
        <Banner tone="error" testId="reconnecting-banner">
          <AlertTriangle
            className="size-4 shrink-0"
            aria-hidden="true"
          />
          <span className="text-xs flex-1">
            Connection lost. Reload to see live updates.
          </span>
          <button
            type="button"
            onClick={handleReload}
            className="text-xs font-semibold underline underline-offset-2 hover:no-underline"
            data-testid="reconnecting-banner-reload"
          >
            Reload
          </button>
        </Banner>
      );
    case "error": {
      const needsSignIn = detailSuggestsSignIn(state.detail);
      return (
        <Banner tone="error" testId="reconnecting-banner">
          <AlertTriangle
            className="size-4 shrink-0"
            aria-hidden="true"
          />
          <span className="text-xs flex-1">
            {state.detail || "Connection error."}
          </span>
          {needsSignIn ? (
            <Link
              href="/login"
              className="text-xs font-semibold underline underline-offset-2 hover:no-underline"
              data-testid="reconnecting-banner-signin"
            >
              Sign in
            </Link>
          ) : null}
        </Banner>
      );
    }
  }
}

/**
 * Heuristic check on the WS {@code error} state's {@code detail} string
 * to decide whether to surface a sign-in link. The WS hardening layer
 * (see Task 1) emits human-readable detail strings for ERROR frames;
 * match on the canonical "session expired" / "sign in" phrasing so a
 * plain network-level error doesn't accidentally prompt the viewer to
 * re-auth.
 */
function detailSuggestsSignIn(detail: string | null | undefined): boolean {
  if (!detail) return false;
  const lower = detail.toLowerCase();
  return lower.includes("session expired") || lower.includes("sign in");
}

function handleReload(): void {
  if (typeof window === "undefined") return;
  window.location.reload();
}

type BannerTone = "warning" | "error";

function Banner({
  tone,
  children,
  testId,
}: {
  tone: BannerTone;
  children: React.ReactNode;
  testId: string;
}) {
  // All non-connected states share the error-container tone per spec
  // §15; the {@code tone} distinction is kept so that a future
  // "connecting-but-slow" state (or an A/B with warning-container
  // colors) can slot in without rewriting the surface.
  const toneClasses =
    tone === "warning"
      ? "bg-danger-bg text-danger-flat"
      : "bg-danger-bg text-danger-flat";
  return (
    <div
      role="status"
      aria-live="polite"
      data-testid={testId}
      data-tone={tone}
      className={`flex items-center gap-2 rounded-lg px-3 py-2 ${toneClasses}`}
    >
      {children}
    </div>
  );
}
