"use client";

import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
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

const MENU_WIDTH = 192; // matches tailwind w-48 (12rem * 16px/rem)
const MENU_GAP = 4; // matches mt-1

/**
 * Per-row kebab menu on the admin realty groups table. Mirrors the
 * listings {@code RowActionMenu} primitive shape and styling.
 *
 * <p>The menu is rendered via {@link createPortal} into {@code document.body}
 * with a fixed-position coordinate computed from the trigger button's
 * bounding rect. Why a portal: the parent {@code <AdminRealtyGroupsTable>}
 * uses {@code overflow-x-auto} on its table wrapper, which silently
 * collapses to {@code overflow-y: auto} per the CSS spec the moment the
 * x-axis is set to anything other than {@code visible}. An absolutely-
 * positioned dropdown inside that wrapper gets clipped vertically — a
 * single-row table couldn't render the menu without a scrollbar
 * appearing inside the row.
 *
 * <p>Position is recomputed on every open and on subsequent
 * {@code scroll} / {@code resize} so the menu stays anchored to the
 * trigger even as the user scrolls the page. SSR-safe: the portal target
 * is resolved inside an effect, after the document is available.
 */
export function AdminRealtyGroupRowActionMenu({ isDissolved, onPick }: Props) {
  const [open, setOpen] = useState(false);
  const [coords, setCoords] = useState<{ top: number; left: number } | null>(
    null,
  );
  // Lazy initializer runs once at first client render where `document` is
  // defined; SSR is "use client" but still pre-render so the initializer
  // returns null safely. This avoids a synchronous setState-in-effect that
  // would trigger a cascading render (caught by the React 19
  // `react-hooks/set-state-in-effect` lint rule).
  const [portalTarget] = useState<HTMLElement | null>(() =>
    typeof document === "undefined" ? null : document.body,
  );
  const triggerRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);

  // Compute the menu's anchored position from the trigger's bounding rect.
  // Recomputed whenever the menu opens AND on scroll/resize so the menu
  // tracks the trigger as the user scrolls the page. Right-aligned to the
  // trigger so the menu sits flush with the kebab button.
  useLayoutEffect(() => {
    if (!open) return;
    function place() {
      const t = triggerRef.current;
      if (!t) return;
      const rect = t.getBoundingClientRect();
      setCoords({
        top: rect.bottom + MENU_GAP,
        left: rect.right - MENU_WIDTH,
      });
    }
    place();
    window.addEventListener("scroll", place, true);
    window.addEventListener("resize", place);
    return () => {
      window.removeEventListener("scroll", place, true);
      window.removeEventListener("resize", place);
    };
  }, [open]);

  useEffect(() => {
    if (!open) return;
    function onDocClick(e: MouseEvent) {
      const inTrigger = triggerRef.current?.contains(e.target as Node);
      const inMenu = menuRef.current?.contains(e.target as Node);
      if (!inTrigger && !inMenu) setOpen(false);
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

  const menu =
    open && coords && portalTarget
      ? createPortal(
          <div
            ref={menuRef}
            role="menu"
            data-testid="admin-realty-row-menu"
            style={{ top: coords.top, left: coords.left }}
            className="fixed z-50 w-48 rounded-lg bg-bg-subtle border border-border-subtle shadow-md py-1"
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
                className="w-full text-left px-3 py-1.5 text-[12px] text-fg hover:bg-bg-muted"
              >
                {a.label}
              </button>
            ))}
          </div>,
          portalTarget,
        )
      : null;

  return (
    <>
      <button
        ref={triggerRef}
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-label="Realty group actions"
        aria-expanded={open}
        data-testid="admin-realty-row-menu-trigger"
        className="p-1.5 rounded-lg text-fg-muted hover:bg-bg-muted"
      >
        <MoreVertical className="size-4" aria-hidden="true" />
      </button>
      {menu}
    </>
  );
}
