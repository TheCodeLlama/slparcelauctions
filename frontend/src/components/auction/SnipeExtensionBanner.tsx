"use client";

import { useEffect } from "react";
import { Shield } from "@/components/ui/icons";

export interface SnipeExtensionBannerProps {
  isVisible: boolean;
  extensionMinutes: number;
  /**
   * Pre-formatted remaining-time label, e.g. {@code "2h 14m now remaining"}.
   * The caller owns formatting so the banner stays purely presentational.
   */
  remainingAfterExtension: string;
  /**
   * Fires 4s after the banner becomes visible. The parent is expected to
   * flip {@code isVisible} to false in response — the banner is
   * controlled, so there's no internal hide state. If a fresh extension
   * arrives before the timer elapses, the parent can bump its state
   * timestamp to restart the timer here (via the {@code key} prop on
   * this component, or by re-running the effect through a changing
   * {@code extensionMinutes}).
   */
  onExpire: () => void;
}

const VISIBLE_MS = 4_000;

/**
 * Transient banner shown at the top of the {@link BidPanel} when an
 * incoming {@code BID_SETTLEMENT} envelope reports a snipe-protection
 * extension. Copy mirrors spec §10:
 *
 * <pre>
 *   Auction extended by {N}m — {remainingAfterExtension}
 * </pre>
 *
 * Lifetime is driven by the parent: pass {@code isVisible=true} to
 * render, the banner auto-schedules its {@code onExpire} callback 4s
 * after mount. If a second extension arrives while still visible, the
 * parent should update the props AND force a re-mount (e.g. via a
 * {@code key} that changes on each arrival) so the timer restarts.
 */
export function SnipeExtensionBanner({
  isVisible,
  extensionMinutes,
  remainingAfterExtension,
  onExpire,
}: SnipeExtensionBannerProps) {
  useEffect(() => {
    if (!isVisible) return;
    const handle = setTimeout(() => {
      onExpire();
    }, VISIBLE_MS);
    return () => clearTimeout(handle);
  }, [isVisible, onExpire]);

  if (!isVisible) return null;

  return (
    <div
      role="status"
      aria-live="polite"
      data-testid="snipe-extension-banner"
      className="flex items-center gap-2 rounded-lg bg-brand-soft px-3 py-2 text-brand"
    >
      <Shield className="size-4 shrink-0" aria-hidden="true" />
      <span className="text-xs">
        <span className="font-semibold">
          Auction extended by {extensionMinutes}m
        </span>
        {" — "}
        <span>{remainingAfterExtension}</span>
      </span>
    </div>
  );
}

/**
 * Formats a remaining-time label for the banner. Input is milliseconds
 * until {@code endsAt} from "now" (can be negative — the banner still
 * renders "less than a minute remaining" in that edge).
 */
export function formatRemainingLabel(msRemaining: number): string {
  if (!Number.isFinite(msRemaining) || msRemaining <= 60_000) {
    return "less than a minute remaining";
  }
  const totalMin = Math.floor(msRemaining / 60_000);
  const hours = Math.floor(totalMin / 60);
  const minutes = totalMin % 60;
  if (hours > 0 && minutes > 0) return `${hours}h ${minutes}m now remaining`;
  if (hours > 0) return `${hours}h now remaining`;
  return `${minutes}m now remaining`;
}
