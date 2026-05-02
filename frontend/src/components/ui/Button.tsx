import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from "react";
import { Loader2 } from "./icons";
import { cn } from "@/lib/cn";

type ButtonVariant = "primary" | "secondary" | "tertiary" | "destructive" | "dark";
type ButtonSize = "sm" | "md" | "lg" | "xl";

type ButtonProps = {
  variant?: ButtonVariant;
  size?: ButtonSize;
  loading?: boolean;
  leftIcon?: ReactNode;
  rightIcon?: ReactNode;
  fullWidth?: boolean;
  children: ReactNode;
} & Omit<ButtonHTMLAttributes<HTMLButtonElement>, "children">;

const variantClasses: Record<ButtonVariant, string> = {
  primary:
    "bg-brand text-white border-brand hover:bg-brand-hover hover:border-brand-hover",
  secondary:
    "bg-surface-raised text-fg border border-border hover:bg-bg-hover hover:border-border-strong",
  tertiary: "bg-transparent text-fg-muted hover:bg-bg-hover hover:text-fg",
  destructive:
    "bg-danger-flat text-white border-danger-flat hover:opacity-90",
  dark: "bg-fg text-bg border-fg hover:opacity-90",
};

const sizeClasses: Record<ButtonSize, string> = {
  sm: "h-8 px-3 text-xs",
  md: "h-9 px-4 text-sm",
  lg: "h-11 px-5 text-sm",
  xl: "h-12 px-6 text-base",
};

const baseClasses =
  "inline-flex items-center justify-center gap-1.5 rounded-sm border font-medium transition-colors disabled:opacity-50 disabled:pointer-events-none focus:outline-none focus-visible:ring-2 focus-visible:ring-brand";

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  {
    variant = "primary",
    size = "md",
    loading = false,
    leftIcon,
    rightIcon,
    fullWidth = false,
    disabled,
    className,
    children,
    type = "button",
    ...rest
  },
  ref
) {
  const isDisabled = disabled || loading;
  const renderedLeft = loading ? (
    <Loader2 className="size-4 animate-spin" />
  ) : (
    leftIcon
  );

  return (
    <button
      ref={ref}
      type={type}
      disabled={isDisabled}
      className={cn(
        baseClasses,
        variantClasses[variant],
        sizeClasses[size],
        fullWidth && "w-full",
        className
      )}
      {...rest}
    >
      {renderedLeft}
      <span>{children}</span>
      {rightIcon}
    </button>
  );
});
