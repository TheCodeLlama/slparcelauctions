"use client";

import { useId } from "react";
import { Check } from "@/components/ui/icons";
import { cn } from "@/lib/cn";
import {
  permissionDescription,
  permissionLabel,
} from "@/lib/realty/permissions";
import type { RealtyGroupPermission } from "@/types/realty";

export interface PermissionToggleRowProps {
  permission: RealtyGroupPermission;
  checked: boolean;
  onChange: (next: boolean) => void;
  disabled?: boolean;
  className?: string;
}

/**
 * Reusable row primitive for the realty-group permission toggles. Renders
 * a label, description, and a checkbox-style toggle. Used in the invite
 * form and the member permission editor modal.
 *
 * Styled to match {@link Checkbox} but built fresh because the row layout
 * differs (description beneath the label, larger hit target).
 */
export function PermissionToggleRow({
  permission,
  checked,
  onChange,
  disabled,
  className,
}: PermissionToggleRowProps) {
  const id = useId();
  const inputId = `perm-${id}`;

  return (
    <label
      htmlFor={inputId}
      className={cn(
        "flex items-start gap-3 rounded-lg border border-border bg-surface-raised px-3 py-2.5 cursor-pointer transition-colors hover:bg-bg-hover",
        disabled && "cursor-not-allowed opacity-60 hover:bg-surface-raised",
        className,
      )}
      data-testid={`permission-row-${permission}`}
    >
      <div className="relative mt-0.5 shrink-0">
        <input
          id={inputId}
          type="checkbox"
          checked={checked}
          disabled={disabled}
          onChange={(e) => onChange(e.target.checked)}
          className={cn(
            "peer h-5 w-5 appearance-none rounded border-2 border-fg-muted/40",
            "bg-surface-raised transition-colors",
            "checked:border-brand checked:bg-brand",
            "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand",
            "disabled:cursor-not-allowed",
          )}
          data-testid={`permission-checkbox-${permission}`}
        />
        <Check
          className="pointer-events-none absolute inset-0 hidden h-5 w-5 text-white peer-checked:block"
          strokeWidth={3}
          aria-hidden="true"
        />
      </div>
      <div className="flex flex-col gap-0.5 min-w-0">
        <span className="text-sm font-medium text-fg">
          {permissionLabel(permission)}
        </span>
        <span className="text-xs text-fg-muted">
          {permissionDescription(permission)}
        </span>
      </div>
    </label>
  );
}
