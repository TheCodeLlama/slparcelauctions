"use client";

import { useEffect, useRef, useState } from "react";
import { cn } from "@/lib/cn";

type CountdownTimerProps = {
  expiresAt: number;
  format?: "mm:ss" | "hh:mm:ss";
  onExpire?: () => void;
  className?: string;
};

function pad(n: number): string {
  return String(n).padStart(2, "0");
}

function formatRemaining(
  ms: number,
  format: "mm:ss" | "hh:mm:ss",
): string {
  if (ms <= 0) {
    return format === "hh:mm:ss" ? "--:--:--" : "--:--";
  }

  const totalSeconds = Math.ceil(ms / 1000);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;

  if (format === "hh:mm:ss") {
    return `${pad(hours)}:${pad(minutes)}:${pad(seconds)}`;
  }
  return `${pad(minutes)}:${pad(seconds)}`;
}

export function CountdownTimer({
  expiresAt,
  format = "mm:ss",
  onExpire,
  className,
}: CountdownTimerProps) {
  const [now, setNow] = useState(() => Date.now());
  const expiredRef = useRef(false);

  useEffect(() => {
    const id = setInterval(() => {
      setNow(Date.now());
    }, 1000);
    return () => clearInterval(id);
  }, []);

  const remaining = expiresAt - now;

  useEffect(() => {
    if (remaining <= 0 && !expiredRef.current) {
      expiredRef.current = true;
      onExpire?.();
    }
  }, [remaining, onExpire]);

  return (
    <time
      role="timer"
      aria-live="polite"
      className={cn("font-mono tabular-nums text-body-lg", className)}
    >
      {formatRemaining(remaining, format)}
    </time>
  );
}
