import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from "react";
import { cn } from "@/lib/cn";

type IconButtonVariant = "primary" | "secondary" | "tertiary";
type IconButtonSize = "sm" | "md" | "lg";

type IconButtonProps = {
  variant?: IconButtonVariant;
  size?: IconButtonSize;
  "aria-label": string;
  children: ReactNode;
} & Omit<ButtonHTMLAttributes<HTMLButtonElement>, "children" | "aria-label">;

const variantClasses: Record<IconButtonVariant, string> = {
  primary:
    "bg-brand text-white border border-brand hover:bg-brand-hover",
  secondary:
    "bg-surface-raised text-fg border border-border hover:bg-bg-hover hover:border-border-strong",
  tertiary:
    "bg-transparent text-fg-muted hover:bg-bg-hover hover:text-fg",
};

const sizeClasses: Record<IconButtonSize, string> = {
  sm: "h-8 w-8",
  md: "h-9 w-9",
  lg: "h-11 w-11",
};

const baseClasses =
  "inline-flex items-center justify-center rounded-sm transition-colors disabled:opacity-50 disabled:pointer-events-none focus:outline-none focus-visible:ring-2 focus-visible:ring-brand [&_svg]:size-[18px] [&_svg]:stroke-[1.75]";

export const IconButton = forwardRef<HTMLButtonElement, IconButtonProps>(
  function IconButton(
    {
      variant = "secondary",
      size = "md",
      className,
      children,
      type = "button",
      ...rest
    },
    ref
  ) {
    return (
      <button
        ref={ref}
        type={type}
        className={cn(
          baseClasses,
          variantClasses[variant],
          sizeClasses[size],
          className
        )}
        {...rest}
      >
        {children}
      </button>
    );
  }
);
