"use client";

import { useEffect, useRef, useState } from "react";
import { MoreVertical } from "@/components/ui/icons";
import type { AdminRealtyGroupAction } from "./AdminRealtyGroupActionModal";

type ActionDescriptor = {
  key: AdminRealtyGroupAction;
  label: string;
  destructive: boolean;
};

type Props = {
  /** True for dissolved groups; dissolve action is hidden. */
  isDissolved: boolean;
  onPick: (action: AdminRealtyGroupAction) => void;
};

/**
 * Per-row kebab menu on the admin realty groups table. Mirrors the
 * listings {@code RowActionMenu} primitive shape and styling.
 */
export function AdminRealtyGroupRowActionMenu({ isDissolved, onPick }: Props) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    function onDocClick(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) {
        setOpen(false);
      }
    }
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") setOpen(false);
    }
    document.addEventListener("mousedown", onDocClick);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDocClick);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  const actions: ActionDescriptor[] = [
    { key: "edit", label: "Force-edit name", destructive: false },
    ...(isDissolved
      ? []
      : [
          {
            key: "dissolve" as const,
            label: "Force-dissolve",
            destructive: true,
          },
        ]),
  ];

  return (
    <div ref={ref} className="relative inline-flex">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-label="Realty group actions"
        aria-expanded={open}
        data-testid="admin-realty-row-menu-trigger"
        className="p-1.5 rounded-lg text-fg-muted hover:bg-bg-muted"
      >
        <MoreVertical className="size-4" aria-hidden="true" />
      </button>
      {open && (
        <div
          role="menu"
          data-testid="admin-realty-row-menu"
          className="absolute right-0 top-full mt-1 z-30 w-48 rounded-lg bg-bg-subtle border border-border-subtle shadow-md py-1"
        >
          {actions.map((a) => (
            <button
              key={a.key}
              type="button"
              role="menuitem"
              onClick={() => {
                setOpen(false);
                onPick(a.key);
              }}
              data-testid={`admin-realty-row-action-${a.key}`}
              className={`w-full text-left px-3 py-1.5 text-[12px] ${
                a.destructive
                  ? "text-fg hover:bg-bg-muted"
                  : "text-fg hover:bg-bg-muted"
              }`}
            >
              {a.label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
