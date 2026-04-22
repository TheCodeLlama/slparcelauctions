"use client";

import { useEffect, useState } from "react";
import { cn } from "@/lib/cn";

export interface EscrowDeadlineBadgeProps {
  /** ISO-8601 deadline. */
  deadline: string;
  /** Trailing label; defaults to "left". */
  label?: string;
  className?: string;
}

type Urgency = "neutral" | "warning" | "urgent" | "past";

const urgencyClasses: Record<Urgency, string> = {
  neutral: "text-on-surface-variant",
  warning: "text-tertiary",
  urgent: "text-error",
  past: "text-error line-through",
};

function urgencyFor(msRemaining: number): Urgency {
  if (msRemaining <= 0) return "past";
  const hours = msRemaining / 3_600_000;
  if (hours < 6) return "urgent";
  if (hours < 24) return "warning";
  return "neutral";
}

function formatRemaining(msRemaining: number): string {
  if (msRemaining <= 0) return "past deadline";
  const abs = Math.abs(msRemaining);
  const hours = Math.floor(abs / 3_600_000);
  const minutes = Math.floor((abs % 3_600_000) / 60_000);
  if (hours >= 24) {
    const days = Math.floor(hours / 24);
    const remHours = hours % 24;
    return `${days}d ${remHours}h`;
  }
  return `${hours}h ${minutes}m`;
}

/**
 * Relative-time badge used alongside escrow deadlines (payment and transfer).
 * Re-renders every 30 s so the tone shifts neutral → warning → urgent → past
 * without a full page refresh. For sub-minute precision use the existing
 * `CountdownTimer` primitive; this one optimizes for the multi-hour windows
 * escrow operates on.
 */
export function EscrowDeadlineBadge({
  deadline,
  label = "left",
  className,
}: EscrowDeadlineBadgeProps) {
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    const interval = window.setInterval(() => setNow(Date.now()), 30_000);
    return () => window.clearInterval(interval);
  }, []);

  const msRemaining = new Date(deadline).getTime() - now;
  const urgency = urgencyFor(msRemaining);

  return (
    <span
      data-urgency={urgency}
      className={cn(
        "text-label-md font-medium",
        urgencyClasses[urgency],
        className,
      )}
    >
      {formatRemaining(msRemaining)} {label}
    </span>
  );
}
