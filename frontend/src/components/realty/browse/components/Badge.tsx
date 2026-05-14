"use client";

import type { ReactNode } from "react";
import { cn } from "@/lib/cn";

type Tone =
  | "brand"
  | "success"
  | "warning"
  | "danger"
  | "info"
  | "neutral"
  | "outline";

interface BadgeProps {
  tone?: Tone;
  dot?: boolean;
  pulse?: boolean;
  children: ReactNode;
  className?: string;
}

const TONES: Record<Tone, string> = {
  brand: "bg-brand/10 text-brand",
  success: "bg-green-500/10 text-green-700",
  warning: "bg-amber-500/10 text-amber-700",
  danger: "bg-danger/10 text-danger",
  info: "bg-blue-500/10 text-blue-700",
  neutral: "bg-bg-muted text-fg-muted",
  outline: "bg-transparent border border-border text-fg-muted",
};

export function Badge({ tone = "neutral", dot, pulse, children, className }: BadgeProps) {
  return (
    <span
      className={cn(
        "inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[11.5px] font-semibold leading-relaxed whitespace-nowrap",
        TONES[tone],
        className,
      )}
    >
      {dot && (
        <span
          className={cn(
            "relative inline-block w-1.5 h-1.5 rounded-full bg-current",
            pulse &&
              "before:absolute before:inset-[-3px] before:rounded-full before:bg-current before:opacity-30 before:animate-ping",
          )}
        />
      )}
      {children}
    </span>
  );
}
