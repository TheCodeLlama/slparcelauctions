"use client";
import { useState, useEffect } from "react";
import { Button } from "@/components/ui/Button";
import {
  useWarnListing,
  useSuspendListing,
  useCancelListing,
  useReinstateListing,
} from "@/hooks/admin/useAdminListings";
import type { AdminListingAction, AdminListingRow } from "@/lib/admin/types";

const NOTES_MIN = 5;
const NOTES_MAX = 1000;

type ActionConfig = {
  title: string;
  primaryLabel: string;
  variant: "primary" | "destructive";
  body: string;
  placeholder: string;
};

const CONFIG: Record<AdminListingAction, ActionConfig> = {
  warn: {
    title: "Warn seller",
    primaryLabel: "Send warning",
    variant: "destructive",
    body: "The seller will receive a warning notification. The listing remains active.",
    placeholder:
      'e.g. "Please update your description — the title doesn\'t match the parcel size shown in your photos."',
  },
  suspend: {
    title: "Suspend listing",
    primaryLabel: "Suspend listing",
    variant: "destructive",
    body: "The listing will be hidden from browse. The seller will be notified. You can reinstate it later.",
    placeholder:
      'e.g. "Suspending pending review of seller\'s claim that the parcel is on Sansara when it\'s on Heterocera."',
  },
  cancel: {
    title: "Cancel listing",
    primaryLabel: "Cancel listing",
    variant: "destructive",
    body: "**This is permanent.** The listing will be terminated and cannot be reinstated. All bidders will be notified. Use Suspend if you may want to reinstate later.",
    placeholder:
      'e.g. "Cancelled — parcel ownership has changed and seller no longer controls it."',
  },
  reinstate: {
    title: "Reinstate listing",
    primaryLabel: "Reinstate listing",
    variant: "primary",
    body: "The listing will return to ACTIVE status. The remaining auction time will be extended by however long the listing was suspended. The seller will be notified.",
    placeholder:
      'e.g. "Seller has corrected the issue. Reinstating with extended end time."',
  },
};

type Props = {
  open: boolean;
  action: AdminListingAction;
  row: AdminListingRow;
  onClose: () => void;
};

export function ListingActionModal({ open, action, row, onClose }: Props) {
  const [notes, setNotes] = useState<string>("");
  const config = CONFIG[action];

  const warn = useWarnListing();
  const suspend = useSuspendListing();
  const cancel = useCancelListing();
  const reinstate = useReinstateListing();

  const mutation =
    action === "warn" ? warn :
    action === "suspend" ? suspend :
    action === "cancel" ? cancel :
    reinstate;

  useEffect(() => {
    if (!open) return;
    // eslint-disable-next-line react-hooks/set-state-in-effect -- `open` is the external trigger; resetting form state when the modal opens is intentional.
    setNotes("");
  }, [open]);

  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [onClose]);

  if (!open) return null;

  const trimmed = notes.trim();
  const tooShort = trimmed.length > 0 && trimmed.length < NOTES_MIN;
  const valid = trimmed.length >= NOTES_MIN;
  const canSubmit = valid && !mutation.isPending;

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!canSubmit) return;
    mutation.mutate(
      { publicId: row.publicId, body: { notes: trimmed } },
      { onSuccess: onClose },
    );
  }

  return (
    <>
      <div
        className="fixed inset-0 z-40 bg-inverse-surface/20"
        onClick={onClose}
        aria-hidden="true"
      />
      <div
        role="dialog"
        aria-modal="true"
        aria-label={config.title}
        data-testid={`listing-action-modal-${action}`}
        className="fixed inset-0 z-50 flex items-center justify-center p-4"
      >
        <div
          className="w-full max-w-md rounded-lg bg-bg-subtle border border-border-subtle shadow-md p-6 flex flex-col gap-4"
          onClick={(e) => e.stopPropagation()}
        >
          <div className="flex items-start justify-between gap-2">
            <h2 className="text-sm font-semibold text-fg">{config.title}</h2>
            <button
              type="button"
              onClick={onClose}
              aria-label="Close"
              className="p-1.5 rounded-lg text-fg-muted hover:bg-bg-muted"
            >
              ✕
            </button>
          </div>

          <div className="rounded-lg bg-bg-muted p-3 text-[11px] text-fg-muted flex flex-col gap-1">
            <div className="flex justify-between gap-3">
              <span className="shrink-0">Listing</span>
              <span className="text-fg text-right truncate" title={row.title}>
                {row.title}
              </span>
            </div>
            <div className="flex justify-between gap-3">
              <span className="shrink-0">Seller</span>
              <span className="text-fg">{row.sellerUsername}</span>
            </div>
            <div className="flex justify-between gap-3">
              <span className="shrink-0">Status</span>
              <span className="text-fg">{row.status}</span>
            </div>
          </div>

          <p className="text-[12px] text-fg-muted">{config.body}</p>

          <form onSubmit={handleSubmit} className="flex flex-col gap-4">
            <div className="flex flex-col gap-1">
              <label htmlFor="action-notes" className="text-xs font-medium text-fg">
                Notes (sent to seller and audit log) <span className="text-danger">*</span>
              </label>
              <textarea
                id="action-notes"
                rows={4}
                value={notes}
                disabled={mutation.isPending}
                onChange={(e) => setNotes(e.target.value.slice(0, NOTES_MAX))}
                placeholder={config.placeholder}
                data-testid={`listing-action-notes-${action}`}
                className="w-full resize-y rounded-lg bg-bg-muted px-4 py-3 text-fg placeholder:text-fg-muted ring-1 ring-border-subtle focus:outline-none focus:ring-2 focus:ring-brand disabled:opacity-50"
              />
              <div className="flex justify-between text-[11px] font-medium">
                <span className={tooShort ? "text-danger" : "text-fg-muted"}>
                  Min {NOTES_MIN} chars, max {NOTES_MAX}.
                </span>
                <span className="text-fg-muted">
                  {trimmed.length} / {NOTES_MAX}
                </span>
              </div>
            </div>

            <div className="flex justify-end gap-2">
              <Button
                variant="secondary"
                type="button"
                onClick={onClose}
                disabled={mutation.isPending}
              >
                Cancel
              </Button>
              <Button
                variant={config.variant}
                type="submit"
                disabled={!canSubmit}
                loading={mutation.isPending}
                data-testid={`listing-action-submit-${action}`}
              >
                {config.primaryLabel}
              </Button>
            </div>
          </form>
        </div>
      </div>
    </>
  );
}
