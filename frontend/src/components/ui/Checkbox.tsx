// frontend/src/components/ui/Checkbox.tsx
import { forwardRef, useId, type InputHTMLAttributes, type ReactNode } from "react";
import { Check } from "./icons";
import { cn } from "@/lib/cn";

type CheckboxProps = Omit<InputHTMLAttributes<HTMLInputElement>, "type"> & {
  label: ReactNode;
  error?: string;
};

/**
 * Styled checkbox primitive. Wraps a native <input type="checkbox"> with a
 * custom check icon overlay. API mirrors the existing Input primitive (label,
 * error, forwardRef so react-hook-form's register works).
 *
 * The label can be a React node so consumers can embed inline links:
 *   <Checkbox label={<>I agree to the <Link href="/terms">Terms</Link></>} />
 */
export const Checkbox = forwardRef<HTMLInputElement, CheckboxProps>(
  ({ label, error, className, id, ...props }, ref) => {
    const generatedId = useId();
    const checkboxId = id ?? generatedId;
    return (
      <div className={className}>
        <label
          htmlFor={checkboxId}
          className="flex items-start gap-3 cursor-pointer"
        >
          <div className="relative mt-0.5 shrink-0">
            <input
              ref={ref}
              id={checkboxId}
              type="checkbox"
              className={cn(
                "peer h-5 w-5 appearance-none rounded border-2 border-on-surface-variant/40",
                "bg-surface-container-lowest transition-colors",
                "checked:border-primary checked:bg-primary",
                "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary",
                error && "border-error"
              )}
              {...props}
            />
            <Check
              className="pointer-events-none absolute inset-0 hidden h-5 w-5 text-on-primary peer-checked:block"
              strokeWidth={3}
            />
          </div>
          <span className="text-body-sm text-on-surface">{label}</span>
        </label>
        {error && (
          <p className="ml-8 mt-1 text-label-sm text-error">{error}</p>
        )}
      </div>
    );
  }
);
Checkbox.displayName = "Checkbox";
