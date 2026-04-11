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
    "bg-gradient-to-br from-primary to-primary-container text-on-primary shadow-soft hover:shadow-elevated",
  secondary:
    "bg-surface-container-lowest text-on-surface shadow-soft hover:shadow-elevated",
  tertiary: "text-on-surface-variant hover:bg-surface-container-low",
};

const sizeClasses: Record<IconButtonSize, string> = {
  sm: "h-9 w-9",
  md: "h-11 w-11",
  lg: "h-12 w-12",
};

const baseClasses =
  "inline-flex items-center justify-center rounded-full transition-all disabled:opacity-50 disabled:pointer-events-none [&_svg]:size-5 [&_svg]:stroke-[1.5]";

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
