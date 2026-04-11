import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from "react";
import { Loader2 } from "./icons";
import { cn } from "@/lib/cn";

type ButtonVariant = "primary" | "secondary" | "tertiary";
type ButtonSize = "sm" | "md" | "lg";

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
    "bg-gradient-to-br from-primary to-primary-container text-on-primary shadow-soft hover:shadow-elevated",
  secondary:
    "bg-surface-container-lowest text-on-surface shadow-soft hover:shadow-elevated",
  tertiary: "text-primary hover:underline underline-offset-4",
};

const sizeClasses: Record<ButtonSize, string> = {
  sm: "h-9 px-4 text-label-md",
  md: "h-11 px-5 text-label-lg",
  lg: "h-12 px-6 text-title-md",
};

const baseClasses =
  "inline-flex items-center justify-center gap-2 rounded-default font-medium transition-all disabled:opacity-50 disabled:pointer-events-none";

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
