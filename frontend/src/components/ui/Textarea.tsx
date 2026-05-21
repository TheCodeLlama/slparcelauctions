"use client";
import {
  forwardRef,
  useId,
  type ReactNode,
  type TextareaHTMLAttributes,
} from "react";
import { cn } from "@/lib/cn";

type TextareaProps = {
  label?: string;
  helperText?: ReactNode;
  error?: string;
  fullWidth?: boolean;
} & TextareaHTMLAttributes<HTMLTextAreaElement>;

const baseClasses =
  "w-full rounded-lg bg-bg-subtle text-fg placeholder:text-fg-muted p-3 ring-1 ring-transparent transition-all focus:bg-surface-raised focus:outline-none focus:ring-brand resize-y";

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaProps>(function Textarea(
  { label, helperText, error, fullWidth = true, className, id, rows = 6, ...rest },
  ref,
) {
  const generatedId = useId();
  const taId = id ?? generatedId;
  const showError = Boolean(error);

  return (
    <div className={cn("flex flex-col gap-1", fullWidth && "w-full")}>
      {label && (
        <label htmlFor={taId} className="text-sm text-fg">
          {label}
        </label>
      )}
      <textarea
        ref={ref}
        id={taId}
        rows={rows}
        className={cn(
          baseClasses,
          showError && "ring-danger focus:ring-danger",
          className,
        )}
        aria-invalid={showError || undefined}
        {...rest}
      />
      {(helperText || error) && (
        <p className={cn("text-xs", showError ? "text-danger" : "text-fg-muted")}>
          {error ?? helperText}
        </p>
      )}
    </div>
  );
});
