"use client";
import { useState, useRef, useEffect } from "react";
import { MoreVertical } from "@/components/ui/icons";
import type { AdminListingAction, AuctionStatus } from "@/lib/admin/types";

type ActionGate = { allowed: boolean; reason?: string };

/**
 * Per-action allow/deny rules. Mirrors the backend status guards: the
 * disabled tooltip explains why a given action isn't available for the
 * current status. A request that slips past these guards still gets
 * rejected by the backend with INVALID_STATUS_FOR_ACTION.
 */
function gateFor(status: AuctionStatus, action: AdminListingAction): ActionGate {
  switch (action) {
    case "warn":
      // Warning is about the seller's behavior — allowed in any status.
      return { allowed: true };
    case "suspend":
      if (status === "SUSPENDED") {
        return { allowed: false, reason: "Already suspended" };
      }
      if (status === "COMPLETED" || status === "CANCELLED" || status === "EXPIRED") {
        return { allowed: false, reason: `Cannot suspend a ${status.toLowerCase()} listing` };
      }
      return { allowed: true };
    case "cancel":
      if (status === "COMPLETED" || status === "CANCELLED" || status === "EXPIRED") {
        return { allowed: false, reason: `Already ${status.toLowerCase()}` };
      }
      return { allowed: true };
    case "reinstate":
      if (status !== "SUSPENDED") {
        return { allowed: false, reason: "Only suspended listings can be reinstated" };
      }
      return { allowed: true };
  }
}

type Props = {
  status: AuctionStatus;
  onPick: (action: AdminListingAction) => void;
};

const ACTIONS: { key: AdminListingAction; label: string; destructive: boolean }[] = [
  { key: "warn",      label: "Warn seller",      destructive: true },
  { key: "suspend",   label: "Suspend listing",  destructive: true },
  { key: "cancel",    label: "Cancel listing",   destructive: true },
  { key: "reinstate", label: "Reinstate listing", destructive: false },
];

export function RowActionMenu({ status, onPick }: Props) {
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

  return (
    <div ref={ref} className="relative inline-flex">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-label="Listing actions"
        aria-expanded={open}
        data-testid="row-action-menu-trigger"
        className="p-1.5 rounded-lg text-fg-muted hover:bg-bg-muted"
      >
        <MoreVertical className="size-4" aria-hidden="true" />
      </button>
      {open && (
        <div
          role="menu"
          data-testid="row-action-menu"
          className="absolute right-0 top-full mt-1 z-30 w-48 rounded-lg bg-bg-subtle border border-border-subtle shadow-md py-1"
        >
          {ACTIONS.map((a) => {
            const gate = gateFor(status, a.key);
            return (
              <button
                key={a.key}
                type="button"
                role="menuitem"
                disabled={!gate.allowed}
                title={gate.allowed ? undefined : gate.reason}
                onClick={() => {
                  if (!gate.allowed) return;
                  setOpen(false);
                  onPick(a.key);
                }}
                data-testid={`row-action-${a.key}`}
                className={`w-full text-left px-3 py-1.5 text-[12px] ${
                  gate.allowed
                    ? a.destructive
                      ? "text-fg hover:bg-bg-muted"
                      : "text-fg hover:bg-bg-muted"
                    : "text-fg-muted/50 cursor-not-allowed"
                }`}
              >
                {a.label}
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}
