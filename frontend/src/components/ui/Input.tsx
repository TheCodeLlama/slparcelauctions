import {
  forwardRef,
  useId,
  type InputHTMLAttributes,
  type ReactNode,
} from "react";
import { cn } from "@/lib/cn";

type InputProps = {
  label?: string;
  helperText?: string;
  error?: string;
  leftIcon?: ReactNode;
  rightIcon?: ReactNode;
  fullWidth?: boolean;
} & InputHTMLAttributes<HTMLInputElement>;

const baseClasses =
  "h-12 w-full rounded-lg bg-bg-subtle text-fg placeholder:text-fg-muted px-4 ring-1 ring-transparent transition-all focus:bg-surface-raised focus:outline-none focus:ring-brand";

export const Input = forwardRef<HTMLInputElement, InputProps>(function Input(
  {
    label,
    helperText,
    error,
    leftIcon,
    rightIcon,
    fullWidth = true,
    className,
    id,
    ...rest
  },
  ref
) {
  const generatedId = useId();
  const inputId = id ?? generatedId;
  const showError = Boolean(error);

  return (
    <div className={cn("flex flex-col gap-1", fullWidth && "w-full")}>
      {label && (
        <label
          htmlFor={inputId}
          className="text-xs font-medium text-fg-muted"
        >
          {label}
        </label>
      )}
      <div className="relative">
        {leftIcon && (
          <span className="absolute left-3 top-1/2 -translate-y-1/2 text-fg-muted [&_svg]:size-5 [&_svg]:stroke-[1.5]">
            {leftIcon}
          </span>
        )}
        <input
          ref={ref}
          id={inputId}
          className={cn(
            baseClasses,
            leftIcon && "pl-10",
            rightIcon && "pr-10",
            showError && "ring-danger focus:ring-danger",
            className
          )}
          aria-invalid={showError || undefined}
          {...rest}
        />
        {rightIcon && (
          <span className="absolute right-3 top-1/2 -translate-y-1/2 text-fg-muted [&_svg]:size-5 [&_svg]:stroke-[1.5]">
            {rightIcon}
          </span>
        )}
      </div>
      {showError ? (
        <span className="text-xs text-danger">{error}</span>
      ) : helperText ? (
        <span className="text-xs text-fg-muted">{helperText}</span>
      ) : null}
    </div>
  );
});
