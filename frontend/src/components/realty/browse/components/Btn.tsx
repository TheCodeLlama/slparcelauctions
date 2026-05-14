"use client";

import type { ButtonHTMLAttributes, ReactNode } from "react";
import { cn } from "@/lib/cn";

type Variant = "primary" | "secondary" | "ghost" | "dark";
type Size = "sm" | "md" | "lg";

interface BtnProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: Size;
  block?: boolean;
  children: ReactNode;
}

const VARIANTS: Record<Variant, string> = {
  primary:
    "bg-brand text-on-brand border-brand hover:opacity-90 font-semibold",
  secondary:
    "bg-surface-raised text-fg border-border hover:bg-bg-muted hover:border-border-strong",
  ghost: "bg-transparent text-fg-muted border-transparent hover:bg-bg-muted hover:text-fg",
  dark: "bg-fg text-bg border-fg hover:opacity-90",
};

const SIZES: Record<Size, string> = {
  sm: "px-2.5 py-1.5 text-xs",
  md: "px-3.5 py-2 text-sm",
  lg: "px-5 py-3 text-sm",
};

export function Btn({
  variant = "secondary",
  size = "md",
  block,
  className,
  children,
  ...rest
}: BtnProps) {
  return (
    <button
      className={cn(
        "inline-flex items-center justify-center gap-1.5 rounded-md border font-medium transition-colors disabled:opacity-50 disabled:cursor-not-allowed active:translate-y-px whitespace-nowrap leading-tight",
        VARIANTS[variant],
        SIZES[size],
        block && "w-full",
        className,
      )}
      {...rest}
    >
      {children}
    </button>
  );
}
